package bootstrap

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// installedNode is a machine with everything on it: a unit, a binary, a credential and a
// customer's files. Every test below is about which of those survive.
func installedNode(t *testing.T) (layout, *fakeInit) {
	t.Helper()
	where, _ := testLayout(t)

	fakeBinary(t, where.BinaryPath, "v0.4.0")
	fakeBinary(t, where.previousBinaryPath(), "v0.3.0")
	writeFile(t, where.unitPath(), renderUnit(where.unitParameters()))
	writeFile(t, filepath.Join(where.UnitDir, rollbackUnitName),
		renderRollbackUnit(where.unitParameters()))

	credential := rpc.Credential{
		NodeID:                 "nd_01",
		NodeName:               "berlin-1",
		Token:                  "cred_secret",
		Panel:                  "https://panel.example:443",
		PanelCertificateSHA256: strings.Repeat("ab", 32),
		EnrolledAt:             time.Now().UTC().Truncate(time.Second),
	}
	body, err := json.MarshalIndent(credential, "", "  ")
	if err != nil {
		t.Fatalf("encode the credential: %v", err)
	}
	writeFile(t, where.ConfigPath, string(body))
	writeFile(t, where.keyPath(), "-----BEGIN PRIVATE KEY-----\n")

	// What a customer owns.
	writeFile(t, filepath.Join(where.StateDir, "volumes", "svc_7", "index.html"), "<h1>hi</h1>")
	writeFile(t, filepath.Join(where.StateDir, "state.db"), "sqlite")

	init := workingInit()
	return where, init
}

// Design section 7.7, and one of the quality gates in section 13.4: uninstall does not
// touch the state directory. Ever, by default, under any circumstances.
func TestUninstallLeavesCustomerDataExactlyWhereItIs(t *testing.T) {
	where, init := installedNode(t)
	out := &strings.Builder{}

	err := removal{layout: where, systemd: init.seam(), stdin: strings.NewReader(""), out: out}.
		run(context.Background())
	if err != nil {
		t.Fatalf("uninstall: %v", err)
	}

	// Gone.
	mustNotExist(t, where.BinaryPath)
	mustNotExist(t, where.previousBinaryPath())
	mustNotExist(t, where.unitPath())
	mustNotExist(t, filepath.Join(where.UnitDir, rollbackUnitName))

	// Untouched.
	if page := readFile(t, filepath.Join(where.StateDir, "volumes", "svc_7", "index.html")); page != "<h1>hi</h1>" {
		t.Fatalf("a customer's file changed: %q", page)
	}
	mustExist(t, filepath.Join(where.StateDir, "state.db"))
	mustExist(t, where.ConfigPath)
	mustExist(t, where.keyPath())

	if !init.ran("stop") || !init.ran("disable") {
		t.Fatalf("the service was not stopped and disabled: %v", init.commands)
	}
	if !init.ran("daemon-reload") {
		t.Fatal("systemd was not told the unit files are gone")
	}
	mustContain(t, out.String(), where.StateDir)
	mustContain(t, out.String(), "--purge")
}

// The typed confirmation is the whole safety mechanism. Getting it wrong deletes nothing -
// and leaves the service installed, because an operator who mistyped is not an operator
// who wanted a half-removed node.
func TestPurgeWithoutTheNodeNameDeletesNothing(t *testing.T) {
	where, init := installedNode(t)
	out := &strings.Builder{}

	err := removal{
		layout: where, purge: true, systemd: init.seam(),
		stdin: strings.NewReader("yes\n"), out: out,
	}.run(context.Background())

	if err == nil {
		t.Fatal("--purge went ahead on \"yes\"")
	}
	mustContain(t, err.Error(), "berlin-1")
	mustExist(t, filepath.Join(where.StateDir, "volumes", "svc_7", "index.html"))
	mustExist(t, where.BinaryPath)
	mustExist(t, where.unitPath())
	if len(init.commands) != 0 {
		t.Fatalf("the service was touched before the confirmation was read: %v", init.commands)
	}
}

func TestPurgeWithTheNodeNameDeletesEverything(t *testing.T) {
	where, init := installedNode(t)
	out := &strings.Builder{}

	err := removal{
		layout: where, purge: true, systemd: init.seam(),
		stdin: strings.NewReader("berlin-1\n"), out: out,
	}.run(context.Background())
	if err != nil {
		t.Fatalf("uninstall --purge: %v", err)
	}

	mustNotExist(t, where.StateDir)
	mustNotExist(t, where.configDir())
	mustNotExist(t, where.BinaryPath)
	mustContain(t, out.String(), "Deleted "+where.StateDir)
}

// A node that never enrolled has no name to type, so the thing being destroyed is what has
// to be typed instead.
func TestPurgeOnAnUnenrolledNodeAsksForThePath(t *testing.T) {
	where, init := installedNode(t)
	mustRemove(t, where.ConfigPath)
	out := &strings.Builder{}

	err := removal{
		layout: where, purge: true, systemd: init.seam(),
		stdin: strings.NewReader(where.StateDir + "\n"), out: out,
	}.run(context.Background())
	if err != nil {
		t.Fatalf("uninstall --purge: %v", err)
	}

	mustNotExist(t, where.StateDir)
	mustContain(t, out.String(), "the directory to be deleted")
}

// Uninstalling a node that was never fully installed is a normal thing to do after a
// failed install, and none of the missing files is an error.
func TestUninstallingAHalfInstalledNodeIsNotAnError(t *testing.T) {
	where, _ := testLayout(t)
	init := workingInit()
	init.unitKnown = false

	err := removal{layout: where, systemd: init.seam(), stdin: strings.NewReader(""),
		out: &strings.Builder{}}.run(context.Background())

	if err != nil {
		t.Fatalf("uninstalling a node with nothing on it failed: %v", err)
	}
}

// A layout whose state directory came out as "/" or "/var" would take an operating system
// with it. The flag that gets there is one keystroke from the one that does not.
func TestPurgeRefusesAPathTooCloseToTheRoot(t *testing.T) {
	for _, path := range []string{"/", "/var", "/etc", "C:/"} {
		if err := refuseShallowPath(path); err == nil {
			t.Errorf("%q was accepted as a state directory to delete", path)
		}
	}
	for _, path := range []string{"/var/lib/wisper", "/srv/wisper", "/etc/wisper"} {
		if err := refuseShallowPath(path); err != nil {
			t.Errorf("%q was refused: %v", path, err)
		}
	}
}

// Without systemd there is nothing to stop, and asking systemctl would print an error
// about a bus that is not running over an uninstall that is otherwise fine.
func TestUninstallWithoutSystemdTouchesNoUnits(t *testing.T) {
	where, init := installedNode(t)
	init.booted = false

	err := removal{layout: where, systemd: init.seam(), stdin: strings.NewReader(""),
		out: &strings.Builder{}}.run(context.Background())
	if err != nil {
		t.Fatalf("uninstall: %v", err)
	}

	for _, command := range init.commands {
		if !strings.HasPrefix(command, "reset-failed") {
			t.Fatalf("systemctl %s was run on a machine without systemd", command)
		}
	}
	mustNotExist(t, where.unitPath())
}
