package bootstrap

import (
	"path/filepath"
	"strings"
)

// rollbackUnitName is the unit systemd starts when sasayaki.service gives up.
//
// It exists because of a problem that has no solution inside the daemon: the process that
// applies an upgrade is the process the upgrade replaces, so it is not alive to notice
// that the new binary does not work. Something outside it has to watch, and on a machine
// running systemd the thing outside it is systemd. OnFailure= plus a start limit turns
// "the new binary crash-loops" into "start this other unit", and that other unit is the
// one that puts the previous binary back (design section 7.5).
const rollbackUnitName = "sasayaki-rollback.service"

// unitParameters is what differs between one installation and another. Everything else in
// the unit is policy and is the same everywhere, which is why it is text rather than
// configuration.
type unitParameters struct {
	BinaryPath string
	ConfigPath string
	ConfigDir  string
	StateDir   string
}

func (l layout) unitParameters() unitParameters {
	return unitParameters{
		BinaryPath: l.BinaryPath,
		ConfigPath: l.ConfigPath,
		ConfigDir:  l.configDir(),
		StateDir:   l.StateDir,
	}
}

// renderUnit produces sasayaki.service.
//
// Two groups of directive, and the reasoning behind each is in the file itself rather than
// here, because the file is what an administrator reads at three in the morning and a
// comment in Go source is a comment they will never see.
func renderUnit(p unitParameters) string {
	return substitute(serviceUnitTemplate, p)
}

// renderRollbackUnit produces sasayaki-rollback.service.
func renderRollbackUnit(p unitParameters) string {
	return substitute(rollbackUnitTemplate, p)
}

// substitute fills the template in.
//
// Every path goes through ToSlash on the way in. A unit file is read by systemd on a Linux
// machine whatever the machine that rendered it was, and filepath.Dir on a developer's
// Windows box turns /etc/wisper/node.json into a backslashed path that systemd would take
// as an escape sequence. The paths themselves are Linux paths; this only undoes the host's
// idea of a separator.
func substitute(template string, p unitParameters) string {
	return strings.NewReplacer(
		"{{binary}}", filepath.ToSlash(p.BinaryPath),
		"{{config}}", filepath.ToSlash(p.ConfigPath),
		"{{config-dir}}", filepath.ToSlash(p.ConfigDir),
		"{{state}}", filepath.ToSlash(p.StateDir),
		"{{previous}}", filepath.ToSlash(p.BinaryPath+previousBinarySuffix),
		"{{rollback-unit}}", rollbackUnitName,
	).Replace(template)
}

const serviceUnitTemplate = `# sasayaki - the wisper node daemon.
#
# Written by ` + "`sasayaki install`" + `. Edits survive a re-install only if you move them
# into a drop-in: ` + "`systemctl edit sasayaki.service`" + ` writes one, and re-running the
# installer rewrites this file.
#
# The copy in the repository under deploy/ is this same text. Both come from
# sasayaki/internal/bootstrap/unit.go, and a test fails if they drift apart.

[Unit]
Description=sasayaki - the wisper node daemon
Documentation=https://github.com/furimeo/wisper
After=network-online.target docker.service
# docker.service is wanted and deliberately not required. An engine that dies must leave
# the node reporting degraded and still serving customer traffic through the embedded
# edge; taking the daemon down with it would stop answering ACME too, and the panel would
# lose a node that is otherwise healthy.
Wants=network-online.target docker.service

# Five failures inside two minutes is a daemon that is not going to come up on its own -
# with RestartSec=2 below, that is ten seconds of trying. At that point systemd stops
# restarting it and starts the unit named here, which puts back the binary this one
# replaced. Without a start limit, Restart=always would hide a broken upgrade behind an
# infinite restart loop.
StartLimitIntervalSec=120
StartLimitBurst=5
OnFailure={{rollback-unit}}

[Service]
Type=notify
NotifyAccess=main
ExecStart={{binary}} run --config {{config}} --state-dir {{state}}

# Crash-only: the truth is in SQLite and on disk, so being killed is the same as being
# stopped and there is nothing to tidy up on the way out.
Restart=always
RestartSec=2
KillMode=mixed
TimeoutStopSec=30

# The reconcile loop calls sd_notify(WATCHDOG=1) on every pass. The loop runs every
# fifteen seconds, so a minute of silence is four missed passes: the daemon is wedged
# rather than busy, and being restarted is better than being trusted.
WatchdogSec=60

# --- Hardening (design section 7.4) ------------------------------------------
#
# The daemon runs as root because it binds :80 and :443 and talks to the Docker socket.
# Everything below narrows what that root can reach.

NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
# The only two places it may write. Customer data, SQLite, sites, volumes and certificates
# are all under the state directory; the credential and the node key are in the other.
ReadWritePaths={{state}} {{config-dir}}
# AF_UNIX reaches the Docker socket, AF_INET/AF_INET6 serve customers and dial the panel.
# AF_NETLINK is not in design section 7.4's list and is here anyway: Go asks the kernel for
# this machine's addresses over a netlink route socket, and without it the node enrols
# advertising no address at all.
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
# Binding :80 and :443, and programming iptables egress filters on tenant bridges are
# the privileged operations the daemon performs itself.
CapabilityBoundingSet=CAP_NET_BIND_SERVICE CAP_NET_ADMIN CAP_NET_RAW
AmbientCapabilities=CAP_NET_BIND_SERVICE CAP_NET_ADMIN CAP_NET_RAW
RestrictSUIDSGID=yes
RestrictRealtime=yes
RestrictNamespaces=yes
LockPersonality=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectClock=yes
# Caddy holds a socket per connection and the engine holds one per container. The default
# of 1024 is reached by a node with a hundred busy sites on it.
LimitNOFILE=1048576
# A container being killed for using too much memory is the container's problem. The
# daemon that reported it must survive to say so.
OOMPolicy=continue

[Install]
WantedBy=multi-user.target
`

const rollbackUnitTemplate = `# Undo an upgrade that did not come back.
#
# Started by systemd, never by hand: sasayaki.service names it in OnFailure=, so it runs
# exactly when the daemon has failed to stay up through five restarts. What it does is put
# back the binary the upgrade replaced and start the service again (design section 7.5).
#
# Written by ` + "`sasayaki install`" + `, from sasayaki/internal/bootstrap/unit.go. The copy
# in the repository under deploy/ is the same text and a test keeps it that way.

[Unit]
Description=Roll sasayaki back to the binary it was upgraded from
# Nothing to go back to means nothing to do. After a rollback the previous binary has been
# moved into place, so this condition stops a second failure - for some unrelated reason -
# from doing anything at all.
ConditionPathExists={{previous}}

[Service]
Type=oneshot
# The previous binary runs this, not the installed one: the installed one is the reason
# this unit was started. It refuses to act unless an upgrade is actually in flight, so an
# unrelated crash months later does not silently downgrade the node.
ExecStart={{previous}} upgrade --rollback --target {{binary}} --config {{config}}
`
