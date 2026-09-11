package reconcile

import (
	"context"
	"errors"
	"sort"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The four collaborators these tests stand in for: the container engine, the release
// symlink, the edge and the panel.
//
// Each one records what it was asked to do rather than merely answering, because most of
// what this package has to get right is about what it does *not* do - the container it
// leaves alone during an outage, the edge it does not rebuild on an unchanged pass.
//
// Past three hundred lines and kept in one file deliberately: these are four small doubles
// of one thing, the machine, and splitting them by collaborator would mean four files of
// twenty lines each that are only ever read together.

// fakeRuntime is a container engine that keeps its containers in a map and remembers what
// it was asked to do to them.
type fakeRuntime struct {
	mu         sync.Mutex
	containers map[string]Container
	// listErr, when set, is what Containers answers. The whole point of several tests.
	listErr error
	// images that are not ready yet, by image reference.
	pulling  map[string]ImageState
	imageErr error

	createErr error
	startErr  error
	stopErr   error
	removeErr error

	created  []string
	started  []string
	stopped  []string
	removed  []string
	sequence int
}

func newFakeRuntime() *fakeRuntime {
	return &fakeRuntime{
		containers: make(map[string]Container),
		pulling:    make(map[string]ImageState),
	}
}

func (f *fakeRuntime) Containers(context.Context) ([]Container, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.listErr != nil {
		return nil, f.listErr
	}
	out := make([]Container, 0, len(f.containers))
	for _, container := range f.containers {
		out = append(out, container)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (f *fakeRuntime) EnsureImage(_ context.Context, workload spec.Workload) (ImageState, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.imageErr != nil {
		return ImageState{}, f.imageErr
	}
	if pull, waiting := f.pulling[workload.Image]; waiting {
		return pull, nil
	}
	return ImageState{Ready: true, Digest: "sha256:" + workload.Image}, nil
}

func (f *fakeRuntime) Create(_ context.Context, workload spec.Workload, fingerprint string) (Container, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.createErr != nil {
		return Container{}, f.createErr
	}
	f.sequence++
	container := Container{
		ID:          "ctr-" + workload.ID + "-" + strconv.Itoa(f.sequence),
		WorkloadID:  workload.ID,
		Name:        workload.Name,
		Fingerprint: fingerprint,
		Image:       workload.Image,
		ImageDigest: "sha256:" + workload.Image,
		Runtime:     workload.Runtime,
		Status:      "Created",
	}
	f.containers[container.ID] = container
	f.created = append(f.created, workload.ID)
	return container, nil
}

func (f *fakeRuntime) Start(_ context.Context, containerID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.startErr != nil {
		return f.startErr
	}
	container, known := f.containers[containerID]
	if !known {
		return errors.New("no such container: " + containerID)
	}
	container.Running = true
	container.StartedAt = noon
	container.Status = "Up 1 second"
	f.containers[containerID] = container
	f.started = append(f.started, containerID)
	return nil
}

func (f *fakeRuntime) Stop(_ context.Context, containerID string, _ time.Duration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.stopErr != nil {
		return f.stopErr
	}
	container, known := f.containers[containerID]
	if !known {
		return errors.New("no such container: " + containerID)
	}
	container.Running = false
	container.Status = "Exited (0) 1 second ago"
	f.containers[containerID] = container
	f.stopped = append(f.stopped, containerID)
	return nil
}

func (f *fakeRuntime) Remove(_ context.Context, containerID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.removeErr != nil {
		return f.removeErr
	}
	delete(f.containers, containerID)
	f.removed = append(f.removed, containerID)
	return nil
}

// put installs a container as if the engine already had it.
func (f *fakeRuntime) put(container Container) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.containers[container.ID] = container
}

func (f *fakeRuntime) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.containers)
}

func (f *fakeRuntime) removals() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.removed...)
}

