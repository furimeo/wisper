package runtime

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

func TestImageRefPinsToTheDigestThePanelResolved(t *testing.T) {
	workload := appWorkload()
	workload.Image = "nginx:1.27"
	workload.ImageDigest = "sha256:" + strings.Repeat("a", 64)

	ref, err := imageRef(workload)
	if err != nil {
		t.Fatalf("imageRef: %v", err)
	}
	if ref != "nginx@sha256:"+strings.Repeat("a", 64) {
		t.Errorf("ref = %q, want the tag replaced by the digest so two nodes told to run the "+
			"same tag run the same bytes", ref)
	}
}

func TestImageRefDefaultsTheTagWhenNothingIsPinned(t *testing.T) {
	workload := appWorkload()
	workload.Image = "nginx"
	workload.ImageDigest = ""

	ref, err := imageRef(workload)
	if err != nil {
		t.Fatalf("imageRef: %v", err)
	}
	if ref != "nginx:latest" {
		t.Errorf("ref = %q, want the tag made explicit", ref)
	}
}

func TestImageRefRefusesNonsense(t *testing.T) {
	empty := appWorkload()
	empty.Image = "  "
	if _, err := imageRef(empty); err == nil {
		t.Error("an app with no image was accepted")
	}

	broken := appWorkload()
	broken.Image = "NOT A REFERENCE"
	if _, err := imageRef(broken); err == nil {
		t.Error("an unparseable reference was accepted")
	}

	badDigest := appWorkload()
	badDigest.ImageDigest = "not-a-digest"
	if _, err := imageRef(badDigest); err == nil {
		t.Error("a digest that is not one was accepted")
	}
}

func TestDigestOfPrefersThisRepositorysOwnDigest(t *testing.T) {
	digests := []string{
		"registry.internal/mirror/nginx@sha256:mirror",
		"docker.io/library/nginx@sha256:real",
	}
	if got := digestOf("nginx:1.27", digests, "sha256:imageid"); got != "sha256:real" {
		t.Errorf("digest = %q, want the one from this reference's own repository: any other "+
			"would compare unequal against the spec forever", got)
	}
	if got := digestOf("nginx:1.27", nil, "sha256:imageid"); got != "sha256:imageid" {
		t.Errorf("digest = %q, want the image id for something never pulled from a registry", got)
	}
}

func TestEnsureImageReportsAnImageThatIsAlreadyHere(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	state, err := docker.EnsureImage(context.Background(), appWorkload())
	if err != nil {
		t.Fatalf("EnsureImage: %v", err)
	}
	if !state.Ready || state.Digest != "sha256:bbbb" {
		t.Errorf("state = %+v, want ready with the resolved digest", state)
	}
}

func TestEnsureImageRefusesASite(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())
	workload := appWorkload()
	workload.Kind = spec.KindSite

	if _, err := docker.EnsureImage(context.Background(), workload); err == nil {
		t.Fatal("a static site was asked to have an image")
	}
}

