package rpc

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"
)

// startNode runs a real Client against the stub panel and stops it when the test ends.
//
// The reconnect schedule is compressed to milliseconds. That is not a shortcut around
// the behaviour under test - the schedule still doubles, still has a floor and still
// has a ceiling - it just means a test about backoff finishes in a second rather than
// in a minute.
func startNode(t *testing.T, credential Credential, node *fakeNode, options ...Option) (*Client, *logCapture) {
	t.Helper()

	logger, logs := captureLogs()
	settings := append([]Option{
		WithLogger(logger),
		WithBackoff(Backoff{Base: 10 * time.Millisecond, Max: 80 * time.Millisecond, Factor: 2}),
		WithUplinkRotation(200 * time.Millisecond),
	}, options...)

	client, err := New(credential, node.handlers(), settings...)
	if err != nil {
		t.Fatalf("new client: %v", err)
	}

	ctx, stop := context.WithCancel(context.Background())
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		_ = client.Run(ctx)
	}()
	t.Cleanup(func() {
		stop()
		select {
		case <-finished:
		case <-time.After(howLong):
			t.Error("Run did not return after its context was cancelled")
		}
		_ = client.Close()
	})

	return client, logs
}

// logCapture is how a test asserts on something whose only external effect is a log
// line - a refused certificate, for instance, which by design never reaches the panel.
type logCapture struct {
	mu    sync.Mutex
	lines []string
}

func captureLogs() (*slog.Logger, *logCapture) {
	capture := &logCapture{}
	return slog.New(slog.NewTextHandler(capture, &slog.HandlerOptions{Level: slog.LevelDebug})), capture
}

func (c *logCapture) Write(line []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.lines = append(c.lines, string(line))
	return len(line), nil
}

func (c *logCapture) count(text string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	seen := 0
	for _, line := range c.lines {
		if strings.Contains(line, text) {
			seen++
		}
	}
	return seen
}

func (c *logCapture) contains(text string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, line := range c.lines {
		if strings.Contains(line, text) {
			return true
		}
	}
	return false
}

func (c *logCapture) dump() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return strings.Join(c.lines, "")
}

// waitFor polls until the condition holds, so a test asserting on something that
// happens on another goroutine does not have to guess how long it takes.
func waitFor(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(howLong)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// receive takes one value from a channel or fails the test.
func receive[T any](t *testing.T, from chan T, what string) T {
	t.Helper()
	select {
	case value := <-from:
		return value
	case <-time.After(howLong):
		t.Fatalf("nothing arrived: %s", what)
		var zero T
		return zero
	}
}
