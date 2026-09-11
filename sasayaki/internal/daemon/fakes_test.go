package daemon

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"time"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/dbengine"
	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the tests in this package stand in for.
//
// All of it, which is unusual and is the point: this package is wiring, so the behaviour
// worth testing is what it does to the values passing through it - an error translated, a
// workload id turned into a container id, a heartbeat assembled while the disk is wedged.
// None of that needs Docker, SQLite or a panel, and a test that used real ones would be
// proving that those work rather than that this connects them correctly.

// errEngineDown is what a fake answers with when a test takes the container engine away.
var errEngineDown = errors.New("cannot connect to the docker daemon at unix:///var/run/docker.sock")

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// --- the state database -----------------------------------------------------

// fakeSpecWriter records what was stored and answers with whatever a test set.
type fakeSpecWriter struct {
	err error

	saved      *wisperpb.NodeSpec
	savedFor   string
	savedCalls int
}

func (f *fakeSpecWriter) SaveSpec(_ context.Context, spec *wisperpb.NodeSpec, reason string, _ time.Time) error {
	f.savedCalls++
	if f.err != nil {
		return f.err
	}
	f.saved, f.savedFor = spec, reason
	return nil
}

// fakeNudger is the reconcile loop, reduced to the one method the control stream uses.
type fakeNudger struct {
	reasons []string
}

func (f *fakeNudger) ReconcileNow(reason string) { f.reasons = append(f.reasons, reason) }

// fakeConvergence is the convergence record, with a delay a test can use to wedge it.
type fakeConvergence struct {
	record state.Convergence
	err    error
	// block holds the read until the caller's context expires, which is what a state
	// database queued behind a long write looks like from the heartbeat's side.
	block bool
}

func (f *fakeConvergence) Convergence(ctx context.Context) (state.Convergence, error) {
	if f.block {
		<-ctx.Done()
		return state.Convergence{}, ctx.Err()
	}
	return f.record, f.err
}

// --- the sampler ------------------------------------------------------------

type fakeSampler struct {
	snapshot stats.Snapshot
}

func (f fakeSampler) Snapshot() stats.Snapshot     { return f.snapshot }
func (f fakeSampler) Capacity() *wisperpb.Capacity { return f.snapshot.Capacity }

// fakeDrain is the drain flag the heartbeat reads.
type fakeDrain bool

func (f fakeDrain) Draining() bool { return bool(f) }

// --- the container engine ---------------------------------------------------

// fakeContainers is the runtime as the backup adapter sees it.
type fakeContainers struct {
	byWorkload map[string]reconcile.Container
	lookupErr  error
	startErr   error
	stopErr    error

	started []string
	stopped []string
}

func newFakeContainers() *fakeContainers {
	return &fakeContainers{byWorkload: make(map[string]reconcile.Container)}
}

func (f *fakeContainers) ContainerFor(_ context.Context, workloadID string) (reconcile.Container, bool, error) {
	if f.lookupErr != nil {
		return reconcile.Container{}, false, f.lookupErr
	}
	container, found := f.byWorkload[workloadID]
	return container, found, nil
}

func (f *fakeContainers) Start(_ context.Context, containerID string) error {
	f.started = append(f.started, containerID)
	return f.startErr
}

func (f *fakeContainers) Stop(_ context.Context, containerID string, _ time.Duration) error {
	f.stopped = append(f.stopped, containerID)
	return f.stopErr
}

// fakeFreezer is the engine's pause and unpause.
type fakeFreezer struct {
	pauseErr   error
	unpauseErr error

	paused   []string
	unpaused []string
}

func (f *fakeFreezer) ContainerPause(_ context.Context, containerID string,
	_ client.ContainerPauseOptions) (client.ContainerPauseResult, error) {
	f.paused = append(f.paused, containerID)
	return client.ContainerPauseResult{}, f.pauseErr
}

func (f *fakeFreezer) ContainerUnpause(_ context.Context, containerID string,
	_ client.ContainerUnpauseOptions) (client.ContainerUnpauseResult, error) {
	f.unpaused = append(f.unpaused, containerID)
	return client.ContainerUnpauseResult{}, f.unpauseErr
}

