package bootstrap

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// machine is the host being inspected, and every way of looking at it.
//
// Each field is a seam. Not because a second implementation is planned - there is
// exactly one real machine - but because the checks below are the code that has to be
// proved to behave correctly when the machine is broken, and there is no way to break a
// real one on demand. A test builds a machine whose docker probe returns "connection
// refused" and asserts that doctor fails with a remedy rather than panicking; that test
// is impossible if the probe is a package-level function.
//
// The four roots are directories rather than functions for the same reason: a test
// writes a fake /proc into t.TempDir() and the parsing code is exercised unchanged.
type machine struct {
	// The pseudo-filesystems and configuration directories every probe reads from:
	// "/proc", "/sys", "/etc" and "/run" on a real node.
	procRoot string
	sysRoot  string
	etcRoot  string
	runRoot  string

	// dbusMachineIDPath is the second place a machine-id can live, on a host that does
	// not have /etc/machine-id. Named rather than derived so a test can move it.
	dbusMachineIDPath string

	// stateDir is the directory whose filesystem decides whether disk quotas can be
	// enforced. It may not exist yet - doctor runs before install creates it - so the
	// filesystem check walks up to the nearest existing ancestor.
	stateDir string

	// panel is the --panel value, empty when doctor was run without one. It turns on the
	// reachability check and gives the clock check something authoritative to compare
	// against.
	panel string

	// dockerHost overrides the socket, for a node whose engine is not in the usual place.
	dockerHost string

	// uid is the effective user id. Nodes run as root: binding :80 needs it, and the DMI
	// serials the machine fingerprint is derived from are 0400.
	uid int

	// now is the clock. Fixed in tests so a rendered report is comparable.
	now func() time.Time

	// lookPath finds an executable. Returns exec.ErrNotFound when it is absent.
	lookPath func(name string) (string, error)

	// runCommand runs a program and returns its combined output. Every caller passes an
	// argument slice; nothing here ever builds a shell string.
	runCommand func(ctx context.Context, name string, args ...string) ([]byte, error)

	// dial opens an outbound TCP connection, for the check that asks whether this
	// machine can reach the internet at all.
	dial func(ctx context.Context, network, address string) (io.Closer, error)

	// listen tries to take a port, and closes it again immediately. The only honest way
	// to answer "is :80 free" - reading /proc/net/tcp misses a socket held in another
	// network namespace, and a port that cannot be bound is a port that is not free
	// whatever the reason.
	listen func(network, address string) (io.Closer, error)

	// diskSpace answers with the total and free bytes of the filesystem holding a path.
	diskSpace func(path string) (total, free int64, err error)

	// probePanel measures one HTTP round trip to the panel: whether it answered, and
	// what time it said it was. Cached, because the clock check and the reachability
	// check both want it and a node behind a slow tunnel should pay for it once.
	probePanel func(ctx context.Context, endpoint string) panelProbe

	// probeDocker asks the engine about itself. Cached for the same reason: the docker
	// check, the isolation check and the cgroups cross-reference all want the answer.
	probeDocker func(ctx context.Context, host string) (dockerReport, error)

	probeOnce   sync.Once
	probeResult panelProbe

	dockerOnce   sync.Once
	dockerResult dockerReport
	dockerErr    error
}

// panelProbe is one attempt to reach the panel.
//
// It separates the three answers an operator needs to tell apart, because "the installer
// did not work" covers all of them and helps with none: the name did not resolve, the
// address refused the connection, or the panel answered. A rejected token is a fourth
// thing entirely and cannot be seen from here - it needs a credential - which is exactly
// why enrolment reports it separately (design section 7.2).
type panelProbe struct {
	// Endpoint is what was dialled, as host:port.
	Endpoint string

	// Reachable is true when the panel answered at the TLS or TCP level.
	Reachable bool

	// Stage is where it stopped: "resolve", "connect", "tls" or "http".
	Stage string

	// Err is why, when it did not answer.
	Err error

	// ServerTime is the panel's clock, from the HTTP Date header. Zero when the panel
	// answered at the transport level but produced no dated response.
	ServerTime time.Time

	// LocalTime is this machine's clock at the moment ServerTime was read. The pair is
	// what the clock check subtracts; either one on its own says nothing.
	LocalTime time.Time

	// RoundTrip is how long the exchange took. Half of it is the one-way delay the clock
	// comparison has to allow for.
	RoundTrip time.Duration
}

