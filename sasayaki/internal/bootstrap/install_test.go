package bootstrap

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// installer builds an installation whose every dependency is a fake, so a test can say
// "this machine fails its preflight" or "the panel refuses the token" in one line.
type installer struct {
	installation
	init  *fakeInit
	clock *virtualClock
	out   *strings.Builder

	// enrolments counts the calls that reached the panel, which is how the tests tell
	// "kept the credential it had" from "enrolled again".
	enrolments int
}

func newInstaller(t *testing.T, where layout, tokenFile string) *installer {
	t.Helper()

	init := workingInit()
	// Nothing is installed yet: is-active on a unit systemd has never heard of.
	init.unitKnown = false
	init.states = []string{unitActive}
	clock := newClock()
	out := &strings.Builder{}

	harness := &installer{init: init, clock: clock, out: out}
	watch := clock.watch(init.seam())
	harness.installation = installation{
		layout:    where,
		panel:     "https://panel.example",
		tokenFile: tokenFile,
		start:     true,
		systemd:   init.seam(),
		stdin:     strings.NewReader(""),
		out:       out,
		watch:     &watch,
		now:       clock.now,
		host:      healthyMachine(t),
		enrol: func(_ context.Context, request rpc.EnrolmentRequest) (rpc.Credential, error) {
			harness.enrolments++
			return rpc.Credential{
				NodeID:                 "nd_01",
				NodeName:               "berlin-1",
				Token:                  "cred_" + request.BootstrapToken,
				Panel:                  "https://panel.example:443",
				PanelCertificateSHA256: strings.Repeat("ab", 32),
				EnrolledAt:             clock.now(),
			}, nil
		},
		probe: func(_ context.Context, path string) (string, error) {
			return versionInFile(path)
		},
		executable: func() (string, error) { return "", errors.New("no --binary was given") },
	}
	return harness
}

// A fake binary is a file whose contents are its version, so the probe seam can answer
// without anything being executed and a test can prove which bytes ended up where.
func fakeBinary(t *testing.T, path, version string) {
	t.Helper()
	writeFile(t, path, "#!sasayaki\nversion="+version+"\n")
}

func versionInFile(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if suffix, found := strings.CutPrefix(line, "version="); found {
			return suffix, nil
		}
	}
	return "", fmt.Errorf("%s is not a sasayaki binary", filepath.Base(path))
}

func writeToken(t *testing.T, root string) string {
	t.Helper()
	path := filepath.Join(root, "token.txt")
	writeFile(t, path, "wsp_bootstrap_token\n")
	return path
}

// Design section 7.2, and the first thing the quality gate in section 13.4 looks for: a
// failed preflight leaves the machine exactly as it was.
func TestAFailedPreflightInstallsNothingAtAll(t *testing.T) {
	where, root := testLayout(t)
	tokenFile := writeToken(t, root)
	harness := newInstaller(t, where, tokenFile)
	// No Docker engine: a required check, and a machine that cannot host anything.
	harness.installation.host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{Host: "unix:///var/run/docker.sock"},
			errors.New("connect: no such file or directory")
	}
	harness.installation.source = sourceBinary(t, root, "v0.4.0")

	before := everythingUnder(t, root)
	err := harness.installation.run(context.Background())

	if !errors.Is(err, errFailedPreflight) {
		t.Fatalf("install returned %v, want the preflight failure", err)
	}
	if difference := added(before, everythingUnder(t, root)); len(difference) > 0 {
		t.Fatalf("a failed preflight wrote %v", difference)
	}
	mustNotExist(t, where.ConfigPath)
	mustNotExist(t, where.keyPath())
	mustNotExist(t, where.BinaryPath)
	mustNotExist(t, where.unitPath())
	if harness.enrolments != 0 {
		t.Fatal("a machine that failed its preflight was offered to the panel")
	}
	// The token is single-use and lives fifteen minutes. Spending it on an installation
	// that was never going to happen would cost the operator a trip back to the panel.
	mustExist(t, tokenFile)
	if len(harness.init.commands) != 0 {
		t.Fatalf("systemd was asked to do %v", harness.init.commands)
	}
}

func TestAWholeInstallationLeavesARunningService(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.installation.source = sourceBinary(t, root, "v0.4.0")

	if err := harness.installation.run(context.Background()); err != nil {
		t.Fatalf("install: %v", err)
	}

	mustExist(t, where.BinaryPath)
	mustExist(t, where.ConfigPath)
	mustExist(t, where.keyPath())
	mustExist(t, where.unitPath())
	mustExist(t, filepath.Join(where.UnitDir, rollbackUnitName))
	mustBePrivate(t, where.ConfigPath)
	mustBePrivate(t, where.keyPath())
	mustExist(t, where.StateDir)

	credential, err := rpc.LoadCredential(where.ConfigPath)
	if err != nil {
		t.Fatalf("the credential that was just written does not load: %v", err)
	}
	if credential.NodeID != "nd_01" {
		t.Fatalf("node id is %q", credential.NodeID)
	}
	// The credential's own String is what keeps the token out of logs; make sure the file
	// is the only place it exists.
	if strings.Contains(harness.out.String(), credential.Token) {
		t.Fatal("the credential was printed to the terminal")
	}

	for _, wanted := range []string{"daemon-reload", "enable " + unitName, "restart " + unitName} {
		if !harness.init.ran(wanted) {
			t.Errorf("systemctl %s was never run; it ran %v", wanted, harness.init.commands)
		}
	}
	mustContain(t, readFile(t, where.unitPath()), "ExecStart="+filepath.ToSlash(where.BinaryPath))
}

