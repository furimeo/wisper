package backup

import (
	"context"
	"errors"
	"fmt"
	"math"
	"math/rand/v2"
	"time"
)

// Trying again, and knowing when not to.
//
// An upload to somebody else's object store fails for two completely different reasons and
// the difference decides everything. A 500, a 503, a connection reset or a timeout is the
// network or the far end being briefly unwell, and the right answer is to wait and try the
// same request again. A 403 is the credentials, a 404 is the bucket, a 400 is this code - and
// retrying any of those turns one clear failure into six identical ones a minute apart and a
// backup that reports the wrong cause.
//
// So a failure is only retried when something has explicitly said it is transient. The
// default is not to.

// transientError marks a failure worth trying again.
type transientError struct{ inner error }

func (t transientError) Error() string { return t.inner.Error() }
func (t transientError) Unwrap() error { return t.inner }

// transient wraps an error as worth retrying. Nil stays nil, so a call site can wrap
// unconditionally.
func transient(err error) error {
	if err == nil {
		return nil
	}
	return transientError{inner: err}
}

func isTransient(err error) bool {
	var marker transientError
	return errors.As(err, &marker)
}

// retryPolicy is how many times and how long between.
//
// Bounded, unlike the reconnect schedule in rpc: a control stream must never give up, because
// a node that stopped dialling is invisible, whereas a backup that keeps retrying for an hour
// runs into the next scheduled one and the node ends up doing nothing but backups. Six
// attempts over roughly a minute is enough to ride out a restart at the far end and short
// enough to leave the timeout in the command meaningful.
type retryPolicy struct {
	Attempts int
	Base     time.Duration
	Max      time.Duration

	// Sleep is replaced in tests so a retry schedule costs no wall-clock time. Nil means a
	// real timer that also watches the context.
	Sleep func(ctx context.Context, delay time.Duration) error
}

func defaultRetry() retryPolicy {
	return retryPolicy{Attempts: 6, Base: 500 * time.Millisecond, Max: 20 * time.Second}
}

func (p retryPolicy) withDefaults() retryPolicy {
	if p.Attempts < 1 {
		p.Attempts = 1
	}
	if p.Base <= 0 {
		p.Base = 500 * time.Millisecond
	}
	if p.Max < p.Base {
		p.Max = p.Base
	}
	if p.Sleep == nil {
		p.Sleep = sleep
	}
	return p
}

// do runs attempt until it succeeds, until it fails in a way that will not fix itself, or
// until the attempts run out.
func (p retryPolicy) do(ctx context.Context, what string, attempt func(context.Context) error) error {
	policy := p.withDefaults()

	var last error
	for try := 0; try < policy.Attempts; try++ {
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("backup: %s: %w", what, err)
		}
		last = attempt(ctx)
		if last == nil {
			return nil
		}
		if !isTransient(last) {
			return last
		}
		if try == policy.Attempts-1 {
			break
		}
		if err := policy.Sleep(ctx, policy.delay(try)); err != nil {
			return fmt.Errorf("backup: %s: %w", what, err)
		}
	}
	return fmt.Errorf("backup: %s failed %d times: %w", what, policy.Attempts, last)
}

// delay is exponential with equal jitter: half the window fixed so it cannot collapse towards
// zero, half random so a node running twenty backups at midnight does not retry all of them
// in the same millisecond.
func (p retryPolicy) delay(try int) time.Duration {
	grown := float64(p.Base) * math.Pow(2, float64(try))
	if grown >= float64(p.Max) || math.IsInf(grown, 1) {
		grown = float64(p.Max)
	}
	window := time.Duration(grown)
	half := window / 2
	if half <= 0 {
		return window
	}
	return half + time.Duration(rand.Int64N(int64(half)))
}

func sleep(ctx context.Context, delay time.Duration) error {
	if delay <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