// newMachine describes this host.
func newMachine(options PreflightOptions) *machine {
	stateDir := options.StateDir
	if stateDir == "" {
		stateDir = DefaultStateDir
	}
	return &machine{
		procRoot:          "/proc",
		sysRoot:           "/sys",
		etcRoot:           "/etc",
		runRoot:           "/run",
		dbusMachineIDPath: "/var/lib/dbus/machine-id",

		stateDir:    stateDir,
		panel:       options.Panel,
		dockerHost:  options.DockerHost,
		uid:         os.Geteuid(),
		now:         time.Now,
		lookPath:    exec.LookPath,
		runCommand:  runCommand,
		dial:        dialOnce,
		listen:      listenOnce,
		diskSpace:   diskSpace,
		probePanel:  reachPanel,
		probeDocker: inspectDocker,
	}
}

// panelReading runs the panel probe at most once per doctor run.
func (m *machine) panelReading(ctx context.Context) panelProbe {
	m.probeOnce.Do(func() {
		if m.panel == "" {
			m.probeResult = panelProbe{Stage: "skipped"}
			return
		}
		m.probeResult = m.probePanel(ctx, m.panel)
	})
	return m.probeResult
}

// dockerReading asks the engine about itself at most once per doctor run.
func (m *machine) dockerReading(ctx context.Context) (dockerReport, error) {
	m.dockerOnce.Do(func() {
		m.dockerResult, m.dockerErr = m.probeDocker(ctx, m.dockerHost)
	})
	return m.dockerResult, m.dockerErr
}

// readFile reads a file under one of the pseudo-filesystem roots.
func (m *machine) readProc(elements ...string) (string, error) {
	return readTrimmed(filepath.Join(append([]string{m.procRoot}, elements...)...))
}

func (m *machine) readSys(elements ...string) (string, error) {
	return readTrimmed(filepath.Join(append([]string{m.sysRoot}, elements...)...))
}

func (m *machine) readEtc(elements ...string) (string, error) {
	return readTrimmed(filepath.Join(append([]string{m.etcRoot}, elements...)...))
}

func (m *machine) runPath(elements ...string) string {
	return filepath.Join(append([]string{m.runRoot}, elements...)...)
}

func readTrimmed(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(raw)), nil
}

// runCommand is the real implementation of the seam. exec.CommandContext with an
// argument slice, never a shell string: a hostname with a semicolon in it is a machine
// somebody misconfigured, not a machine that gets to run commands as root.
func runCommand(ctx context.Context, name string, args ...string) ([]byte, error) {
	command := exec.CommandContext(ctx, name, args...)
	output, err := command.CombinedOutput()
	if err != nil {
		return output, fmt.Errorf("%s: %w", name, err)
	}
	return output, nil
}

// dialOnce is the real outbound connection. The caller closes it.
func dialOnce(ctx context.Context, network, address string) (io.Closer, error) {
	var dialer net.Dialer
	connection, err := dialer.DialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}
	return connection, nil
}

// listenOnce binds an address and gives it straight back. The listener is returned rather
// than closed here so the caller decides when to release it - holding two ports at once
// is how the port checks avoid reporting :443 free because :80 was still shutting down.
func listenOnce(network, address string) (io.Closer, error) {
	listener, err := net.Listen(network, address)
	if err != nil {
		return nil, err
	}
	return listener, nil
}