// Running the installer twice is what an operator does. It has to be an upgrade.
func TestReRunningTheInstallerWithoutATokenKeepsTheCredential(t *testing.T) {
	where, root := testLayout(t)
	first := newInstaller(t, where, writeToken(t, root))
	first.installation.source = sourceBinary(t, root, "v0.4.0")
	if err := first.installation.run(context.Background()); err != nil {
		t.Fatalf("first install: %v", err)
	}
	before := readFile(t, where.ConfigPath)

	second := newInstaller(t, where, "")
	second.init.unitKnown = true
	newer := filepath.Join(root, "build", "sasayaki-v0.5.0")
	fakeBinary(t, newer, "v0.5.0")
	second.installation.source = newer

	if err := second.installation.run(context.Background()); err != nil {
		t.Fatalf("second install: %v", err)
	}

	if second.enrolments != 0 {
		t.Fatal("a re-install with no token enrolled again and burned a node record")
	}
	if readFile(t, where.ConfigPath) != before {
		t.Fatal("the credential changed on a re-install that was given no token")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.5.0" {
		t.Fatalf("the installed binary is %s, want the one this run was given", version)
	}
	mustExist(t, where.previousBinaryPath())
	if version, _ := versionInFile(where.previousBinaryPath()); version != "v0.4.0" {
		t.Fatalf("the kept binary is %s, want the one that was replaced", version)
	}
}

// The upgrade half of "re-running the installer is an upgrade": a new binary that will not
// stay up puts the old one back rather than leaving the node down.
func TestAReInstallOntoABrokenBinaryRollsBack(t *testing.T) {
	where, root := testLayout(t)
	first := newInstaller(t, where, writeToken(t, root))
	first.installation.source = sourceBinary(t, root, "v0.4.0")
	if err := first.installation.run(context.Background()); err != nil {
		t.Fatalf("first install: %v", err)
	}

	second := newInstaller(t, where, "")
	second.init.unitKnown = true
	// It starts, and then it is gone: the shape of a daemon that cannot read its state.
	// The third entry is the restart onto the binary that worked, and it repeats.
	second.init.states = []string{unitActive, unitFailed, unitActive}
	broken := filepath.Join(root, "build", "sasayaki-v0.5.0")
	fakeBinary(t, broken, "v0.5.0")
	second.installation.source = broken

	err := second.installation.run(context.Background())

	if err == nil {
		t.Fatal("an installation whose service never came up reported success")
	}
	mustContain(t, err.Error(), "back on")
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is %s; the broken one should have been undone", version)
	}
	mustNotExist(t, where.previousBinaryPath())
}

// A machine with no systemd is a container or a WSL distribution somebody is testing on.
// The node it gets is a working one it starts by hand, and the installer says exactly
// that instead of pretending or refusing.
func TestWithoutSystemdTheUnitIsWrittenAndNothingIsStarted(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.init.booted = false
	harness.installation.systemd = harness.init.seam()
	harness.installation.source = sourceBinary(t, root, "v0.4.0")

	if err := harness.installation.run(context.Background()); err != nil {
		t.Fatalf("install: %v", err)
	}

	mustExist(t, where.unitPath())
	if len(harness.init.commands) != 0 {
		t.Fatalf("systemctl was called on a machine without systemd: %v", harness.init.commands)
	}
	mustContain(t, harness.out.String(), "not running systemd")
	mustContain(t, harness.out.String(), where.BinaryPath+" run")
}

// Not enrolled, no token: there is nothing to install, and the message has to say what to
// do rather than fail on a missing file three steps later.
func TestInstallingWithNoTokenAndNoCredentialSaysWhatToDo(t *testing.T) {
	where, _ := testLayout(t)
	harness := newInstaller(t, where, "")

	err := harness.installation.run(context.Background())

	if err == nil {
		t.Fatal("an unenrolled node with no token cannot be installed")
	}
	mustContain(t, err.Error(), "--token-file")
	mustNotExist(t, where.BinaryPath)
}

// The panel refusing a token and the network being down look identical from a terminal.
// They send an operator to completely different places (design section 7.2).
func TestARefusedTokenIsReportedAsATokenAndNotAsTheNetwork(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.installation.source = sourceBinary(t, root, "v0.4.0")
	harness.installation.enrol = func(context.Context, rpc.EnrolmentRequest) (rpc.Credential, error) {
		return rpc.Credential{}, status.Error(codes.PermissionDenied,
			"That bootstrap token has expired. Issue a fresh one and run the installer again.")
	}

	err := harness.installation.run(context.Background())

	if err == nil {
		t.Fatal("a refused enrolment reported success")
	}
	mustContain(t, err.Error(), "expired")
	mustContain(t, err.Error(), "the token and not the network")
	// Nothing that outlives the failure: no service, no binary in place.
	mustNotExist(t, where.BinaryPath)
	mustNotExist(t, where.unitPath())
}

