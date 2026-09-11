package bootstrap

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

// upgrader is an installed node with a new binary waiting to be fetched.
type upgrader struct {
	steps upgradeSteps
	init  *fakeInit
	clock *virtualClock
	out   *strings.Builder

	// served is what the fake download returns, and its checksum is what the panel would
	// have published alongside it.
	served    []byte
	servedSum string

	// fetches counts downloads, so a test can prove nothing was fetched twice or at all.
	fetches int
}

func newUpgrader(t *testing.T, runningVersion, offeredVersion string) (*upgrader, layout) {
	t.Helper()

	where, _ := testLayout(t)
	fakeBinary(t, where.BinaryPath, runningVersion)
	writeFile(t, where.ConfigPath, "{}")

	offered := []byte("#!sasayaki\nversion=" + offeredVersion + "\n")
	sum := sha256.Sum256(offered)

	init := workingInit()
	clock := newClock()
	harness := &upgrader{
		init: init, clock: clock, out: &strings.Builder{},
		served: offered, servedSum: hex.EncodeToString(sum[:]),
	}
	harness.steps = upgradeSteps{
		layout:  where,
		systemd: init.seam(),
		watch:   clock.watch(init.seam()),
		fetch: func(context.Context, string) ([]byte, error) {
			harness.fetches++
			return harness.served, nil
		},
		probe: func(_ context.Context, path string) (string, error) { return versionInFile(path) },
		now:   clock.now,
		out:   harness.out,
	}
	return harness, where
}

func (u *upgrader) fromPanel() upgradeIntent {
	return upgradeIntent{
		source:    upgradeSource{url: "https://panel.example/dist/sasayaki", sha256: u.servedSum},
		restart:   true,
		supervise: true,
	}
}

// An upgrade that installs an unverified binary is a remote code execution feature. The
// checksum is checked before anything on disk is touched, so a mismatch costs nothing.
func TestAChecksumMismatchReplacesNothing(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	intent := harness.fromPanel()
	intent.source.sha256 = strings.Repeat("00", 32)

	_, err := harness.steps.apply(context.Background(), intent)

	if err == nil {
		t.Fatal("a binary with the wrong checksum was installed")
	}
	mustContain(t, err.Error(), "Nothing has been replaced")
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is now %s", version)
	}
	mustNotExist(t, where.previousBinaryPath())
	mustNotExist(t, where.BinaryPath+".incoming")
	mustNotExist(t, upgradeMarkerPath(where.ConfigPath))
	if harness.init.ran("restart") {
		t.Fatal("the service was restarted for an upgrade that did not happen")
	}
}

// The failure a checksum cannot catch: exactly the bytes the panel published, for the
// wrong architecture. Running it before it is in place is what turns that into a refusal
// rather than a node that will not start.
func TestABinaryThatWillNotRunIsNeverInstalled(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	harness.served = []byte("\x7fELF for some other machine")
	sum := sha256.Sum256(harness.served)
	intent := harness.fromPanel()
	intent.source.sha256 = hex.EncodeToString(sum[:])

	_, err := harness.steps.apply(context.Background(), intent)

	if err == nil {
		t.Fatal("a binary this machine cannot run was installed")
	}
	mustContain(t, err.Error(), "wrong build")
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is now %s", version)
	}
	mustNotExist(t, where.BinaryPath+".incoming")
}

func TestAGoodUpgradeReplacesTheBinaryAndKeepsTheOldOne(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")

	result, err := harness.steps.apply(context.Background(), harness.fromPanel())
	if err != nil {
		t.Fatalf("upgrade: %v", err)
	}

	if result.GetRolledBack() {
		t.Fatal("a healthy upgrade reported a rollback")
	}
	if result.GetPreviousVersion() != "v0.4.0" || result.GetNewVersion() != "v0.5.0" {
		t.Fatalf("result says %s -> %s", result.GetPreviousVersion(), result.GetNewVersion())
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.5.0" {
		t.Fatalf("the installed binary is %s", version)
	}
	if version, _ := versionInFile(where.previousBinaryPath()); version != "v0.4.0" {
		t.Fatalf("the kept binary is %s", version)
	}
	if !harness.init.ran("restart " + unitName) {
		t.Fatalf("the service was not restarted: %v", harness.init.commands)
	}
	// Confirmed healthy, so there is nothing left for the rollback unit to act on.
	mustNotExist(t, upgradeMarkerPath(where.ConfigPath))
	mustNotExist(t, where.BinaryPath+".incoming")
}

