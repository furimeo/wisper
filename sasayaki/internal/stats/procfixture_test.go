package stats

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// A machine written out as the four files this package reads.
//
// Fixtures rather than a fake procfs interface: the thing worth being sure about is the
// parsing - which column of /proc/stat is iowait, which interfaces double-count, what a
// kernel without MemAvailable looks like - and an interface would have the tests assert
// against an imitation of the file rather than against the file.

// procFixture is a machine described in the four files this package reads.
type procFixture struct {
	cpuTicks        int64
	bootedAt        int64
	cores           int
	memTotalKiB     int64
	memAvailableKiB int64
	rxBytes         int64
	txBytes         int64
	load            float64
}

// writeProcFixture lays a fake /proc out on disk and returns its root.
func writeProcFixture(t *testing.T, fixture procFixture) string {
	t.Helper()
	root := t.TempDir()
	rewriteProcFixture(t, root, fixture)
	return root
}

// rewriteProcFixture moves a fixture forward in time, which is how a counter is made to
// advance between two passes.
func rewriteProcFixture(t *testing.T, root string, fixture procFixture) {
	t.Helper()

	stat := "cpu  " + decimal(fixture.cpuTicks) + " 0 0 100000 0 0 0 0 0 0\n"
	for core := range fixture.cores {
		stat += "cpu" + decimal(int64(core)) + " 0 0 0 0 0 0 0 0 0 0\n"
	}
	stat += "btime " + decimal(fixture.bootedAt) + "\n"
	write(t, filepath.Join(root, "stat"), stat)

	write(t, filepath.Join(root, "meminfo"),
		"MemTotal:       "+decimal(fixture.memTotalKiB)+" kB\n"+
			"MemFree:        "+decimal(fixture.memAvailableKiB/2)+" kB\n"+
			"MemAvailable:   "+decimal(fixture.memAvailableKiB)+" kB\n")

	if err := os.MkdirAll(filepath.Join(root, "net"), 0o755); err != nil {
		t.Fatalf("create the fixture's net directory: %v", err)
	}
	write(t, filepath.Join(root, "net", "dev"),
		"Inter-|   Receive                                                |  Transmit\n"+
			" face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets\n"+
			"    lo: 999999 10 0 0 0 0 0 0 888888 10 0 0 0 0 0 0\n"+
			"  eth0: "+decimal(fixture.rxBytes)+" 10 0 0 0 0 0 0 "+decimal(fixture.txBytes)+" 10 0 0 0 0 0 0\n"+
			"docker0: 555555 10 0 0 0 0 0 0 444444 10 0 0 0 0 0 0\n")

	write(t, filepath.Join(root, "loadavg"),
		strconv.FormatFloat(fixture.load, 'f', 2, 64)+" 0.40 0.30 1/200 12345\n")
}

func decimal(value int64) string { return strconv.FormatInt(value, 10) }

func write(t *testing.T, path, contents string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(contents), 0o644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}
