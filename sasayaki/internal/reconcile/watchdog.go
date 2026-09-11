package reconcile

import (
	"log/slog"
	"time"

	systemd "github.com/coreos/go-systemd/v22/daemon"
)

// Proving to systemd that the loop is still turning.
//
// The unit file sets Type=notify and WatchdogSec=60, so a daemon that stops saying
// WATCHDOG=1 is killed and restarted. That is the point: a reconcile loop that has wedged
// - on a Docker call that never returns, on a lock nobody releases - keeps the process
// alive and answering nothing, which is the failure mode that is hardest to notice from
// the outside. Restarting it costs nothing, because customers' containers are children of
// the engine and not of this process (design section 7.5).
//
// The notification is sent from the same select loop that runs the passes, and that is
// what makes it a real check rather than a decoration: a pass that never returns blocks
// the loop, no notification goes out, and systemd does what it is there for. A ticker on
// its own goroutine would go on cheerfully reporting a wedged daemon as healthy.
//
// The one exception is deliberate. A pass may legitimately take minutes - stopping a dozen
// containers, each with its stop grace - and being restarted in the middle of that would
// be worse than waiting. So the notification is sent while a pass is in flight, up to
// stallAfter; past that the loop is not slow, it is stuck.

// defaultStall is how long a single pass may run before the daemon stops claiming to be
// healthy. Chosen against the slowest legitimate pass: a node holding a few dozen
// containers, all of them being stopped for a drain at ten seconds of grace each.
const defaultStall = 5 * time.Minute

// watchdog is how this loop talks to the service manager.
type watchdog struct {
	// How often to notify. Zero disables everything here, which is the case whenever the
	// daemon is not running under a systemd unit that asked for a watchdog - `run --dev`,
	// a test, a container.
	interval time.Duration
	// How long one pass may run before notifications stop.
	stallAfter time.Duration
	// Replaced in tests. Nothing in production passes anything else.
	notify func(state string) error
}

// detectWatchdog reads what the service manager asked for.
//
// Half the interval, which is what systemd's own documentation recommends: notifying at
// exactly WatchdogSec means every scheduling hiccup is a restart.
func detectWatchdog() watchdog {
	w := watchdog{stallAfter: defaultStall, notify: notifySystemd}

	window, err := systemd.SdWatchdogEnabled(false)
	if err != nil || window <= 0 {
		// No WATCHDOG_USEC in the environment, or one this process is not the recipient
		// of. Not an error: the daemon is expected to run outside systemd during
		// development, and an unreadable value is answered by not pretending to be
		// supervised.
		return w
	}
	w.interval = window / 2
	return w
}

// notifySystemd is the real thing. It answers nil when there is no NOTIFY_SOCKET in the
// environment, which is the ordinary case outside a systemd unit.
func notifySystemd(state string) error {
	_, err := systemd.SdNotify(false, state)
	return err
}

// ticker is the channel the loop selects on, and a stop function. A disabled watchdog
// returns a channel that never fires, so the caller has no special case.
func (w watchdog) ticker() (<-chan time.Time, func()) {
	if w.interval <= 0 {
		return nil, func() {}
	}
	tick := time.NewTicker(w.interval)
	return tick.C, tick.Stop
}

// feed tells the service manager the loop is alive, unless a pass has been running so long
// that saying so would be a lie.
//
// startedAt is the moment the pass in flight began, or the zero time when the loop is
// between passes.
func (w watchdog) feed(log *slog.Logger, startedAt, now time.Time) {
	if w.interval <= 0 || w.notify == nil {
		return
	}
	if !startedAt.IsZero() && now.Sub(startedAt) > w.stallAfter {
		log.Error("a reconcile pass has been running too long to call this node healthy",
			slog.Duration("running_for", now.Sub(startedAt)),
			slog.String("meaning", "the service manager will restart this daemon; customers' containers are not affected"))
		return
	}
	if err := w.notify(systemd.SdNotifyWatchdog); err != nil {
		log.Warn("could not notify the service manager", slog.String("error", err.Error()))
	}
}

// notifyReady is sent once, after the first pass, to satisfy Type=notify.
//
// After the first pass and not at startup, because Type=notify means "tell me when you are
// ready to work" and a daemon that has not yet read its spec, listed its containers and
// converged them once is not. `systemctl start` blocking until that is true is what makes
// the installer able to say the node is up (design section 7.3).
func (w watchdog) notifyReady(log *slog.Logger) {
	if w.notify == nil {
		return
	}
	if err := w.notify(systemd.SdNotifyReady); err != nil {
		log.Warn("could not tell the service manager this node is ready",
			slog.String("error", err.Error()))
	}
}