// --- the database servers ---------------------------------------------------

type fakeEngines struct {
	dumpErr      error
	restoreErr   error
	provisionErr error

	dumped      []dbengine.Target
	restored    []dbengine.Target
	provisioned []*wisperpb.ProvisionDatabase
}

func (f *fakeEngines) Dump(_ context.Context, target dbengine.Target, into io.Writer) (int64, error) {
	f.dumped = append(f.dumped, target)
	if f.dumpErr != nil {
		return 0, f.dumpErr
	}
	written, err := io.WriteString(into, "a dump of "+target.DatabaseName)
	return int64(written), err
}

func (f *fakeEngines) Restore(_ context.Context, target dbengine.Target, from io.Reader) (int64, error) {
	f.restored = append(f.restored, target)
	if f.restoreErr != nil {
		return 0, f.restoreErr
	}
	read, err := io.Copy(io.Discard, from)
	return read, err
}

func (f *fakeEngines) ProvisionDatabase(_ context.Context,
	request *wisperpb.ProvisionDatabase) (*wisperpb.DatabaseProvisioned, error) {
	f.provisioned = append(f.provisioned, request)
	if f.provisionErr != nil {
		return nil, f.provisionErr
	}
	return &wisperpb.DatabaseProvisioned{DatabaseName: request.GetGrant().GetDatabaseName()}, nil
}

// --- log feeds --------------------------------------------------------------

// fakeLogStream hands out a scripted sequence of chunks and then an error.
type fakeLogStream struct {
	chunks []runtime.LogChunk
	end    error

	mu     sync.Mutex
	index  int
	closed bool
}

func (f *fakeLogStream) Next() (runtime.LogChunk, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.index < len(f.chunks) {
		chunk := f.chunks[f.index]
		f.index++
		return chunk, nil
	}
	return runtime.LogChunk{}, f.end
}

func (f *fakeLogStream) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}

func (f *fakeLogStream) wasClosed() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.closed
}

// fakeLogReader is the engine as a log feed sees it.
type fakeLogReader struct {
	containers *fakeContainers
	stream     *fakeLogStream
	openErr    error

	mu       sync.Mutex
	opened   []string
	options  runtime.LogOptions
	openings int
}

func (f *fakeLogReader) ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error) {
	return f.containers.ContainerFor(ctx, workloadID)
}

func (f *fakeLogReader) Logs(_ context.Context, containerID string, options runtime.LogOptions) (logStream, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.openings++
	if f.openErr != nil {
		return nil, f.openErr
	}
	f.opened = append(f.opened, containerID)
	f.options = options
	return f.stream, nil
}

func (f *fakeLogReader) lastOptions() runtime.LogOptions {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.options
}

// fakeSink collects what was pushed to the panel, and can refuse.
type fakeSink struct {
	mu      sync.Mutex
	refuse  bool
	chunks  []*wisperpb.LogChunk
	waiting chan struct{}
}

func newFakeSink() *fakeSink {
	return &fakeSink{waiting: make(chan struct{}, 64)}
}

func (f *fakeSink) SendLog(chunk *wisperpb.LogChunk) bool {
	f.mu.Lock()
	refused := f.refuse
	if !refused {
		f.chunks = append(f.chunks, chunk)
	}
	f.mu.Unlock()

	select {
	case f.waiting <- struct{}{}:
	default:
	}
	return !refused
}

// await blocks until at least count chunks have been offered, or the test gives up.
func (f *fakeSink) await(count int) bool {
	deadline := time.After(2 * time.Second)
	for {
		f.mu.Lock()
		enough := len(f.chunks) >= count
		f.mu.Unlock()
		if enough {
			return true
		}
		select {
		case <-f.waiting:
		case <-deadline:
			return false
		}
	}
}

func (f *fakeSink) taken() []*wisperpb.LogChunk {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*wisperpb.LogChunk(nil), f.chunks...)
}
