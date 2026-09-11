package backup

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

// What is worth trying again and what is not. Getting this wrong in one direction turns a
// domestic uplink into a failed backup every night; getting it wrong in the other turns one
// clear "your credentials are wrong" into six identical ones a minute apart.

func TestOnlyTransientFailuresAreTriedAgain(t *testing.T) {
	policy := retryPolicy{Attempts: 4, Sleep: noSleep}

	t.Run("a transient failure is retried until it works", func(t *testing.T) {
		attempts := 0
		err := policy.do(context.Background(), "upload", func(context.Context) error {
			attempts++
			if attempts < 3 {
				return transient(errors.New("the store is having a moment"))
			}
			return nil
		})
		if err != nil {
			t.Fatalf("the third attempt worked and the call still failed: %v", err)
		}
		if attempts != 3 {
			t.Errorf("made %d attempts, want 3", attempts)
		}
	})

	t.Run("a permanent failure is not", func(t *testing.T) {
		attempts := 0
		err := policy.do(context.Background(), "upload", func(context.Context) error {
			attempts++
			return errors.New("the access key is not one this bucket knows")
		})
		if err == nil {
			t.Fatal("a permanent failure was reported as success")
		}
		if attempts != 1 {
			t.Errorf("made %d attempts at something that will never work, want 1", attempts)
		}
	})

	t.Run("attempts run out", func(t *testing.T) {
		attempts := 0
		err := policy.do(context.Background(), "upload", func(context.Context) error {
			attempts++
			return transient(errors.New("still unwell"))
		})
		if err == nil {
			t.Fatal("a failure that never resolved was reported as success")
		}
		if attempts != 4 {
			t.Errorf("made %d attempts, want the 4 the policy allows", attempts)
		}
	})
}

func TestTheStatusCodesWorthWaitingFor(t *testing.T) {
	for status, want := range map[int]bool{
		http.StatusOK:                  false,
		http.StatusBadRequest:          false,
		http.StatusForbidden:           false,
		http.StatusNotFound:            false,
		http.StatusConflict:            false,
		http.StatusRequestTimeout:      true,
		http.StatusTooManyRequests:     true,
		http.StatusInternalServerError: true,
		http.StatusBadGateway:          true,
		http.StatusServiceUnavailable:  true,
		http.StatusGatewayTimeout:      true,
	} {
		if got := retryableStatus(status); got != want {
			t.Errorf("status %d: retryable=%v, want %v", status, got, want)
		}
	}
}

// A cancelled context stops the schedule rather than sleeping through it, so a daemon being
// stopped does not wait out a twenty-second backoff before it exits.
func TestARetryScheduleStopsWhenTheCommandIsCancelled(t *testing.T) {
	cancelled, stop := context.WithCancel(context.Background())
	stop()

	attempts := 0
	err := retryPolicy{Attempts: 4}.do(cancelled, "upload", func(context.Context) error {
		attempts++
		return transient(errors.New("unwell"))
	})
	if err == nil {
		t.Fatal("a cancelled retry reported success")
	}
	if !errors.Is(err, context.Canceled) {
		t.Errorf("the failure is %v, and it should say the command was cancelled", err)
	}
	if attempts != 0 {
		t.Errorf("made %d attempts after the command was cancelled", attempts)
	}
}

// The failure the store reports is the one the caller sees, not "failed 6 times".
func TestTheLastFailureIsWrappedRatherThanReplaced(t *testing.T) {
	sentinel := errors.New("the bucket is full")

	err := retryPolicy{Attempts: 2, Sleep: noSleep}.do(context.Background(), "upload",
		func(context.Context) error { return transient(sentinel) })

	if !errors.Is(err, sentinel) {
		t.Errorf("the failure is %v and does not carry what the store said", err)
	}
}
