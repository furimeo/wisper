package files

import (
	"context"
	"io/fs"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// "How big is this folder?" - the question a customer asks when their quota is full.
//
// A separate operation from a listing because it walks the whole tree, and a listing must
// not: a directory page has to come back while somebody is looking at their phone, and a
// walk of a hundred thousand files does not. It is also the one operation here with a
// budget, because the honest answer to "this tree is too big to measure" is a number
// marked approximate, not a request that never returns (files.proto, DirectorySize).

// measureDirectory answers a MeasureDirectory.
func (h *Host) measureDirectory(ctx context.Context, root *openRoot, request *wisperpb.MeasureDirectory, out *replies) error {
	// followLeaf is true: measuring enters the directory.
	target, failed := resolve(root, request.GetPath(), true)
	if failed != nil {
		return failed
	}

	info, err := root.root.Lstat(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "look at the path")
	}
	if !info.IsDir() {
		// A file has a size already, and answering with it is more useful than refusing.
		return out.size(&wisperpb.DirectorySize{
			Path:      target,
			Bytes:     info.Size(),
			FileCount: 1,
		})
	}

	walk := &measurement{budget: h.limits.MeasureEntryBudget}
	if failed := h.measure(ctx, root, target, walk, 0); failed != nil {
		return failed
	}
	return out.size(&wisperpb.DirectorySize{
		Path:           target,
		Bytes:          walk.bytes,
		FileCount:      walk.files,
		DirectoryCount: walk.directories,
		Approximate:    walk.approximate,
	})
}

// measurement is what a walk has found so far, and how much further it may go.
type measurement struct {
	bytes       int64
	files       int64
	directories int64
	visited     int64
	budget      int64
	// approximate is set when the walk stopped early: the budget ran out, or a directory
	// could not be read. Both mean the number below is a floor rather than the answer.
	approximate bool
}

func (m *measurement) spent() bool {
	return m.visited >= m.budget
}

// measure walks one directory, counting.
//
// Symlinks are counted by their own size and never followed, which is both the safe answer
// and the one `du` gives: following them would count a target twice, and following one out
// of the root is the traversal this package exists to prevent.
//
// A directory that cannot be read does not fail the whole measurement. A customer with one
// unreadable folder still wants the size of the other nine hundred, and the answer comes
// back marked approximate so the UI does not present a floor as a fact.
func (h *Host) measure(ctx context.Context, root *openRoot, target string, walk *measurement, depth int) *failure {
	if failed := interrupted(ctx, target, "measuring the directory"); failed != nil {
		return failed
	}
	if depth > maxTreeDepth {
		walk.approximate = true
		return nil
	}

	entries, failed := readEntries(ctx, root, target)
	if failed != nil {
		if depth == 0 {
			// The directory the customer named. Failing to read that one is the answer.
			return failed
		}
		walk.approximate = true
		return nil
	}

	for _, entry := range entries {
		if walk.spent() {
			walk.approximate = true
			return nil
		}
		if failed := interrupted(ctx, target, "measuring the directory"); failed != nil {
			return failed
		}
		walk.visited++

		child := childPath(target, entry.Name())
		if entry.IsDir() && entry.Type()&fs.ModeSymlink == 0 {
			walk.directories++
			if failed := h.measure(ctx, root, child, walk, depth+1); failed != nil {
				return failed
			}
			continue
		}

		info, err := entry.Info()
		if err != nil {
			// Removed while the walk was running. Not a failure: it is not there to count.
			continue
		}
		walk.files++
		walk.bytes += info.Size()
	}
	return nil
}
