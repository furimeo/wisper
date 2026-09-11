package stats

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The one measurement here that touches the disk, and the one place in this package where a
// value from the network would become a filesystem path.

func TestVolumeBytesAreMeasuredAndCached(t *testing.T) {
	stateDir := t.TempDir()
	tree := filepath.Join(stateDir, "volumes", "workload-1", "data")
	if err := os.MkdirAll(filepath.Join(tree, "nested"), 0o755); err != nil {
		t.Fatalf("build the volume tree: %v", err)
	}
	write(t, filepath.Join(tree, "a.bin"), strings.Repeat("x", 1000))
	write(t, filepath.Join(tree, "nested", "b.bin"), strings.Repeat("x", 500))

	measured := newVolumes(stateDir, time.Minute, 1000)
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	if got := measured.bytesFor(t.Context(), "workload-1", at); got != 1500 {
		t.Fatalf("measured %d bytes, want 1500 across both files", got)
	}

	// Written to a moment later, and inside the refresh interval: the cached figure stands.
	write(t, filepath.Join(tree, "c.bin"), strings.Repeat("x", 9000))
	if got := measured.bytesFor(t.Context(), "workload-1", at.Add(10*time.Second)); got != 1500 {
		t.Fatalf("measured %d bytes inside the refresh interval, want the cached 1500: walking a "+
			"customer's tree every twelve seconds makes the sampler the busiest thing on the node", got)
	}

	if got := measured.bytesFor(t.Context(), "workload-1", at.Add(2*time.Minute)); got != 10500 {
		t.Fatalf("measured %d bytes after the refresh interval, want 10500", got)
	}
}

func TestAWorkloadWithNoVolumesMeasuresZero(t *testing.T) {
	measured := newVolumes(t.TempDir(), time.Minute, 1000)
	if got := measured.bytesFor(t.Context(), "never-written-to", time.Now()); got != 0 {
		t.Fatalf("measured %d bytes for a workload with no volume directory, want 0", got)
	}
}

// A workload id arrives from a container label, which arrives from a spec, which arrives from
// the network. It must not become a path that climbs out of the volume tree.
func TestAWorkloadIdThatClimbsOutOfTheTreeIsRefused(t *testing.T) {
	stateDir := t.TempDir()
	outside := filepath.Join(stateDir, "secrets")
	if err := os.MkdirAll(outside, 0o755); err != nil {
		t.Fatalf("build the directory outside the tree: %v", err)
	}
	write(t, filepath.Join(outside, "node.json"), strings.Repeat("x", 4096))

	measured := newVolumes(stateDir, time.Minute, 1000)
	for _, id := range []string{"../secrets", `..\secrets`, "a/b", "", ".", "..", "C:/Windows"} {
		if _, ok := measured.directoryFor(id); ok {
			t.Fatalf("the workload id %q resolved to a directory; it names somewhere other than "+
				"this workload's own volumes", id)
		}
		if got := measured.bytesFor(t.Context(), id, time.Now()); got != 0 {
			t.Fatalf("the workload id %q measured %d bytes rather than being refused", id, got)
		}
	}
}

// A tree big enough to cost real time stops at the budget and reports a floor. Understating a
// quota bar is survivable; a sampling pass that never finishes is not.
func TestAWalkStopsAtItsBudget(t *testing.T) {
	stateDir := t.TempDir()
	tree := filepath.Join(stateDir, "volumes", "workload-1")
	if err := os.MkdirAll(tree, 0o755); err != nil {
		t.Fatalf("build the volume tree: %v", err)
	}
	for index := range 40 {
		write(t, filepath.Join(tree, "file-"+decimal(int64(index))), strings.Repeat("x", 100))
	}

	full := newVolumes(stateDir, time.Minute, 1000).bytesFor(t.Context(), "workload-1", time.Now())
	if full != 4000 {
		t.Fatalf("an unbudgeted walk measured %d bytes, want 4000", full)
	}

	capped := newVolumes(stateDir, time.Minute, 10).bytesFor(t.Context(), "workload-1", time.Now())
	if capped == 0 || capped >= full {
		t.Fatalf("a walk capped at 10 entries measured %d bytes, want something between 0 and the "+
			"full %d: the budget has to stop the walk and keep what it counted", capped, full)
	}
}

func TestForgettingAWorkloadDropsItsMeasurement(t *testing.T) {
	stateDir := t.TempDir()
	tree := filepath.Join(stateDir, "volumes", "gone")
	if err := os.MkdirAll(tree, 0o755); err != nil {
		t.Fatalf("build the volume tree: %v", err)
	}
	write(t, filepath.Join(tree, "a.bin"), "xxxx")

	measured := newVolumes(stateDir, time.Minute, 1000)
	measured.bytesFor(t.Context(), "gone", time.Now())
	measured.bytesFor(t.Context(), "staying", time.Now())

	measured.forget(map[string]struct{}{"staying": {}})
	if _, kept := measured.measured["gone"]; kept {
		t.Fatal("a workload that has left the node is still holding a measurement")
	}
	if _, kept := measured.measured["staying"]; !kept {
		t.Fatal("a workload that is still here lost its measurement")
	}
}
