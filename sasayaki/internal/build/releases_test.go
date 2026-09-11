package build

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The symlink swap is the whole of "deploy" and "rollback" for a static site, so it gets
// the most attention in this package: that it is atomic, that rolling back restores the
// previous tree byte for byte, and that a site with nothing published is a normal state
// rather than an error.

func TestPublishedIsEmptyBeforeTheFirstDeployment(t *testing.T) {
	h := newHarness(t)

	published, err := h.Releases.Published(context.Background(), "42")
	if err != nil {
		t.Fatalf("a site that has never been deployed must not be an error: %v", err)
	}
	if published != "" {
		t.Fatalf("expected no published release, got %q", published)
	}
}

func TestPublishPointsCurrentAtTheRelease(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)

	h.seedRelease(t, "42", "7", map[string]string{"index.html": "seven"})
	if err := h.Releases.Publish(context.Background(), "42", "7"); err != nil {
		t.Fatalf("publish: %v", err)
	}

	published, err := h.Releases.Published(context.Background(), "42")
	if err != nil {
		t.Fatalf("read what is published: %v", err)
	}
	if published != "7" {
		t.Fatalf("expected release 7 to be live, got %q", published)
	}

	link, _ := currentLink(h.StateDir, "42")
	served := readTree(t, link)
	if served["index.html"] != "seven" {
		t.Fatalf("the symlink does not resolve to the release: %v", served)
	}

	// Relative, so the whole state tree can be moved and still resolve.
	target, err := os.Readlink(link)
	if err != nil {
		t.Fatalf("read the link: %v", err)
	}
	if filepath.IsAbs(target) {
		t.Fatalf("the current link is absolute (%q); a moved state directory would break it", target)
	}
}

func TestRollbackRestoresTheExactPreviousTree(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	before := map[string]string{
		"index.html":     "<h1>version one</h1>",
		"assets/app.css": "body{color:red}",
		"nested/deep/x":  "one",
	}
	after := map[string]string{
		"index.html":     "<h1>version two</h1>",
		"assets/app.css": "body{color:blue}",
	}
	h.seedRelease(t, "42", "7", before)
	h.seedRelease(t, "42", "8", after)

	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("publish 7: %v", err)
	}
	link, _ := currentLink(h.StateDir, "42")
	if got := readTree(t, link); !reflect.DeepEqual(got, before) {
		t.Fatalf("release 7 is not what was served: %v", got)
	}

	if err := h.Releases.Publish(ctx, "42", "8"); err != nil {
		t.Fatalf("publish 8: %v", err)
	}
	if got := readTree(t, link); !reflect.DeepEqual(got, after) {
		t.Fatalf("release 8 is not what was served: %v", got)
	}

	// The rollback: the panel names the older release in the next generation, and this is
	// the same move it made forwards.
	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("roll back to 7: %v", err)
	}
	if got := readTree(t, link); !reflect.DeepEqual(got, before) {
		t.Fatalf("rolling back did not restore the previous tree exactly: %v", got)
	}
	if published, _ := h.Releases.Published(ctx, "42"); published != "7" {
		t.Fatalf("expected release 7 to be live again, got %q", published)
	}
}