func TestAnUnreachablePanelIsReportedAsTheNetworkAndNotTheToken(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.installation.source = sourceBinary(t, root, "v0.4.0")
	harness.installation.enrol = func(context.Context, rpc.EnrolmentRequest) (rpc.Credential, error) {
		return rpc.Credential{}, status.Error(codes.Unavailable,
			"connection error: desc = transport: dial tcp: connect: connection refused")
	}

	err := harness.installation.run(context.Background())

	if err == nil {
		t.Fatal("an unreachable panel reported success")
	}
	mustContain(t, err.Error(), "network rather than the token")
	mustContain(t, err.Error(), "has not been spent")
}

// The panel suspends both nodes when one fingerprint arrives twice, and answers
// FAILED_PRECONDITION. Nothing on this machine will change that, and the message has to
// say so rather than suggesting another token (design section 7.3).
func TestADuplicateFingerprintIsReportedAsSomethingATokenCannotFix(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.installation.source = sourceBinary(t, root, "v0.4.0")
	harness.installation.enrol = func(context.Context, rpc.EnrolmentRequest) (rpc.Credential, error) {
		return rpc.Credential{}, status.Error(codes.FailedPrecondition,
			"Another node is already enrolled with this machine's fingerprint. Both have "+
				"been suspended.")
	}

	err := harness.installation.run(context.Background())

	if err == nil {
		t.Fatal("a duplicate fingerprint reported success")
	}
	mustContain(t, err.Error(), "suspended")
	mustContain(t, err.Error(), "Nothing here will change that")
}

// The enrolment carries the fingerprint and the preflight, because the panel decides both
// clone detection and placement from them.
func TestTheEnrolmentCarriesTheFingerprintAndTheDoctorReport(t *testing.T) {
	where, root := testLayout(t)
	harness := newInstaller(t, where, writeToken(t, root))
	harness.installation.source = sourceBinary(t, root, "v0.4.0")

	var sent rpc.EnrolmentRequest
	harness.installation.enrol = func(_ context.Context, request rpc.EnrolmentRequest) (rpc.Credential, error) {
		sent = request
		return rpc.Credential{
			NodeID: "nd_01", NodeName: "berlin-1", Token: "cred", Panel: "https://panel.example:443",
			PanelCertificateSHA256: strings.Repeat("ab", 32), EnrolledAt: time.Now(),
		}, nil
	}

	if err := harness.installation.run(context.Background()); err != nil {
		t.Fatalf("install: %v", err)
	}

	if len(sent.MachineFingerprint) != 64 {
		t.Fatalf("fingerprint is %q, want a hex SHA-256", sent.MachineFingerprint)
	}
	if sent.Doctor == nil || !sent.Doctor.GetRequiredChecksPassed() {
		t.Fatal("the panel refuses an enrolment whose doctor report did not pass, so one " +
			"has to be sent")
	}
	if sent.BootstrapToken != "wsp_bootstrap_token" {
		t.Fatalf("bootstrap token is %q", sent.BootstrapToken)
	}
	if len(sent.Key) == 0 {
		t.Fatal("no node key was generated")
	}
}

// The node key outlives the credential: re-enrolling must present the same identity the
// panel recorded rather than looking like a different machine.
func TestReEnrollingKeepsTheNodeKey(t *testing.T) {
	where, root := testLayout(t)
	first := newInstaller(t, where, writeToken(t, root))
	first.installation.source = sourceBinary(t, root, "v0.4.0")
	if err := first.installation.run(context.Background()); err != nil {
		t.Fatalf("first install: %v", err)
	}
	key := readFile(t, where.keyPath())

	second := newInstaller(t, where, writeToken(t, root))
	second.init.unitKnown = true
	second.installation.source = first.installation.source
	if err := second.installation.run(context.Background()); err != nil {
		t.Fatalf("second install: %v", err)
	}

	if second.enrolments != 1 {
		t.Fatalf("a re-install with a token enrolled %d times, want once", second.enrolments)
	}
	if readFile(t, where.keyPath()) != key {
		t.Fatal("the node key was regenerated; the panel would see a different machine")
	}
}

// sourceBinary is a binary sitting somewhere an installer would find one.
func sourceBinary(t *testing.T, root, version string) string {
	t.Helper()
	path := filepath.Join(root, "build", "sasayaki-"+version)
	fakeBinary(t, path, version)
	return path
}

// added is what the second listing has that the first did not.
func added(before, after []string) []string {
	seen := make(map[string]bool, len(before))
	for _, path := range before {
		seen[path] = true
	}
	var difference []string
	for _, path := range after {
		if !seen[path] {
			difference = append(difference, path)
		}
	}
	return difference
}

var _ io.Writer = (*strings.Builder)(nil)
