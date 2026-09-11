package stats

import (
	"context"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// How much of their disk each customer is using.
//
// Sampled rather than computed on demand, because a quota bar is on every page and walking
// a customer's tree is a readdir per directory (stats.proto, WorkloadSample.disk_used_bytes).
// Sampled far more slowly than everything else here, because it is the one measurement in
// this package that touches the disk rather than reading a counter: a tree with a hundred
// thousand files costs real time, and doing it every twelve seconds would make the sampler
// the busiest thing on the node.
//
// The figure is a floor, deliberately. A walk that hits its budget stops and reports what it
// has, so a customer with a pathological tree gets an understated bar rather than a sampler
// that never finishes a pass. The enforcement is the XFS project quota on the directory
// (internal/runtime, quota.go); this is the number, not the limit.

// volumes measures the volume tree of each workload, and remembers the answer.
type volumes struct {
	// root is <state-dir>/volumes, the layout internal/runtime owns and this package only
	// reads.
	root string
	// every is how often one workload's tree is walked again.
	every time.Duration
	// budget caps the entries one walk will visit.
	budget int

	measured map[string]measurement
}

// measurement is one walk's result and when it was taken.
type measurement struct {
	bytes int64
	at    time.Time
}

func newVolumes(stateDir string, every time.Duration, budget int) *volumes {
	return &volumes{
		root:     filepath.Join(stateDir, "volumes"),
		every:    every,
		budget:   budget,
		measured: make(map[string]measurement),
	}
}

// bytesFor is how much one workload's volumes hold.
//
// Cached between walks, so the figure on a sample taken thirty seconds after a walk is the
// figure from the walk. That is honest for what it describes: a quota bar is a slow-moving
// number, and one that is four minutes old is worth incomparably more than one that costs a
// tree traversal every twelve seconds to be current.
func (v *volumes) bytesFor(ctx context.Context, workloadID string, now time.Time) int64 {
	last, known := v.measured[workloadID]
	if known && now.Sub(last.at) < v.every {
		return last.bytes
	}

	directory, ok := v.directoryFor(workloadID)
	if !ok {
		// An id that could climb out of the tree. It never reaches a filesystem call, and
		// the workload is reported with no disk figure rather than with somebody else's.
		return 0
	}

	measured := walkBytes(ctx, directory, v.budget)
	v.measured[workloadID] = measurement{bytes: measured, at: now}
	return measured
}

// forget drops the workloads that are no longer on this node, so the map does not outlive
// what it describes.
func (v *volumes) forget(live map[string]struct{}) {
	for workloadID := range v.measured {
		if _, alive := live[workloadID]; !alive {
			delete(v.measured, workloadID)
		}
	}
}

// directoryFor resolves a workload's volume tree, refusing an id that is not one.
//
// The check is not defensive habit. A workload id arrives from a container label, which
// arrives from a spec, which arrives from the network, and this is the point at which it
// would become a filesystem path. An id containing a separator or a parent reference would
// walk - and report the size of - a directory belonging to somebody else, or to the host
// (AGENTS.md section 5).
func (v *volumes) directoryFor(workloadID string) (string, bool) {
	if workloadID == "" || workloadID == "." || workloadID == ".." {
		return "", false
	}
	if strings.ContainsAny(workloadID, `/\:`) || strings.ContainsRune(workloadID, 0) {
		return "", false
	}
	return filepath.Join(v.root, workloadID), true
}

// walkBytes totals the regular files under directory, up to budget entries.
//
// Apparent size, not blocks: os.Stat's Size is what a customer sees when they list their
// files, and a sparse file reported at its block count would have them arguing with a quota
// bar that disagrees with `ls`. Symlinks are counted as links and never followed, so a link
// a customer made to their own data directory does not have it counted twice or send the
// walk in a circle.
//
// A missing directory is zero, not an error: a workload whose volumes have never been
// written to has none, and so does a site, which has no volumes at all.
func walkBytes(ctx context.Context, directory string, budget int) int64 {
	var total int64
	visited := 0

	_ = filepath.WalkDir(directory, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			// An unreadable subdirectory is skipped rather than abandoning the whole walk:
			// most of the answer is worth more than none of it.
			if entry != nil && entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}

		visited++
		if visited > budget {
			// Out of budget. What has been counted so far is a floor, which is the safe
			// direction for a figure a quota bar is drawn from.
			return filepath.SkipAll
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		if info, statErr := entry.Info(); statErr == nil {
			total += info.Size()
		}
		return nil
	})

	return total
}
