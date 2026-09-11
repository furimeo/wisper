package bootstrap

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The fakes every test in this package builds a machine out of.
//
// There is one real node and it cannot be broken on demand, which is the whole problem:
// every property worth proving here is a statement about what happens when something is
// wrong - the preflight fails, the token is refused, the new binary does not come back.
// These are what let a test say "this machine has no runsc and a full disk" in one line.

// --- a machine ---------------------------------------------------------------

// healthyMachine is a node that passes everything. A test that cares about one check
// breaks that one and leaves the rest alone, so the assertion reads as the difference
// rather than as a wall of setup.
func healthyMachine(t *testing.T) *machine {
	t.Helper()
	root := t.TempDir()

	write := func(relative, contents string) {
		t.Helper()
		path := filepath.Join(root, filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatalf("create %s: %v", filepath.Dir(path), err)
		}
		if err := os.WriteFile(path, []byte(contents), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}

	write("proc/sys/kernel/osrelease", "6.8.0-45-generic\n")
	write("proc/meminfo", "MemTotal:       16318664 kB\nMemFree:  102400 kB\n")
	write("proc/self/mountinfo",
		"25 30 8:1 / / rw,relatime shared:1 - xfs /dev/sda1 rw,prjquota\n")
	write("sys/fs/cgroup/cgroup.controllers", "cpuset cpu io memory hugetlb pids rdma\n")
	write("sys/class/dmi/id/product_uuid", "4c4c4544-0043-3010-8046-b7c04f4d3232\n")
	write("sys/class/dmi/id/board_serial", "PF2K9ZQ1\n")
	write("etc/os-release", "ID=ubuntu\nVERSION_ID=\"24.04\"\nPRETTY_NAME=\"Ubuntu 24.04 LTS\"\n")
	write("etc/machine-id", "2f4a6c1e9b7d4f0a8c3e5d7b1a9f2c4e\n")
	write("run/systemd/timesync/synchronized", "")

	return &machine{
		procRoot:          filepath.Join(root, "proc"),
		sysRoot:           filepath.Join(root, "sys"),
		etcRoot:           filepath.Join(root, "etc"),
		runRoot:           filepath.Join(root, "run"),
		dbusMachineIDPath: filepath.Join(root, "var", "lib", "dbus", "machine-id"),
		stateDir:          filepath.Join(root, "var", "lib", "wisper"),
		uid:               0,
		now:               func() time.Time { return time.Date(2026, 9, 11, 8, 0, 0, 0, time.UTC) },
		lookPath: func(name string) (string, error) {
			if name == "runsc" {
				return "/usr/local/bin/runsc", nil
			}
			return "", errors.New("not found")
		},
		runCommand: func(_ context.Context, name string, _ ...string) ([]byte, error) {
			switch name {
			case "/usr/local/bin/runsc":
				return []byte("runsc version release-20260401.0\n"), nil
			case "timedatectl":
				return []byte("yes\n"), nil
			default:
				return nil, fmt.Errorf("%s: no such fake command", name)
			}
		},
		dial: func(context.Context, string, string) (io.Closer, error) {
			return io.NopCloser(strings.NewReader("")), nil
		},
		listen: func(string, string) (io.Closer, error) {
			return io.NopCloser(strings.NewReader("")), nil
		},
		diskSpace: func(string) (int64, int64, error) {
			return 500 << 30, 400 << 30, nil
		},
		probePanel: func(_ context.Context, endpoint string) panelProbe {
			return panelProbe{Endpoint: endpoint, Reachable: true, Stage: "tls"}
		},
		probeDocker: func(context.Context, string) (dockerReport, error) {
			return dockerReport{
				Host:          "unix:///var/run/docker.sock",
				Version:       "27.3.1",
				APIVersion:    "1.47",
				CgroupVersion: "2",
				Runtimes:      []string{"runc", "runsc"},
				StorageDriver: "overlay2",
			}, nil
		},
	}
}

// checkNamed finds one row of a report, so an assertion names the check it is about.
func checkNamed(t *testing.T, report *wisperpb.DoctorReport, id string) *wisperpb.DoctorCheck {
	t.Helper()
	for _, item := range report.GetChecks() {
		if item.GetId() == id {
			return item
		}
	}
	t.Fatalf("no check called %q in the report", id)
	return nil
}

// --- an init system ----------------------------------------------------------

// fakeInit records what was asked of systemd and answers with a script.
type fakeInit struct {
	commands []string

	// states is what is-active returns, one entry per call, the last one repeating. A
	// crash loop is []string{"activating", "failed"}.
	states []string

	booted    bool
	unitKnown bool

	// failures maps a systemctl verb to the error it produces.
	failures map[string]string
}

func workingInit() *fakeInit {
	return &fakeInit{booted: true, unitKnown: true, states: []string{unitActive}}
}

func (f *fakeInit) seam() systemd {
	return systemd{
		booted: func() bool { return f.booted },
		run: func(_ context.Context, args ...string) (string, error) {
			f.commands = append(f.commands, strings.Join(args, " "))
			if len(args) == 0 {
				return "", errors.New("systemctl with no arguments")
			}
			if message, bad := f.failures[args[0]]; bad {
				return message, errors.New(message)
			}
			switch args[0] {
			case "is-active":
				return f.nextState(), nil
			case "cat":
				if !f.unitKnown {
					return "", errors.New("no such unit")
				}
				return "[Unit]\nDescription=sasayaki\n", nil
			default:
				return "", nil
			}
		},
	}
}

func (f *fakeInit) nextState() string {
	if len(f.states) == 0 {
		return unitInactive
	}
	next := f.states[0]
	if len(f.states) > 1 {
		f.states = f.states[1:]
	}
	return next
}

func (f *fakeInit) ran(verb string) bool {
	for _, command := range f.commands {
		if strings.HasPrefix(command, verb) {
			return true
		}
	}
	return false
}

// --- a clock -----------------------------------------------------------------

// virtualClock makes a settle window instant. Without it every test that watches a
// restart would wait out twelve real seconds, and a test suite nobody runs is worse than
// no test suite.
type virtualClock struct {
	at time.Time
}

func newClock() *virtualClock {
	return &virtualClock{at: time.Date(2026, 9, 11, 8, 0, 0, 0, time.UTC)}
}

func (c *virtualClock) now() time.Time { return c.at }

func (c *virtualClock) sleep(_ context.Context, d time.Duration) error {
	c.at = c.at.Add(d)
	return nil
}

func (c *virtualClock) watch(sd systemd) restartWatch {
	return restartWatch{
		systemd: sd,
		unit:    unitName,
		timeout: restartTimeout,
		settle:  restartSettle,
		now:     c.now,
		sleep:   c.sleep,
	}
}

// --- a filesystem ------------------------------------------------------------

// testLayout is an entire installation inside t.TempDir(), which is what makes "nothing
// was written" and "the state directory was not touched" things a test can assert rather
// than things a comment claims.
func testLayout(t *testing.T) (layout, string) {
	t.Helper()
	root := t.TempDir()
	return layout{
		ConfigPath: filepath.Join(root, "etc", "wisper", "node.json"),
		StateDir:   filepath.Join(root, "var", "lib", "wisper"),
		BinaryPath: filepath.Join(root, "usr", "local", "bin", "sasayaki"),
		UnitDir:    filepath.Join(root, "etc", "systemd", "system"),
	}, root
}

func writeFile(t *testing.T, path, contents string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("create %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(contents), 0o644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func mustRemove(t *testing.T, path string) {
	t.Helper()
	if err := os.Remove(filepath.FromSlash(path)); err != nil {
		t.Fatalf("remove %s: %v", path, err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(raw)
}

// everythingUnder lists every path below a directory, for the assertion that a failed
// preflight left the machine exactly as it found it.
func everythingUnder(t *testing.T, root string) []string {
	t.Helper()
	var found []string
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if path == root {
			return nil
		}
		relative, relErr := filepath.Rel(root, path)
		if relErr != nil {
			return relErr
		}
		found = append(found, filepath.ToSlash(relative))
		return nil
	})
	if err != nil {
		t.Fatalf("walk %s: %v", root, err)
	}
	return found
}

func mustNotExist(t *testing.T, path string) {
	t.Helper()
	if exists(path) {
		t.Fatalf("%s exists and should not", path)
	}
}

func mustExist(t *testing.T, path string) {
	t.Helper()
	if !exists(path) {
		t.Fatalf("%s does not exist and should", path)
	}
}

// mustBePrivate checks a file is 0600. Skipped on Windows, where the mode a test would
// read back is an approximation the operating system invents.
func mustBePrivate(t *testing.T, path string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		return
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	if permissions := info.Mode().Perm(); permissions != 0o600 {
		t.Fatalf("%s is %o, want 600", path, permissions)
	}
}

func mustContain(t *testing.T, haystack, needle string) {
	t.Helper()
	if !strings.Contains(haystack, needle) {
		t.Fatalf("expected to find %q in:\n%s", needle, haystack)
	}
}
