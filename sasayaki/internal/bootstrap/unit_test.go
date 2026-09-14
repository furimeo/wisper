package bootstrap

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Design section 7.4 lists what the unit must contain. It is a list of properties rather
// than a style preference: without WatchdogSec a wedged reconcile loop is never noticed,
// without the hardening the daemon's root is the machine's root, and without the start
// limit a broken upgrade hides behind an infinite restart loop.
func TestTheUnitCarriesEveryDirectiveTheDesignRequires(t *testing.T) {
	unit := renderUnit(defaultLayout().unitParameters())

	for _, directive := range []string{
		"Restart=always",
		"RestartSec=2",
		"WatchdogSec=",
		"NotifyAccess=main",
		"Type=notify",
		"NoNewPrivileges=yes",
		"ProtectSystem=strict",
		"ProtectHome=yes",
		"PrivateTmp=yes",
		"ReadWritePaths=/var/lib/wisper /etc/wisper",
		"RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
		"CapabilityBoundingSet=CAP_NET_BIND_SERVICE CAP_NET_ADMIN CAP_NET_RAW",
		"LimitNOFILE=",
		"ExecStart=/usr/local/bin/sasayaki run --config /etc/wisper/node.json --state-dir /var/lib/wisper",
		"[Install]",
		"WantedBy=multi-user.target",
	} {
		if !strings.Contains(unit, directive) {
			t.Errorf("the unit is missing %q", directive)
		}
	}
}

// The rollback only works if systemd knows to run it, and only stops mattering because the
// previous binary is gone. Both halves are in the unit rather than in code.
func TestTheUnitArmsTheRollback(t *testing.T) {
	unit := renderUnit(defaultLayout().unitParameters())

	mustContain(t, unit, "StartLimitBurst=")
	mustContain(t, unit, "StartLimitIntervalSec=")
	mustContain(t, unit, "OnFailure="+rollbackUnitName)

	rollback := renderRollbackUnit(defaultLayout().unitParameters())
	mustContain(t, rollback, "ConditionPathExists=/usr/local/bin/sasayaki.previous")
	mustContain(t, rollback, "Type=oneshot")
	// The previous binary runs it, because the installed one is why it is running.
	mustContain(t, rollback, "ExecStart=/usr/local/bin/sasayaki.previous upgrade --rollback")
}

// Go's answer to "what addresses does this machine have" is a netlink socket. A node that
// enrols advertising nothing is a node no customer's DNS can point at.
func TestTheUnitAllowsNetlinkSoTheNodeCanFindItsOwnAddresses(t *testing.T) {
	mustContain(t, renderUnit(defaultLayout().unitParameters()), "AF_NETLINK")
}

// A unit rendered on a developer's Windows machine has to be a unit systemd can read: a
// backslash in a path is an escape character to it, and every path here is a Linux path.
func TestARenderedUnitNeverContainsABackslash(t *testing.T) {
	for _, unit := range []string{
		renderUnit(defaultLayout().unitParameters()),
		renderRollbackUnit(defaultLayout().unitParameters()),
	} {
		if strings.Contains(unit, `\`) {
			t.Fatalf("a rendered unit contains a backslash:\n%s", unit)
		}
		if strings.Contains(unit, "{{") {
			t.Fatalf("a placeholder was not substituted:\n%s", unit)
		}
	}
}

// A custom --state-dir has to reach every place the unit mentions it, or the daemon starts
// with ProtectSystem=strict over a directory it cannot write to.
func TestACustomLayoutReachesEveryPathInTheUnit(t *testing.T) {
	unit := renderUnit(layout{
		ConfigPath: "/srv/wisper/etc/node.json",
		StateDir:   "/srv/wisper/data",
		BinaryPath: "/opt/wisper/sasayaki",
		UnitDir:    "/etc/systemd/system",
	}.unitParameters())

	mustContain(t, unit, "ExecStart=/opt/wisper/sasayaki run --config /srv/wisper/etc/node.json --state-dir /srv/wisper/data")
	mustContain(t, unit, "ReadWritePaths=/srv/wisper/data /srv/wisper/etc")
}

// deploy/ holds the units an operator reads before they run anything, and `sasayaki
// install` renders them from the constants in this package. Two copies of a systemd unit
// drift the first time somebody edits one, and the one that is wrong is always the one
// being read.
func TestTheUnitsInDeployMatchWhatTheInstallerWrites(t *testing.T) {
	parameters := defaultLayout().unitParameters()

	for name, rendered := range map[string]string{
		"sasayaki.service": renderUnit(parameters),
		rollbackUnitName:   renderRollbackUnit(parameters),
	} {
		path := filepath.Join("..", "..", "..", "deploy", name)
		onDisk, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v. It is generated from unit.go and has to be in the "+
				"repository, because an operator installing by hand reads it", path, err)
		}
		if string(onDisk) != rendered {
			t.Errorf("deploy/%s is not what `sasayaki install` writes. Regenerate it from "+
				"renderUnit in unit.go rather than editing it.", name)
		}
	}
}

// Every path in a layout ends up in a unit file, where a space separates arguments and a
// quote opens a token. Refusing them at the point somebody can still fix it beats
// rendering a unit systemd silently misreads.
func TestALayoutWithAnImpossiblePathIsRefused(t *testing.T) {
	// Built from t.TempDir so the base is absolute on the machine running the test, and
	// each case below is refused for the reason it is testing rather than because
	// /usr/local/bin is not an absolute path on Windows.
	base, _ := testLayout(t)
	if err := base.validate(); err != nil {
		t.Fatalf("the base layout is not valid: %v", err)
	}

	relative := base
	relative.StateDir = "var/lib/wisper"
	if err := relative.validate(); err == nil {
		t.Error("a relative state directory was accepted")
	}

	spaced := base
	spaced.BinaryPath = base.BinaryPath + " daemon"
	if err := spaced.validate(); err == nil {
		t.Error("a binary path with a space in it was accepted")
	}

	empty := base
	empty.ConfigPath = ""
	if err := empty.validate(); err == nil {
		t.Error("an empty configuration path was accepted")
	}
}
