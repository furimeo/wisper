package cron

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What every test in this package builds on: a real state database on a temporary path, a
// clock that only moves when a test moves it, and a container engine that never was one.
//
// It is past three hundred lines and stays one file: it is the fixture the other three share,
// and a fixture split across two files is one where half of a test's arrangement is somewhere
// the reader has not looked.
//
// The store is deliberately not faked. Everything this package does that is worth asserting
// ends as a row - a run that started, a run that was skipped, a next-run time that survived a
// restart - and a fake store would only prove that the fake agrees with the test. The engine
// is faked for the opposite reason: what is worth asserting there is a job that times out, a
// job that panics and a container that is not running, and arranging any of those against a
// real Docker daemon means a unit test that needs a machine.

// The moments these tests happen at. Whole seconds, because timestamps are stored to the
// millisecond and a comparison that fails on rounding teaches nobody anything.
var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

const workloadID = "wl-app"

// discardLogs is the scheduler's journal, thrown away. Every failure this package handles is
// also written down as a row, and the rows are what the tests assert on.
func discardLogs() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// clock is the node's clock, under a test's control. Safe for concurrent use: an execution
// runs on its own goroutine and reads it to time-stamp the row it writes.
type clock struct {
	mu sync.Mutex
	at time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

func (c *clock) set(at time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = at
}

// call is one execution the fake engine was asked for.
type call struct {
	containerID string
	command     []string
	// deadline is what the scheduler gave the execution to finish in. Zero when it handed
	// over a context with no deadline at all, which would mean a job that can never be
	// stopped.
	deadline time.Time
}

// fakeContainers is a container engine whose answers a test writes.
type fakeContainers struct {
	mu sync.Mutex
	// byWorkload is what ContainerFor finds. A workload that is not in here has no container.
	byWorkload map[string]reconcile.Container
	// lookupErr stands in for a Docker daemon that is not answering. It is not the same thing
	// as an empty map and this package must not treat it as one.
	lookupErr error

	calls []call
	// respond is what one execution does. Nil is an immediate success.
	respond func(ctx context.Context, containerID string) (runtime.RunResult, error)
	// begun receives one value as each execution starts, so a test can wait for a run to be
	// in flight rather than sleeping and hoping.
	begun chan struct{}
}

func newFakeContainers() *fakeContainers {
	return &fakeContainers{
		byWorkload: make(map[string]reconcile.Container),
		begun:      make(chan struct{}, 16),
	}
}

func (f *fakeContainers) ContainerFor(_ context.Context, workloadID string) (reconcile.Container, bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.lookupErr != nil {
		return reconcile.Container{}, false, f.lookupErr
	}
	container, found := f.byWorkload[workloadID]
	return container, found, nil
}

func (f *fakeContainers) Run(ctx context.Context, containerID string, options runtime.RunOptions) (runtime.RunResult, error) {
	deadline, _ := ctx.Deadline()

	f.mu.Lock()
	f.calls = append(f.calls, call{containerID: containerID, command: options.Command, deadline: deadline})
	respond := f.respond
	f.mu.Unlock()

	select {
	case f.begun <- struct{}{}:
	default:
	}

	if respond == nil {
		return runtime.RunResult{}, nil
	}
	return respond(ctx, containerID)
}

// place puts a running container on the fake node for one workload.
func (f *fakeContainers) place(workloadID string, running bool, status string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.byWorkload[workloadID] = reconcile.Container{
		ID: "container-" + workloadID, WorkloadID: workloadID, Running: running, Status: status,
	}
}

func (f *fakeContainers) forget(workloadID string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.byWorkload, workloadID)
}

func (f *fakeContainers) ran() []call {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]call(nil), f.calls...)
}

// rig is one assembled scheduler with everything a test needs to drive it.
type rig struct {
	scheduler  *Scheduler
	store      *state.Store
	containers *fakeContainers
	clock      *clock
	// generation is bumped by every publish, because the store refuses a spec older than the
	// one it holds.
	generation uint64
	path       string
}

func newRig(t *testing.T) *rig {
	t.Helper()
	return rigAt(t, filepath.Join(t.TempDir(), state.FileName))
}