// fakeSites is the release symlink of every static site on the node.
type fakeSites struct {
	mu           sync.Mutex
	published    map[string]string
	publishedErr error
	publishErr   error
	discarded    []string
}

func newFakeSites() *fakeSites {
	return &fakeSites{published: make(map[string]string)}
}

func (f *fakeSites) Published(_ context.Context, workloadID string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.publishedErr != nil {
		return "", f.publishedErr
	}
	return f.published[workloadID], nil
}

func (f *fakeSites) Publish(_ context.Context, workloadID, releaseID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.publishErr != nil {
		return f.publishErr
	}
	f.published[workloadID] = releaseID
	return nil
}

func (f *fakeSites) Discard(_ context.Context, workloadID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.published, workloadID)
	f.discarded = append(f.discarded, workloadID)
	return nil
}

// fakeEdge counts how often the route table was loaded, which is how the tests prove that
// a pass that changed nothing did not rebuild the edge's configuration.
type fakeEdge struct {
	mu        sync.Mutex
	syncs     int
	last      spec.Spec
	syncErr   error
	statuses  []spec.RouteStatus
	statusErr error
}

func (f *fakeEdge) Sync(_ context.Context, desired spec.Spec) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.syncErr != nil {
		return f.syncErr
	}
	f.syncs++
	f.last = desired
	return nil
}

func (f *fakeEdge) Statuses(context.Context) ([]spec.RouteStatus, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.statusErr != nil {
		return nil, f.statusErr
	}
	return append([]spec.RouteStatus(nil), f.statuses...), nil
}

func (f *fakeEdge) syncCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.syncs
}

// fakeDatabases is the shared engine layer, for the sizes that ride along in a batch.
type fakeDatabases struct {
	statuses []spec.DatabaseStatus
	err      error
}

func (f *fakeDatabases) Statuses(context.Context) ([]spec.DatabaseStatus, error) {
	return f.statuses, f.err
}

// fakeReporter is the panel.
type fakeReporter struct {
	mu      sync.Mutex
	batches []*wisperpb.StatusBatch
	events  []*wisperpb.NodeEvent
	ack     *wisperpb.Ack
	err     error
}

func (f *fakeReporter) ReportStatus(_ context.Context, batch *wisperpb.StatusBatch) (*wisperpb.Ack, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return nil, f.err
	}
	f.batches = append(f.batches, batch)
	if f.ack != nil {
		return f.ack, nil
	}
	return &wisperpb.Ack{}, nil
}

func (f *fakeReporter) Emit(event *wisperpb.NodeEvent) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, event)
}

func (f *fakeReporter) latest(t *testing.T) *wisperpb.StatusBatch {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.batches) == 0 {
		t.Fatal("the panel was told nothing about this pass")
	}
	return f.batches[len(f.batches)-1]
}

func (f *fakeReporter) kinds() []wisperpb.NodeEventKind {
	f.mu.Lock()
	defer f.mu.Unlock()
	kinds := make([]wisperpb.NodeEventKind, 0, len(f.events))
	for _, event := range f.events {
		kinds = append(kinds, event.GetKind())
	}
	return kinds
}

// adopt copies another engine's containers, which is how "the daemon restarted" is
// modelled: the daemon is replaced and the containers, being children of Docker, are not.
func (f *fakeRuntime) adopt(other *fakeRuntime) {
	other.mu.Lock()
	defer other.mu.Unlock()
	f.mu.Lock()
	defer f.mu.Unlock()
	for id, container := range other.containers {
		f.containers[id] = container
	}
	f.sequence = other.sequence
}

// adopt copies another node's published releases, for the same reason: a symlink on disk
// outlives the process that made it.
func (f *fakeSites) adopt(other *fakeSites) {
	other.mu.Lock()
	defer other.mu.Unlock()
	f.mu.Lock()
	defer f.mu.Unlock()
	for id, release := range other.published {
		f.published[id] = release
	}
}