// A cold pull takes minutes and a reconcile pass has to finish in seconds, or the systemd
// watchdog restarts the daemon in the middle of it. So the pull runs on its own and this
// answers immediately with how far it has got.
func TestEnsureImageStartsAPullWithoutWaitingForIt(t *testing.T) {
	api := newFake()
	release := make(chan struct{})
	started := make(chan string, 4)
	api.onPull = func(ref string) (client.ImagePullResponse, error) {
		started <- ref
		return &fakePull{hold: release, messages: []jsonstream.Message{{Status: "Downloading"}}}, nil
	}
	docker := newDocker(t, api, newHost())

	state, err := docker.EnsureImage(context.Background(), appWorkload())
	if err != nil {
		t.Fatalf("EnsureImage: %v", err)
	}
	if state.Ready {
		t.Fatal("reported ready for an image that is not on the node")
	}
	if state.Progress == "" {
		t.Error("no progress to show a customer waiting on a deployment")
	}

	select {
	case ref := <-started:
		if ref != "nginx:1.27" {
			t.Errorf("pulled %q, want the workload's image", ref)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no pull was started")
	}

	// The next pass, while the same pull is still running, must not start a second one:
	// two concurrent pulls of one image is a node fighting itself for bandwidth.
	if _, err := docker.EnsureImage(context.Background(), appWorkload()); err != nil {
		t.Fatalf("EnsureImage on the second pass: %v", err)
	}
	select {
	case ref := <-started:
		t.Errorf("a second pull of %q was started while the first was still running", ref)
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
}

func TestAFailedPullIsReportedAndNotRetriedImmediately(t *testing.T) {
	api := newFake()
	var pulls int
	var mu sync.Mutex
	api.onPull = func(string) (client.ImagePullResponse, error) {
		mu.Lock()
		pulls++
		mu.Unlock()
		return &fakePull{messages: []jsonstream.Message{
			{Error: &jsonstream.Error{Code: 404, Message: "manifest unknown"}},
		}}, nil
	}

	now := time.Date(2026, 4, 1, 12, 0, 0, 0, time.UTC)
	docker, err := New(context.Background(), t.TempDir(), withEngine(api),
		WithCommandRunner(newHost().run), WithLogger(slog.New(slog.DiscardHandler)),
		WithClock(func() time.Time { return now }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer docker.Close()

	if _, err := docker.EnsureImage(context.Background(), appWorkload()); err != nil {
		t.Fatalf("EnsureImage: %v", err)
	}

	failure := waitForPullFailure(t, docker)
	if !strings.Contains(failure.Error(), "manifest unknown") {
		t.Errorf("error = %v, want the registry's own words: 'manifest unknown' and "+
			"'toomanyrequests' need completely different actions", failure)
	}

	// Same failure again, and no second request: four attempts a minute against a
	// registry that is refusing turns a temporary refusal into a permanent one.
	if _, err := docker.EnsureImage(context.Background(), appWorkload()); err == nil {
		t.Error("a pull that just failed was reported as fine")
	}
	mu.Lock()
	if pulls != 1 {
		t.Errorf("made %d pull requests inside the cool-down, want 1", pulls)
	}
	mu.Unlock()

	// Past the cool-down it tries again, because a registry that was down comes back.
	now = now.Add(2 * pullRetryAfter)
	if _, err := docker.EnsureImage(context.Background(), appWorkload()); err != nil {
		t.Fatalf("EnsureImage after the cool-down: %v", err)
	}
	waitForPullFailure(t, docker)
	mu.Lock()
	defer mu.Unlock()
	if pulls != 2 {
		t.Errorf("made %d pull requests, want a second one after the cool-down", pulls)
	}
}

func TestPullProgressCountsEveryLayer(t *testing.T) {
	report := newProgress()

	report.observe(jsonstream.Message{Status: "Pulling from library/nginx"})
	report.observe(jsonstream.Message{ID: "a", Status: "Downloading",
		Progress: &jsonstream.Progress{Current: 50, Total: 100}})
	rendered := report.observe(jsonstream.Message{ID: "b", Status: "Downloading",
		Progress: &jsonstream.Progress{Current: 0, Total: 100}})

	if rendered != "Downloading 25%" {
		t.Errorf("progress = %q, want the whole pull rather than one layer of it", rendered)
	}
	rendered = report.observe(jsonstream.Message{ID: "b", Status: "Extracting",
		Progress: &jsonstream.Progress{Current: 100, Total: 100}})
	if rendered != "Extracting 75%" {
		t.Errorf("progress = %q, want the phase to change with the status", rendered)
	}
}

// waitForPullFailure polls until the background pull has finished and its error is what
// EnsureImage answers with. Polling rather than sleeping: the goroutine takes microseconds
// and a fixed sleep would be both slower and flakier.
func waitForPullFailure(t *testing.T, docker *Docker) error {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := docker.EnsureImage(context.Background(), appWorkload()); err != nil {
			return err
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("the pull never finished")
	return nil
}
