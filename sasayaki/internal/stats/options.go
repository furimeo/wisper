package stats

import (
	"context"
	"fmt"
	"log/slog"
	"path/filepath"
	"time"
)

// Building the sampler, and refusing to build one that cannot measure anything.
//
// Every collaborator is required. A nil field would not fail here; it would fail twelve
// seconds later, on a goroutine, in a daemon that had already told systemd it was ready -
// which is the least useful moment and the least useful place for a nil dereference.

// DefaultInterval is how often the machine and its containers are read.
//
// Twelve seconds, inside the ten-to-fifteen band the design asks for and deliberately not
// equal to the reconcile loop's fifteen: two loops on the same period on the same node line
// up and then contend on the Docker socket at the same instant forever, and two that drift
// past each other do not.
const DefaultInterval = 12 * time.Second

// DefaultVolumeInterval is how often a workload's volume tree is walked again.
//
// Twenty-five times slower than the sampling interval, because it is the one measurement
// here that costs a readdir per directory rather than a counter read. A quota bar is a
// slow-moving number and one that is five minutes old is worth what one that is current is
// worth, at a fraction of the disk traffic.
const DefaultVolumeInterval = 5 * time.Minute

const (
	// defaultBufferCapacity is how many samples are kept for a panel that is away.
	//
	// Sixteen thousand is a node running fifty workloads for about an hour: long enough to
	// cover the tunnel outages that actually happen, short enough that the file stays in the
	// low tens of megabytes on a node whose panel has been gone since Friday.
	defaultBufferCapacity = 16384

	// defaultDrainPerPass is how much of the backlog one pass delivers. Deliberately smaller
	// than the uplink's own queue, so catching up never displaces the samples this pass has
	// just taken (publish.go).
	defaultDrainPerPass = 256

	// defaultVolumeEntryBudget caps one walk of one customer's tree. Past it the walk stops
	// and reports what it has, which is a floor rather than a wrong answer.
	defaultVolumeEntryBudget = 200_000

	// defaultProcRoot is where Linux publishes the kernel's own accounting.
	defaultProcRoot = "/proc"
)

// Options is everything the sampler is built from.
type Options struct {
	Engine Engine
	Specs  Specs
	Uplink Uplink

	// StateDir is the node's state directory, /var/lib/wisper. Three things are read from
	// it: the filesystem it sits on, which is the disk this node's pressure is measured
	// against; the volumes under it, which are what each customer is holding; and the
	// buffer file, which is written into it.
	StateDir string

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Interval defaults to DefaultInterval.
	Interval time.Duration

	// VolumeInterval defaults to DefaultVolumeInterval.
	VolumeInterval time.Duration

	// Thresholds defaults field by field, so a caller can move one mark without restating
	// the policy.
	Thresholds Thresholds

	// BufferCapacity is how many samples the ring holds. Zero means defaultBufferCapacity;
	// it is never unbounded, because the disk this buffer sits on is the disk the pressure
	// state exists to protect.
	BufferCapacity int

	// Now defaults to time.Now. Replaced in tests, so a pass can be placed at an exact
	// moment and an hour of outage does not take an hour to simulate.
	Now func() time.Time

	// ProcRoot defaults to /proc. Replaced in tests with a directory of fixtures, which is
	// the only way this parsing can be exercised on a developer's machine.
	ProcRoot string

	// Disk defaults to statfs. Replaced in tests, because the disk-pressure thresholds are
	// the part of this package most worth being sure about and filling a real filesystem to
	// 92% to check them is not a unit test.
	Disk func(path string) (total, available int64, err error)

	// VolumeEntryBudget caps one walk of one workload's volume tree. Zero means
	// defaultVolumeEntryBudget.
	VolumeEntryBudget int
}

func (o Options) validate() error {
	missing := make([]string, 0, 4)
	check := func(name string, provided bool) {
		if !provided {
			missing = append(missing, name)
		}
	}
	check("Engine", o.Engine != nil)
	check("Specs", o.Specs != nil)
	check("Uplink", o.Uplink != nil)
	check("StateDir", o.StateDir != "")

	if len(missing) > 0 {
		return fmt.Errorf("stats.Options is missing %v: the sampler uses every one of these on "+
			"every pass, so a nil field is a crash twelve seconds after the daemon starts", missing)
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("stats.Options.StateDir %q is not absolute: it names the filesystem this "+
			"node's disk pressure is measured against, and a relative path measures whatever "+
			"directory the process happens to be in", o.StateDir)
	}
	return nil
}

// New builds the sampler and opens its buffer.
//
// It takes a context because opening the buffer touches the disk, and a node whose state
// directory is on a filesystem that has gone away should fail while the daemon is still
// starting rather than on a goroutine afterwards.
func New(ctx context.Context, options Options) (*Sampler, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	capacity := options.BufferCapacity
	if capacity <= 0 {
		capacity = defaultBufferCapacity
	}
	ring, err := openBuffer(ctx, filepath.Join(options.StateDir, BufferFileName), capacity)
	if err != nil {
		return nil, err
	}

	thresholds := options.Thresholds.withDefaults()
	volumeInterval := options.VolumeInterval
	if volumeInterval <= 0 {
		volumeInterval = DefaultVolumeInterval
	}
	budget := options.VolumeEntryBudget
	if budget <= 0 {
		budget = defaultVolumeEntryBudget
	}

	sampler := &Sampler{
		engine:       options.Engine,
		specs:        options.Specs,
		uplink:       options.Uplink,
		buffer:       ring,
		log:          options.Logger,
		now:          options.Now,
		stateDir:     options.StateDir,
		interval:     options.Interval,
		thresholds:   thresholds,
		procfs:       procfs{root: options.ProcRoot},
		disk:         options.Disk,
		drainPerPass: min(defaultDrainPerPass, capacity),
		counters:     newCounters(),
		volumes:      newVolumes(options.StateDir, volumeInterval, budget),
		gauge:        newGauge(thresholds.DiskWarning, thresholds.DiskCritical),
	}
	if sampler.log == nil {
		sampler.log = slog.Default()
	}
	if sampler.now == nil {
		sampler.now = time.Now
	}
	if sampler.interval <= 0 {
		sampler.interval = DefaultInterval
	}
	if sampler.procfs.root == "" {
		sampler.procfs.root = defaultProcRoot
	}
	if sampler.disk == nil {
		sampler.disk = diskSpace
	}

	// An open decision and an empty capacity, so nothing that reads the snapshot before the
	// first pass has to check for nil or for a zero value that means "refuse".
	sampler.snapshot.Store(&Snapshot{Capacity: capacityOf(machineReading{}, allocation{}, 0, 0, 0, true),
		Admission: openAdmission()})

	return sampler, nil
}
