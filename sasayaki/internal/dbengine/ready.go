package dbengine

import (
	"context"
	"fmt"
	"time"
)

// Waiting for a server to start accepting connections.
//
// A PostgreSQL container that has just been created spends anywhere from two seconds to a
// minute running initdb, and a MySQL one longer still. Nothing here sleeps for a guessed
// interval: the server is asked, with its own client, until it answers or the budget runs out.
// A guessed sleep is wrong in both directions - too short and the very first provision on a
// new node fails, too long and every command pays for the worst case forever - and the
// failure it produces reads like the database is broken rather than like it was not up yet.
//
// The budget is the caller's, and the two callers want completely different ones. A customer
// pressing "create database" is waiting on a screen and can be made to wait a minute; the
// status pass that runs every fifteen seconds cannot wait at all, and reports "not accepting
// connections yet" instead - which is what DatabaseStatus.exists is for.

const (
	// probeConnectSeconds is how long the client itself waits for a connection. Short: a
	// server that is still initialising refuses immediately, and this bounds the case where
	// it accepts the socket and then does not answer.
	probeConnectSeconds = 3

	// probeInterval is how long to leave between attempts. Short enough that a server which
	// came up during the gap is noticed at once, long enough that a minute of waiting is
	// about a hundred cheap probes rather than a busy loop.
	probeInterval = 500 * time.Millisecond

	// probeAttemptBudget is the whole time one attempt may take, connect timeout included, so
	// a client that hangs cannot use the entire budget on its first try.
	probeAttemptBudget = 10 * time.Second

	// StartupBudget is how long a command that a person is waiting for gives a server to come
	// up. A cold MySQL initialising its data directory on a slow disk is the case this covers.
	StartupBudget = 3 * time.Minute

	// GlanceBudget is what the fifteen-second status pass allows: three probes at most. The
	// question there is "is it up now", not "wait until it is", and a server that is coming
	// back will be up by the next pass - which is fifteen seconds away, not fifteen minutes.
	GlanceBudget = 1500 * time.Millisecond
)

// waitReady polls the server until it answers or the budget runs out.
//
// The error it returns names the last thing the client said, because "the server is not ready"
// on its own is indistinguishable from a wrong password, a wrong port and an image that
// exits immediately - and those three need three different fixes.
func (e *Engines) waitReady(ctx context.Context, built instance, containerID string, budget time.Duration) error {
	// Wall clock, not the injected one: this is a real wait for a real process to come up,
	// and a test that froze the clock here would spin rather than time out.
	deadline := time.Now().Add(budget)
	probe := built.Talk.probe(built.Spec)
	execute := e.runner(containerID)

	var last error
	for {
		// One attempt may never outlast the whole budget. Without this a server that accepts
		// the socket and then hangs would use the client's full ten seconds on its first try,
		// and the fifteen-second status pass - which allows one and a half - would be the thing
		// that missed the systemd watchdog window.
		attemptCtx, cancel := context.WithTimeout(ctx, attemptBudget(deadline))
		_, err := execute(attemptCtx, probe)
		cancel()
		if err == nil {
			return nil
		}
		last = err

		if !time.Now().Before(deadline) {
			break
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("dbengine: gave up waiting for the %s server %s: %w",
				built.Kind, built.ID, ctx.Err())
		case <-time.After(probeInterval):
		}
	}

	return fmt.Errorf("dbengine: the %s server %s was not accepting connections after %s: %w",
		built.Kind, built.ID, budget, last)
}

// attemptBudget is how long one probe gets: the client's own ceiling, or whatever is left of
// the caller's budget when that is less.
//
// The floor keeps a budget that has just run out from producing a context that is already
// cancelled, which would turn "the server did not answer in time" into "context deadline
// exceeded" and lose the reason.
func attemptBudget(deadline time.Time) time.Duration {
	remaining := time.Until(deadline)
	switch {
	case remaining < probeInterval:
		return probeInterval
	case remaining < probeAttemptBudget:
		return remaining
	default:
		return probeAttemptBudget
	}
}
