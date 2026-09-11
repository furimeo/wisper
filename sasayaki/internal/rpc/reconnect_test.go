package rpc

import (
	"testing"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestBackoffGrowsUntilItReachesTheCeiling(t *testing.T) {
	// The identity jitter turns the schedule into the windows themselves, so the shape
	// is assertable. equalJitter is checked separately below.
	clock := newRetryClock(Backoff{
		Base:   time.Second,
		Max:    8 * time.Second,
		Factor: 2,
		Jitter: func(window time.Duration) time.Duration { return window },
	})

	want := []time.Duration{
		1 * time.Second,
		2 * time.Second,
		4 * time.Second,
		8 * time.Second,
		8 * time.Second,
		8 * time.Second,
	}
	for index, expected := range want {
		if got := clock.next(); got != expected {
			t.Fatalf("delay %d = %s, want %s", index+1, got, expected)
		}
	}

	// A stream that worked puts the node back at the bottom: an hour of success must
	// not be paid for with a minute of waiting.
	clock.reset()
	if got := clock.next(); got != time.Second {
		t.Errorf("after a working stream the delay was %s, want %s", got, time.Second)
	}
}

func TestPenaliseGoesStraightToTheCeiling(t *testing.T) {
	clock := newRetryClock(Backoff{
		Base:   time.Second,
		Max:    time.Minute,
		Factor: 2,
		Jitter: func(window time.Duration) time.Duration { return window },
	})

	clock.penalise()
	if got := clock.next(); got != time.Minute {
		t.Errorf("delay after a refusal = %s, want the ceiling %s", got, time.Minute)
	}
	if got := clock.next(); got != time.Minute {
		t.Errorf("second delay = %s, want the ceiling %s", got, time.Minute)
	}
}

func TestEqualJitterSpreadsWithoutCollapsing(t *testing.T) {
	const window = time.Second
	distinct := make(map[time.Duration]struct{})
	for range 200 {
		delay := equalJitter(window)
		if delay < window/2 || delay >= window {
			t.Fatalf("jittered delay %s is outside [%s, %s)", delay, window/2, window)
		}
		distinct[delay] = struct{}{}
	}
	if len(distinct) < 50 {
		t.Errorf("only %d distinct delays in 200: a fleet reconnecting together would not spread out", len(distinct))
	}
}

// The pin exists for exactly this: the endpoint answers, the handshake completes at the
// TLS layer, and it is somebody else.
func TestAChangedCertificateIsRefusedAndSaidSo(t *testing.T) {
	panel := startPanel(t)

	_, otherFingerprint := selfSignedCertificate(t)
	credential := panel.credential()
	credential.PanelCertificateSHA256 = otherFingerprint

	node := newFakeNode()
	client, logs := startNode(t, credential, node)

	waitFor(t, "the node to refuse the certificate twice", func() bool {
		return logs.count("not the one this node pinned") >= 2
	})

	if client.Connected() {
		t.Fatal("the node connected to a panel presenting a certificate it never pinned")
	}
	if panel.connectAttempts.Load() != 0 {
		t.Errorf("%d streams reached the panel: the credential must never leave the node "+
			"once the certificate does not match", panel.connectAttempts.Load())
	}
	if !logs.contains("re-enrol") {
		t.Errorf("the log does not tell an operator what to do about it:\n%s", logs.dump())
	}
}

// A panel that accepts a connection and immediately drops it is the worst case for a
// reconnect loop: nothing fails fast enough to look like an error, and a loop with no
// backoff turns a restarting panel into a denial of service by its own nodes.
func TestReconnectStormBacksOff(t *testing.T) {
	panel := startPanel(t)
	panel.connectStatus = status.Error(codes.Unavailable, "the panel is restarting")

	node := newFakeNode()
	startNode(t, panel.credential(), node)

	waitFor(t, "the node to retry at all", func() bool {
		return panel.connectAttempts.Load() >= 3
	})

	time.Sleep(500 * time.Millisecond)
	attempts := panel.connectAttempts.Load()

	// With Base 10ms doubling to a 80ms ceiling, half a second is worth roughly ten
	// attempts. A loop with no backoff would be in the hundreds.
	if attempts > 40 {
		t.Errorf("%d attempts: the node is hammering the panel rather than backing off", attempts)
	}
	if attempts < 3 {
		t.Errorf("%d attempts: the node stopped trying", attempts)
	}
}
