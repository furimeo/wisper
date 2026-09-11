package cron

import (
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// Building the scheduler, and refusing to build a broken one.
//
// Both collaborators are required. A nil field would not fail here; it would fail at three in
// the morning, on a goroutine, in a daemon that had been running for a week - which is the
// least useful moment and the least useful place for a nil dereference.

const (
	// DefaultRefresh is how often the spec is re-read.
	//
	// The same cadence as the reconcile loop, and for the same reason: entries appear, change
	// and disappear as generations arrive, and a scheduler holding the first spec it ever saw
	// would keep firing a job the customer deleted last week. It is also what bounds how long
	// the loop sleeps, so a clock that jumped or a machine that was suspended is noticed
	// within one interval rather than at the next firing.
	DefaultRefresh = reconcile.DefaultInterval

	// DefaultTimeout is the ceiling for an entry that arrived without one.
	//
	// Five minutes, which is what cron_task.timeout_seconds defaults to in the panel, so an
	// entry that somehow reached here with a zero behaves the way the screen that created it
	// said it would. There is no "no timeout" option anywhere in this platform: an unbounded
	// job is how a node ends up with two hundred copies of the same stuck script.
	DefaultTimeout = 5 * time.Minute

	// minSleep keeps the loop from spinning if a wake-up computes to now or the past. Nothing
	// is due at that point - a firing always advances its entry past the moment it fired - so
	// this is a floor against arithmetic, not a delay anything waits on.
	minSleep = 20 * time.Millisecond
)

// Options is everything the scheduler is built from.
type Options struct {
	Containers Containers
	Store      Store

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Now defaults to time.Now. Replaced in tests, so a firing can be placed at an exact
	// moment - including one on the far side of a daylight-saving change - without anything
	// having to sleep to get there.
	Now func() time.Time

	// Refresh overrides how often the spec is re-read. Zero means DefaultRefresh.
	Refresh time.Duration
}

// New builds the scheduler. Nothing fires until Run is called.
func New(options Options) (*Scheduler, error) {
	missing := make([]string, 0, 2)
	if options.Containers == nil {
		missing = append(missing, "Containers")
	}
	if options.Store == nil {
		missing = append(missing, "Store")
	}
	if len(missing) > 0 {
		return nil, errors.New("cron.Options is missing " + strings.Join(missing, " and ") +
			": a scheduler that cannot reach the engine or the disk would accept every entry " +
			"in the spec and run none of them")
	}

	s := &Scheduler{
		containers: options.Containers,
		store:      options.Store,
		log:        options.Logger,
		now:        options.Now,
		refresh:    options.Refresh,
		entries:    make(map[string]*entry),
		inFlight:   make(map[string]int),
	}
	if s.log == nil {
		s.log = slog.Default()
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.refresh <= 0 {
		s.refresh = DefaultRefresh
	}
	return s, nil
}