// Section 13.4: an upgrade that fails goes back to the previous binary, by itself.
func TestAnUpgradeThatDoesNotComeBackRollsItselfBack(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	// Up, then gone, then up again on the binary that worked.
	harness.init.states = []string{unitActive, unitFailed, unitActive}

	result, err := harness.steps.apply(context.Background(), harness.fromPanel())
	if err != nil {
		t.Fatalf("a rollback that worked is not an error from apply: %v", err)
	}

	if !result.GetRolledBack() {
		t.Fatal("the result does not say it rolled back, so the panel would call this a " +
			"successful upgrade")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is %s, want the one that worked", version)
	}
	// The previous binary was moved rather than copied: what is live now is it, and the
	// rollback unit's ConditionPathExists is no longer satisfied.
	mustNotExist(t, where.previousBinaryPath())
	mustNotExist(t, upgradeMarkerPath(where.ConfigPath))
	mustContain(t, result.GetDetail(), "back on v0.4.0")
	if !harness.init.ran("reset-failed") {
		t.Fatal("the start limit was not cleared, so systemd would refuse to start the " +
			"unit however good the binary is")
	}
}

// The daemon upgrading itself cannot watch what happens next, because the restart kills
// it. The marker it leaves behind is what lets systemd's OnFailure unit tell an upgrade
// that did not come back from an unrelated crash.
func TestAnUnsupervisedUpgradeLeavesTheMarkerForSystemd(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	intent := harness.fromPanel()
	intent.supervise = false

	result, err := harness.steps.apply(context.Background(), intent)
	if err != nil {
		t.Fatalf("upgrade: %v", err)
	}

	mustExist(t, upgradeMarkerPath(where.ConfigPath))
	mustExist(t, where.previousBinaryPath())
	if !harness.init.ran("restart " + unitName) {
		t.Fatal("the restart was never requested")
	}
	mustContain(t, result.GetDetail(), rollbackUnitName)
}

func TestUpgradingToTheVersionAlreadyRunningDoesNothing(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")

	result, err := harness.steps.apply(context.Background(), harness.fromPanel())
	if err != nil {
		t.Fatalf("upgrade: %v", err)
	}

	mustContain(t, result.GetDetail(), "already running")
	mustNotExist(t, where.previousBinaryPath())
	if harness.init.ran("restart") {
		t.Fatal("the node was restarted for no reason")
	}
}

func TestForceReinstallsTheSameVersion(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")
	intent := harness.fromPanel()
	intent.force = true

	if _, err := harness.steps.apply(context.Background(), intent); err != nil {
		t.Fatalf("upgrade --force: %v", err)
	}

	mustExist(t, where.previousBinaryPath())
	if !harness.init.ran("restart " + unitName) {
		t.Fatal("--force did not restart the service")
	}
}

// An offline node: the operator carried the file on themselves, so there is no URL and
// nothing to download.
func TestALocalBinaryIsInstalledWithoutADownload(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	carried := filepath.Join(filepath.Dir(where.ConfigPath), "sasayaki-v0.5.0")
	fakeBinary(t, carried, "v0.5.0")

	result, err := harness.steps.apply(context.Background(), upgradeIntent{
		source:    upgradeSource{localPath: carried},
		restart:   true,
		supervise: true,
	})
	if err != nil {
		t.Fatalf("upgrade --binary: %v", err)
	}

	if harness.fetches != 0 {
		t.Fatal("an offline upgrade went to the network")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.5.0" {
		t.Fatalf("the installed binary is %s", version)
	}
	if result.GetNewVersion() != "v0.5.0" {
		t.Fatalf("result says the new version is %q", result.GetNewVersion())
	}
	// The hash of what was installed is printed, because on this path the operator is the
	// only thing verifying it.
	mustContain(t, harness.out.String(), "sha256 ")
}

