package stats

import (
	"context"
	"errors"
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

// The fakes every test in this package builds on.
//
// No Docker, no panel and no /proc. What each stand-in exists for is specific: the engine so
// a container can be restarted underneath the sampler without killing anything real, the
// uplink so an hour of outage takes a microsecond, and the disk so a filesystem can be 92%
// full without one being filled. The buffer is deliberately not faked - it is real SQLite in
// a temporary directory, because "the ring is bounded" is a claim about SQL.

// fakeEngine is a container engine whose answers a test writes.
type fakeEngine struct {
	mu         sync.Mutex
	containers []reconcile.Container
	samples    map[string]runtime.Sample
	// listError is returned by Containers instead of the list, standing in for a Docker
	// daemon that is not answering.
	listError error
	// sampleError is returned for one container id, standing in for one that exited between
	// being listed and being read.
	sampleError map[string]error
}

func newFakeEngine() *fakeEngine {
	return &fakeEngine{samples: make(map[string]runtime.Sample), sampleError: make(map[string]error)}
}

func (f *fakeEngine) Containers(context.Context) ([]reconcile.Container, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.listError != nil {
		return nil, f.listError
	}
	return append([]reconcile.Container(nil), f.containers...), nil
}

func (f *fakeEngine) Sample(_ context.Context, containerID string) (runtime.Sample, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if err := f.sampleError[containerID]; err != nil {
		return runtime.Sample{}, err
	}
	sample, known := f.samples[containerID]
	if !known {
		return runtime.Sample{}, errors.New("no such container")
	}
	return sample, nil
}

// place puts one running container on the fake node with the counters given.
func (f *fakeEngine) place(workloadID, containerID string, startedAt time.Time, sample runtime.Sample) {
	f.mu.Lock()
	defer f.mu.Unlock()
	sample.ContainerID = containerID
	for index, existing := range f.containers {
		if existing.WorkloadID == workloadID {
			f.containers[index] = reconcile.Container{
				ID: containerID, WorkloadID: workloadID, Running: true, StartedAt: startedAt,
			}
			f.samples[containerID] = sample
			return
		}
	}
	f.containers = append(f.containers, reconcile.Container{
		ID: containerID, WorkloadID: workloadID, Running: true, StartedAt: startedAt,
	})
	f.samples[containerID] = sample
}

// fakeSpecs is the desired state, or the absence of one.
type fakeSpecs struct {
	spec *wisperpb.NodeSpec
	err  error
}

func (f *fakeSpecs) LoadSpec(context.Context) (state.StoredSpec, error) {
	if f.err != nil {
		return state.StoredSpec{}, f.err
	}
	if f.spec == nil {
		return state.StoredSpec{}, state.ErrNoSpec
	}
	return state.StoredSpec{Spec: f.spec, Generation: f.spec.GetGeneration()}, nil
}

// fakeUplink is the panel: reachable or not, and with a queue that can be made to refuse.
type fakeUplink struct {
	mu        sync.Mutex
	connected bool
	// queue is how many samples it will take before refusing. Zero means no limit.
	queue    int
	accepted []*wisperpb.StatSample
	events   []*wisperpb.NodeEvent
}

func (f *fakeUplink) SendStat(sample *wisperpb.StatSample) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.queue > 0 && len(f.accepted) >= f.queue {
		return false
	}
	f.accepted = append(f.accepted, sample)
	return true
}

func (f *fakeUplink) Connected() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.connected
}

func (f *fakeUplink) Emit(event *wisperpb.NodeEvent) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, event)
}

func (f *fakeUplink) NodeID() string { return "node-under-test" }

func (f *fakeUplink) reach(connected bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connected = connected
}

func (f *fakeUplink) delivered() []*wisperpb.StatSample {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*wisperpb.StatSample(nil), f.accepted...)
}

func (f *fakeUplink) emitted() []*wisperpb.NodeEvent {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*wisperpb.NodeEvent(nil), f.events...)
}

// fakeDisk is a filesystem of a fixed size with a settable amount used.
type fakeDisk struct {
	mu        sync.Mutex
	total     int64
	available int64
}

func (d *fakeDisk) space(string) (int64, int64, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.total, d.available, nil
}

func (d *fakeDisk) fillTo(fraction float64) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.available = d.total - int64(float64(d.total)*fraction)
}

// clock is a time source a test advances by hand, so an hour of outage costs nothing.
type clock struct {
	mu  sync.Mutex
	now time.Time
}

func newClock() *clock {
	return &clock{now: time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)}
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// rig is a sampler and everything that was put behind it.
type rig struct {
	sampler *Sampler
	engine  *fakeEngine
	specs   *fakeSpecs
	uplink  *fakeUplink
	disk    *fakeDisk
	clock   *clock

	// proc is the fixture root, and machine is what is currently written into it.
	proc    string
	machine procFixture
}

// burn moves the machine's own counters forward, which is what a real /proc does between two
// passes.
func (r *rig) burn(t *testing.T, cpuTicks, rx, tx int64) {
	t.Helper()
	r.machine.cpuTicks += cpuTicks
	r.machine.rxBytes += rx
	r.machine.txBytes += tx
	rewriteProcFixture(t, r.proc, r.machine)
}

// newRig builds a sampler over the fakes, with a real buffer in a temporary directory and a
// /proc made of fixtures.
func newRig(t *testing.T, adjust func(*Options)) *rig {
	t.Helper()

	stateDir, err := filepath.Abs(t.TempDir())
	if err != nil {
		t.Fatalf("resolve the temporary state directory: %v", err)
	}
	engine := newFakeEngine()
	specs := &fakeSpecs{}
	uplink := &fakeUplink{connected: true}
	disk := &fakeDisk{total: 100 << 30, available: 60 << 30}
	tick := newClock()
	machine := procFixture{cpuTicks: 0, bootedAt: 1700000000, cores: 4,
		memTotalKiB: 8 << 20, memAvailableKiB: 6 << 20, rxBytes: 0, txBytes: 0, load: 0.5}

	options := Options{
		Engine:   engine,
		Specs:    specs,
		Uplink:   uplink,
		StateDir: stateDir,
		Logger:   testLogger(),
		Now:      tick.Now,
		ProcRoot: writeProcFixture(t, machine),
		Disk:     disk.space,
	}
	if adjust != nil {
		adjust(&options)
	}

	sampler, err := New(t.Context(), options)
	if err != nil {
		t.Fatalf("build the sampler: %v", err)
	}
	t.Cleanup(func() { sampler.Close() })

	return &rig{sampler: sampler, engine: engine, specs: specs, uplink: uplink,
		disk: disk, clock: tick, proc: options.ProcRoot, machine: machine}
}

// tick advances the clock and takes one pass, which is what the Run loop does on a timer.
func (r *rig) tick(t *testing.T, after time.Duration) {
	t.Helper()
	r.clock.advance(after)
	r.sampler.tick(t.Context())
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// workloadSampleFor finds the sample describing one workload, and fails when there is none.
func workloadSampleFor(t *testing.T, samples []*wisperpb.StatSample, workloadID string) *wisperpb.WorkloadSample {
	t.Helper()
	for _, sample := range samples {
		if workload := sample.GetWorkload(); workload != nil && workload.GetWorkloadId() == workloadID {
			return workload
		}
	}
	t.Fatalf("no sample for the workload %q among %d samples", workloadID, len(samples))
	return nil
}
