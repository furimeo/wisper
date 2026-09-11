package reconcile

import (
	"fmt"
	"log/slog"
	"time"
)

// Building the loop, and refusing to build a broken one.
//
// Every collaborator is required. A field left nil would not fail here; it would fail
// fifteen seconds later, on a goroutine, in a daemon that had already told systemd it was
// ready - which is the least useful moment and the least useful place for a nil pointer.

// Options is everything the loop is built from.
type Options struct {
	Runtime   Runtime
	Sites     Sites
	Edge      Edge
	Store     Store
	Databases Databases
	Reporter  Reporter

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Interval is the tick before any spec has been read. Zero means DefaultInterval.
	Interval time.Duration

	// Now defaults to time.Now. Replaced in tests, so a pass can be placed at an exact
	// moment and nothing has to sleep to make a timestamp move.
	Now func() time.Time

	// WatchdogInterval overrides what systemd asked for. Zero means ask; a negative value
	// disables the watchdog entirely. Only tests set it.
	WatchdogInterval time.Duration

	// Notify replaces the sd_notify call. It is given the raw state string - "READY=1",
	// "WATCHDOG=1". Only tests set it.
	Notify func(state string) error
}

func (o Options) validate() error {
	missing := make([]string, 0, 6)
	check := func(name string, provided bool) {
		if !provided {
			missing = append(missing, name)
		}
	}
	check("Runtime", o.Runtime != nil)
	check("Sites", o.Sites != nil)
	check("Edge", o.Edge != nil)
	check("Store", o.Store != nil)
	check("Databases", o.Databases != nil)
	check("Reporter", o.Reporter != nil)

	if len(missing) > 0 {
		return fmt.Errorf("reconcile.Options is missing %v: the loop calls every one of these on "+
			"every pass, so a nil field is a crash fifteen seconds after the daemon starts", missing)
	}
	return nil
}

// New builds the loop.
func New(options Options) (*Loop, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	l := &Loop{
		runtime:   options.Runtime,
		sites:     options.Sites,
		edge:      options.Edge,
		store:     options.Store,
		databases: options.Databases,
		reporter:  options.Reporter,
		log:       options.Logger,
		now:       options.Now,
		interval:  options.Interval,
		nudge:     make(chan string, 1),
	}
	if l.log == nil {
		l.log = slog.Default()
	}
	if l.now == nil {
		l.now = time.Now
	}
	if l.interval <= 0 {
		l.interval = DefaultInterval
	}

	l.watchdog = detectWatchdog()
	if options.WatchdogInterval != 0 {
		l.watchdog.interval = options.WatchdogInterval
	}
	if options.Notify != nil {
		l.watchdog.notify = options.Notify
	}

	return l, nil
}