// TestSwapIsAtomic hammers the link with readers while it is being replaced. Every read
// must resolve to one release or the other; a reader that ever sees a missing path is the
// window the two-step unlink-then-symlink version has and this one does not.
func TestSwapIsAtomic(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	h.seedRelease(t, "42", "7", map[string]string{"index.html": "seven"})
	h.seedRelease(t, "42", "8", map[string]string{"index.html": "eight"})
	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("publish 7: %v", err)
	}

	link, _ := currentLink(h.StateDir, "42")
	var (
		stop     atomic.Bool
		misses   atomic.Int64
		readers  sync.WaitGroup
		expected = map[string]bool{"seven": true, "eight": true}
	)
	for range 4 {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for !stop.Load() {
				contents, err := os.ReadFile(filepath.Join(link, "index.html"))
				if err != nil || !expected[string(contents)] {
					misses.Add(1)
					return
				}
			}
		}()
	}

	for index := range 40 {
		release := "8"
		if index%2 == 1 {
			release = "7"
		}
		if err := h.Releases.Publish(ctx, "42", release); err != nil {
			stop.Store(true)
			readers.Wait()
			t.Fatalf("swap %d: %v", index, err)
		}
		time.Sleep(time.Millisecond)
	}
	stop.Store(true)
	readers.Wait()

	if misses.Load() != 0 {
		t.Fatalf("a reader saw the site missing during %d swap(s); the swap is not atomic",
			misses.Load())
	}
}

func TestPublishRefusesAReleaseThatIsNotThere(t *testing.T) {
	h := newHarness(t)

	if err := h.Releases.Publish(context.Background(), "42", "9"); err == nil {
		t.Fatal("publishing a release that does not exist must fail rather than leave a " +
			"dangling link the edge serves as a 404")
	}
}

func TestPublishRefusesAnIdentifierThatWouldEscape(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	for _, attempt := range []struct{ workload, release string }{
		{"..", "7"},
		{"42", ".."},
		{"42", "../../etc"},
		{"42/../43", "7"},
		{"42", ".hidden"},
	} {
		if err := h.Releases.Publish(ctx, attempt.workload, attempt.release); err == nil {
			t.Fatalf("publishing workload %q release %q was accepted", attempt.workload, attempt.release)
		}
	}
}

func TestPublishTwiceIsANoOp(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	h.seedRelease(t, "42", "7", map[string]string{"index.html": "seven"})
	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("publish: %v", err)
	}
	link, _ := currentLink(h.StateDir, "42")
	before, err := os.Lstat(link)
	if err != nil {
		t.Fatalf("look at the link: %v", err)
	}

	// The reconcile loop calls this every fifteen seconds for a site whose spec has not
	// changed; replacing the link each time would be churn for nothing.
	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("publish again: %v", err)
	}
	after, err := os.Lstat(link)
	if err != nil {
		t.Fatalf("look at the link: %v", err)
	}
	if !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("publishing the release that is already live replaced the link")
	}
}

func TestDiscardRemovesTheWholeSiteTree(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	h.seedRelease(t, "42", "7", map[string]string{"index.html": "seven"})
	if err := h.Releases.Publish(ctx, "42", "7"); err != nil {
		t.Fatalf("publish: %v", err)
	}
	if err := h.Releases.Discard(ctx, "42"); err != nil {
		t.Fatalf("discard: %v", err)
	}

	site, _ := siteRoot(h.StateDir, "42")
	if _, err := os.Lstat(site); !os.IsNotExist(err) {
		t.Fatalf("the site tree is still there: %v", err)
	}
	// Idempotent: the reconcile loop may see the same orphan on two passes.
	if err := h.Releases.Discard(ctx, "42"); err != nil {
		t.Fatalf("discarding a site that is already gone must be a no-op: %v", err)
	}
}

func TestReleasesListsFinishedOnesOnly(t *testing.T) {
	h := newHarness(t)

	h.seedRelease(t, "42", "7", map[string]string{"index.html": "seven"})
	h.seedRelease(t, "42", "8", map[string]string{"index.html": "eight"})
	root, _ := releaseRoot(h.StateDir, "42")
	if err := os.MkdirAll(filepath.Join(root, stagingPrefix+"9"), 0o755); err != nil {
		t.Fatalf("create a staging directory: %v", err)
	}

	listed, err := h.Releases.Releases("42")
	if err != nil {
		t.Fatalf("list releases: %v", err)
	}
	if !reflect.DeepEqual(listed, []string{"7", "8"}) {
		t.Fatalf("expected the two finished releases, got %v", listed)
	}
}