// The rollback unit runs on any failure that trips the start limit. Without an upgrade in
// flight it must do nothing at all: silently downgrading a node's agent because Docker
// died is a change nobody asked for.
func TestRollbackWithNoUpgradeInFlightChangesNothing(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	fakeBinary(t, where.previousBinaryPath(), "v0.3.0")

	result, err := harness.steps.rollBackInFlight(context.Background())
	if err != nil {
		t.Fatalf("rollback: %v", err)
	}

	if result.GetRolledBack() {
		t.Fatal("a node with no upgrade in flight was downgraded")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is %s", version)
	}
	mustExist(t, where.previousBinaryPath())
	if harness.init.ran("restart") {
		t.Fatal("the service was restarted for a rollback that did not happen")
	}
}

// A marker nobody cleared eventually stops meaning anything. An outage in December is not
// an upgrade from September coming home to roost.
func TestRollbackIgnoresAMarkerThatIsTooOld(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")
	fakeBinary(t, where.previousBinaryPath(), "v0.4.0")
	writeMarker(t, where, upgradeMarker{
		PreviousVersion: "v0.4.0",
		NewVersion:      "v0.5.0",
		Target:          where.BinaryPath,
		PreviousBinary:  where.previousBinaryPath(),
		StartedAt:       harness.clock.now().Add(-2 * upgradeWindow),
	})

	result, err := harness.steps.rollBackInFlight(context.Background())
	if err != nil {
		t.Fatalf("rollback: %v", err)
	}

	if result.GetRolledBack() {
		t.Fatal("a stale marker triggered a downgrade")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.5.0" {
		t.Fatalf("the installed binary is %s", version)
	}
	mustNotExist(t, upgradeMarkerPath(where.ConfigPath))
}

// The path systemd takes when a self-upgrade crash-loops: the marker is there, the
// previous binary is there, and this is what puts the node back together.
func TestRollbackInFlightRestoresThePreviousBinary(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")
	fakeBinary(t, where.previousBinaryPath(), "v0.4.0")
	writeMarker(t, where, upgradeMarker{
		PreviousVersion: "v0.4.0",
		NewVersion:      "v0.5.0",
		Target:          where.BinaryPath,
		PreviousBinary:  where.previousBinaryPath(),
		StartedAt:       harness.clock.now(),
	})

	result, err := harness.steps.rollBackInFlight(context.Background())
	if err != nil {
		t.Fatalf("rollback: %v", err)
	}

	if !result.GetRolledBack() {
		t.Fatal("the rollback did not report itself")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is %s, want the one before the upgrade", version)
	}
	mustNotExist(t, where.previousBinaryPath())
	mustNotExist(t, upgradeMarkerPath(where.ConfigPath))
	if !harness.init.ran("restart " + unitName) {
		t.Fatal("the service was not started again after the rollback")
	}
}

// Nothing to go back to is the one case where a failed upgrade cannot be undone, and
// saying so plainly is the whole of what can be done about it.
func TestARollbackWithNoPreviousBinarySaysSo(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")
	writeMarker(t, where, upgradeMarker{
		NewVersion:     "v0.5.0",
		Target:         where.BinaryPath,
		PreviousBinary: where.previousBinaryPath(),
		StartedAt:      harness.clock.now(),
	})

	result, err := harness.steps.rollBackInFlight(context.Background())

	if err == nil {
		t.Fatal("a rollback with nothing to roll back to reported success")
	}
	if result.GetRolledBack() {
		t.Fatal("the result claims a rollback happened")
	}
	mustContain(t, err.Error(), "installed by hand")
}

