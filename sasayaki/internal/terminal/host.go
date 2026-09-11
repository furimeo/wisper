package terminal

import (
	"errors"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Building a Host, and the ceilings every session on it inherits.

const (
	// What a session gets when the panel sends no idle timeout. The panel always sends one
	// (docs/contracts/panel-ports.md section 2.8), so this is the answer to a bug rather
	// than to a normal request - and the answer has to be a number, because the failure it
	// guards against is a forgotten browser tab holding a shell open, as the customer's own
	// application, for weeks (terminal.proto, StartTerminal.idle_timeout_seconds).
	defaultIdleTimeout = 30 * time.Minute

	// The same for the hard ceiling. Long enough that nobody watching a build in a terminal
	// is cut off, short enough that a wedged `top` gives its slot back the same day.
	defaultMaxDuration = 8 * time.Hour

	// How many sessions this node will hold at once. Each one costs a goroutine trio, a
	// hijacked connection and up to a few hundred kilobytes of buffered output, so the
	// number is not about fairness between customers - the panel already decides who may
	// open what - it is the bound that stops a node from being taken down by opening
	// terminals.
	defaultMaxSessions = 64
)

// Options is everything a Host is made of.
type Options struct {
	// Engine is the container runtime. Required: a terminal host with no engine could
	// answer nothing, and finding that out when a customer presses a button is finding it
	// out too late.
	Engine Engine

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Now defaults to time.Now and is used for the timestamps inside frames - when a
	// session attached, when it ended. It is deliberately not used for the idle and
	// duration arithmetic: those are measured against the wall clock the timers use, and
	// two clocks disagreeing is how a timeout fires an hour early on a node whose NTP has
	// just stepped.
	Now func() time.Time

	// IdleTimeout and MaxDuration are used for a request that carries neither, and are the
	// knobs a test turns down so a timeout can be observed without a test that sleeps for
	// half an hour. Zero takes the package default.
	IdleTimeout time.Duration
	MaxDuration time.Duration

	// MaxSessions is how many terminals this node will run at once. Zero takes the package
	// default.
	MaxSessions int
}

func (o Options) validate() error {
	if o.Engine == nil {
		return errors.New("terminal.Options has no Engine: a terminal is a pty inside a " +
			"container, so a host with no container runtime has nothing it could open")
	}
	return nil
}

// Host runs the interactive sessions on this node. Safe for concurrent use: the control
// stream gives every StartTerminal its own goroutine and a customer with two phones is
// two sessions.
type Host struct {
	engine Engine
	log    *slog.Logger
	now    func() time.Time

	idleTimeout time.Duration
	maxDuration time.Duration

	// live is the set of session ids currently attached, which is both the concurrency
	// bound and the guard against two streams claiming one session id (sessions.go).
	live sessions
}

// New opens a terminal host. Nothing is attached until a session arrives.
func New(options Options) (*Host, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	host := &Host{
		engine:      options.Engine,
		log:         options.Logger,
		now:         options.Now,
		idleTimeout: options.IdleTimeout,
		maxDuration: options.MaxDuration,
	}
	if host.log == nil {
		host.log = slog.Default()
	}
	if host.now == nil {
		host.now = time.Now
	}
	if host.idleTimeout <= 0 {
		host.idleTimeout = defaultIdleTimeout
	}
	if host.maxDuration <= 0 {
		host.maxDuration = defaultMaxDuration
	}
	host.live.limit = options.MaxSessions
	if host.live.limit <= 0 {
		host.live.limit = defaultMaxSessions
	}
	return host, nil
}

// idleFor is how long this session may go without a keystroke.
//
// A negative value on the wire is a bug rather than a request for an immortal session, so
// it takes the default alongside zero: the failure mode of guessing too long is a shell
// nobody is using, and the failure mode of honouring a negative number is arithmetic that
// ends the session before it has drawn its first prompt.
func (h *Host) idleFor(request *wisperpb.StartTerminal) time.Duration {
	if seconds := request.GetIdleTimeoutSeconds(); seconds > 0 {
		return time.Duration(seconds) * time.Second
	}
	return h.idleTimeout
}

// durationFor is the ceiling on the whole session, busy or not.
func (h *Host) durationFor(request *wisperpb.StartTerminal) time.Duration {
	if seconds := request.GetMaxDurationSeconds(); seconds > 0 {
		return time.Duration(seconds) * time.Second
	}
	return h.maxDuration
}