// rigAt opens a scheduler on an exact database path, for the tests that need a second daemon
// on the same disk - which is what a restart is.
func rigAt(t *testing.T, path string) *rig {
	t.Helper()

	store, err := state.Open(t.Context(), path)
	if err != nil {
		t.Fatalf("open the state database: %v", err)
	}
	t.Cleanup(func() { store.Close() })

	r := &rig{
		store:      store,
		containers: newFakeContainers(),
		clock:      &clock{at: noon},
		path:       path,
	}
	r.containers.place(workloadID, true, "Up 3 hours")

	scheduler, err := New(Options{
		Containers: r.containers,
		Store:      store,
		Logger:     discardLogs(),
		Now:        r.clock.Now,
	})
	if err != nil {
		t.Fatalf("build the scheduler: %v", err)
	}
	r.scheduler = scheduler
	return r
}

// restart is a second daemon over the same disk, the way an upgrade produces one. The clock
// carries on from wherever the test left it.
//
// The old handle is released first. What is being tested is not what SQLite does with two
// writers - internal/state has that covered, with a child process that is killed outright -
// but what a scheduler that has just read this file believes about a schedule it never saw
// fire.
func (r *rig) restart(t *testing.T) *rig {
	t.Helper()
	r.store.Close()

	fresh := rigAt(t, r.path)
	fresh.clock.set(r.clock.Now())
	fresh.generation = r.generation
	return fresh
}

// publish stores a spec carrying one workload and the entries given.
func (r *rig) publish(t *testing.T, entries ...*wisperpb.CronEntry) {
	t.Helper()
	r.publishWith(t, []*wisperpb.Workload{{Id: workloadID, Name: "app"}}, entries...)
}

// publishWith is publish for the tests that need a spec with no workload in it.
func (r *rig) publishWith(t *testing.T, workloads []*wisperpb.Workload, entries ...*wisperpb.CronEntry) {
	t.Helper()
	r.generation++
	err := r.store.SaveSpec(t.Context(), &wisperpb.NodeSpec{
		Generation: r.generation,
		Workloads:  workloads,
		Cron:       entries,
	}, "a test published it", r.clock.Now())
	if err != nil {
		t.Fatalf("save the spec: %v", err)
	}
}

// tick is one wake-up of the loop at the current time, followed by however long the
// executions it started take.
func (r *rig) tick(t *testing.T) {
	t.Helper()
	r.scheduler.tick(t.Context(), r.clock.Now())
	r.settle(t)
}

// tickAt moves the clock and wakes the loop.
func (r *rig) tickAt(t *testing.T, at time.Time) {
	t.Helper()
	r.clock.set(at)
	r.tick(t)
}

// start wakes the loop without waiting for what it started, for the tests about a run that is
// still in flight.
func (r *rig) start(t *testing.T, at time.Time) {
	t.Helper()
	r.clock.set(at)
	r.scheduler.tick(t.Context(), at)
}

// settle waits for every execution in flight to finish.
func (r *rig) settle(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		r.scheduler.mu.Lock()
		idle := len(r.scheduler.inFlight) == 0
		r.scheduler.mu.Unlock()
		if idle {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("an execution was still running five seconds after the tick that started it")
		}
		time.Sleep(time.Millisecond)
	}
}

// waitForSchedule blocks until the loop has read the spec and written down when one entry is
// next due, and answers with that moment. For the tests that drive Run rather than tick, so
// that nothing races the loop's first pass.
func (r *rig) waitForSchedule(t *testing.T, cronID string) time.Time {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		runs, err := r.store.CronRuns(context.Background())
		if err != nil {
			t.Fatalf("read the cron history: %v", err)
		}
		for _, run := range runs {
			if run.CronID == cronID && !run.NextRunAt.IsZero() {
				return run.NextRunAt
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("the loop never scheduled %s", cronID)
		}
		time.Sleep(time.Millisecond)
	}
}

// history is the row the panel would be shown for one entry.
func (r *rig) history(t *testing.T, cronID string) state.CronRun {
	t.Helper()
	runs, err := r.store.CronRuns(context.Background())
	if err != nil {
		t.Fatalf("read the cron history: %v", err)
	}
	for _, run := range runs {
		if run.CronID == cronID {
			return run
		}
	}
	t.Fatalf("no history for %s; the store holds %d row(s)", cronID, len(runs))
	return state.CronRun{}
}

// task is a cron entry with everything a test does not care about already filled in.
func task(id, schedule string) *wisperpb.CronEntry {
	return &wisperpb.CronEntry{
		Id:             id,
		WorkloadId:     workloadID,
		Schedule:       schedule,
		Command:        []string{"/usr/bin/php", "artisan", "queue:prune"},
		TimeoutSeconds: 60,
	}
}