// ConfirmUpgrade is what the daemon calls once it is up. After it, no later failure can
// be blamed on the upgrade.
func TestConfirmingAnUpgradeDisarmsTheRollback(t *testing.T) {
	harness, where := newUpgrader(t, "v0.5.0", "v0.5.0")
	fakeBinary(t, where.previousBinaryPath(), "v0.4.0")
	writeMarker(t, where, upgradeMarker{
		PreviousVersion: "v0.4.0", NewVersion: "v0.5.0",
		Target: where.BinaryPath, PreviousBinary: where.previousBinaryPath(),
		StartedAt: harness.clock.now(),
	})

	if err := ConfirmUpgrade(where.ConfigPath); err != nil {
		t.Fatalf("confirm: %v", err)
	}
	// And again, because the daemon calls it on every start and most starts are not
	// upgrades.
	if err := ConfirmUpgrade(where.ConfigPath); err != nil {
		t.Fatalf("confirming twice: %v", err)
	}

	result, err := harness.steps.rollBackInFlight(context.Background())
	if err != nil {
		t.Fatalf("rollback: %v", err)
	}
	if result.GetRolledBack() {
		t.Fatal("a confirmed upgrade was rolled back")
	}
	if version, _ := versionInFile(where.BinaryPath); version != "v0.5.0" {
		t.Fatalf("the installed binary is %s", version)
	}
}

// A download with no checksum is a way to run anything on this node as root.
func TestAUrlWithoutAChecksumIsRefused(t *testing.T) {
	_, err := chooseUpgradeSource("https://panel.example/dist/sasayaki", "", "")
	if err == nil {
		t.Fatal("--url was accepted with no --sha256")
	}
	mustContain(t, err.Error(), "--sha256")
}

func TestUpgradeSourceCombinations(t *testing.T) {
	if _, err := chooseUpgradeSource("", "", ""); err == nil {
		t.Error("an upgrade with no source was accepted")
	}
	if _, err := chooseUpgradeSource("https://x/y", "abc", "/tmp/sasayaki"); err == nil {
		t.Error("--url and --binary together were accepted")
	}
	source, err := chooseUpgradeSource("", "", "/tmp/sasayaki")
	if err != nil {
		t.Errorf("--binary alone was refused: %v", err)
	}
	if source.localPath != "/tmp/sasayaki" {
		t.Errorf("source is %+v", source)
	}
}

// sha256sum prints "<hash>  <file>", and an operator pastes the line rather than the word.
func TestAPastedChecksumLineIsUnderstood(t *testing.T) {
	line := "  ABCDEF0123  dist/sasayaki-linux-amd64\n"
	if got := normaliseChecksum(line); got != "abcdef0123" {
		t.Fatalf("normalised to %q", got)
	}
	if got := normaliseChecksum("   "); got != "" {
		t.Fatalf("blank normalised to %q", got)
	}
}

// A download that fails leaves nothing behind, including the staged file.
func TestAFailedDownloadLeavesNothingStaged(t *testing.T) {
	harness, where := newUpgrader(t, "v0.4.0", "v0.5.0")
	harness.steps.fetch = func(context.Context, string) ([]byte, error) {
		return nil, errors.New("fetch https://panel.example/dist/sasayaki: connection reset")
	}

	if _, err := harness.steps.apply(context.Background(), harness.fromPanel()); err == nil {
		t.Fatal("a failed download reported success")
	}
	mustNotExist(t, where.BinaryPath+".incoming")
	if version, _ := versionInFile(where.BinaryPath); version != "v0.4.0" {
		t.Fatalf("the installed binary is %s", version)
	}
}

func writeMarker(t *testing.T, where layout, marker upgradeMarker) {
	t.Helper()
	if err := writeUpgradeMarker(where.ConfigPath, marker); err != nil {
		t.Fatalf("write the upgrade marker: %v", err)
	}
}
