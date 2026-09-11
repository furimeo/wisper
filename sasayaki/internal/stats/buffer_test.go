package stats

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The ring, and the claim that matters about it: a node whose panel has been unreachable
// since Friday must not have spent the weekend filling the disk this package exists to
// protect.

func openTestBuffer(t *testing.T, capacity int) *buffer {
	t.Helper()
	ring, err := openBuffer(t.Context(), filepath.Join(t.TempDir(), BufferFileName), capacity)
	if err != nil {
		t.Fatalf("open the buffer: %v", err)
	}
	t.Cleanup(func() { ring.Close() })
	return ring
}

// numbered is a sample identifiable by the workload id it carries, so a test can say which
// ones survived.
func numbered(index int) *wisperpb.StatSample {
	return &wisperpb.StatSample{
		NodeId:        "node-under-test",
		TakenAt:       timestamppb.New(time.Unix(1_700_000_000+int64(index), 0)),
		IntervalNanos: int64(12 * time.Second),
		Subject: &wisperpb.StatSample_Workload{Workload: &wisperpb.WorkloadSample{
			WorkloadId: "workload-" + decimal(int64(index)),
			CpuNanos:   int64(index),
		}},
	}
}

func count(t *testing.T, ring *buffer) int {
	t.Helper()
	total, err := ring.count(t.Context())
	if err != nil {
		t.Fatalf("count the buffer: %v", err)
	}
	return total
}

// An outage long enough to produce eighty times the ring's capacity. The bound holds,
// and what survives is the recent end - a chart with a hole in last Friday is worth more than
// one with a hole in the last ten minutes.
func TestTheBufferStaysBoundedThroughALongOutage(t *testing.T) {
	const capacity = 64
	ring := openTestBuffer(t, capacity)

	produced := 0
	for pass := range 400 {
		batch := make([]*wisperpb.StatSample, 0, 13)
		for range 13 {
			batch = append(batch, numbered(produced))
			produced++
		}
		if err := ring.append(t.Context(), batch); err != nil {
			t.Fatalf("buffer pass %d: %v", pass, err)
		}
		if held := count(t, ring); held > capacity {
			t.Fatalf("after pass %d the buffer holds %d samples, and its bound is %d: an outage that "+
				"grows the ring is an outage that fills the disk", pass, held, capacity)
		}
	}

	if held := count(t, ring); held != capacity {
		t.Fatalf("the buffer holds %d samples after %d were produced, want it full at %d",
			held, produced, capacity)
	}

	waiting, err := ring.take(t.Context(), capacity)
	if err != nil {
		t.Fatalf("read the buffer: %v", err)
	}
	oldestKept := waiting[0].sample.GetWorkload().GetCpuNanos()
	newestKept := waiting[len(waiting)-1].sample.GetWorkload().GetCpuNanos()
	if newestKept != int64(produced-1) {
		t.Fatalf("the newest sample held is %d, want the last one produced, %d: the ring is dropping "+
			"the wrong end", newestKept, produced-1)
	}
	if oldestKept != int64(produced-capacity) {
		t.Fatalf("the oldest sample held is %d, want %d", oldestKept, produced-capacity)
	}
}

// The file is what makes a daemon restart during an outage cost nothing.
func TestTheBufferSurvivesTheProcess(t *testing.T) {
	path := filepath.Join(t.TempDir(), BufferFileName)

	first, err := openBuffer(t.Context(), path, 32)
	if err != nil {
		t.Fatalf("open the buffer: %v", err)
	}
	if err := first.append(t.Context(), []*wisperpb.StatSample{numbered(1), numbered(2)}); err != nil {
		t.Fatalf("buffer two samples: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("close the buffer: %v", err)
	}

	second, err := openBuffer(t.Context(), path, 32)
	if err != nil {
		t.Fatalf("reopen the buffer: %v", err)
	}
	defer second.Close()

	if held := count(t, second); held != 2 {
		t.Fatalf("a reopened buffer holds %d samples, want the 2 written before the restart", held)
	}
}

// Reading and deleting are separate on purpose: a row removed before the panel accepted it is
// a row lost to the disconnection that was about to happen.
func TestTakeLeavesTheSamplesUntilTheyAreDiscarded(t *testing.T) {
	ring := openTestBuffer(t, 32)
	if err := ring.append(t.Context(), []*wisperpb.StatSample{numbered(1), numbered(2), numbered(3)}); err != nil {
		t.Fatalf("buffer three samples: %v", err)
	}

	waiting, err := ring.take(t.Context(), 2)
	if err != nil {
		t.Fatalf("read the buffer: %v", err)
	}
	if len(waiting) != 2 {
		t.Fatalf("took %d samples, want 2", len(waiting))
	}
	if got := waiting[0].sample.GetWorkload().GetCpuNanos(); got != 1 {
		t.Fatalf("the first sample taken is %d, want the oldest, 1", got)
	}
	if held := count(t, ring); held != 3 {
		t.Fatalf("taking samples removed them: %d held, want 3", held)
	}

	// Only the first is acknowledged, standing in for a stream that died halfway through.
	if err := ring.discard(t.Context(), []int64{waiting[0].id}); err != nil {
		t.Fatalf("discard one sample: %v", err)
	}
	if held := count(t, ring); held != 2 {
		t.Fatalf("%d samples held after one was delivered, want 2", held)
	}

	remaining, err := ring.take(t.Context(), 10)
	if err != nil {
		t.Fatalf("read the buffer: %v", err)
	}
	if got := remaining[0].sample.GetWorkload().GetCpuNanos(); got != 2 {
		t.Fatalf("the next sample is %d, want 2: what was not acknowledged has to be offered again", got)
	}
}

// A row this build cannot decode was written by another one. Leaving it at the head of the
// queue would block every sample behind it forever.
func TestAnUndecodableRowIsDroppedRatherThanBlocking(t *testing.T) {
	ring := openTestBuffer(t, 32)
	if err := ring.append(t.Context(), []*wisperpb.StatSample{numbered(1)}); err != nil {
		t.Fatalf("buffer a sample: %v", err)
	}
	if _, err := ring.db.ExecContext(t.Context(),
		`INSERT INTO stat_buffer (taken_at, payload) VALUES (?, ?)`,
		int64(1), []byte{0xff, 0xff, 0xff, 0xff}); err != nil {
		t.Fatalf("plant an undecodable row: %v", err)
	}
	if err := ring.append(t.Context(), []*wisperpb.StatSample{numbered(2)}); err != nil {
		t.Fatalf("buffer another sample: %v", err)
	}

	waiting, err := ring.take(t.Context(), 10)
	if err != nil {
		t.Fatalf("read the buffer: %v", err)
	}
	if len(waiting) != 2 {
		t.Fatalf("took %d samples, want the 2 that decode", len(waiting))
	}
	if held := count(t, ring); held != 2 {
		t.Fatalf("%d rows left, want the undecodable one gone and the 2 readable ones kept", held)
	}
}

// A cache of statistics that will not open is not a reason to refuse to run a node.
func TestAnUnreadableBufferIsReplacedRatherThanFatal(t *testing.T) {
	path := filepath.Join(t.TempDir(), BufferFileName)
	if err := os.WriteFile(path, []byte("this is not a database"), 0o600); err != nil {
		t.Fatalf("plant a corrupt file: %v", err)
	}

	ring, err := openBuffer(t.Context(), path, 16)
	if err != nil {
		t.Fatalf("open a buffer over a corrupt file: %v", err)
	}
	defer ring.Close()

	if err := ring.append(t.Context(), []*wisperpb.StatSample{numbered(1)}); err != nil {
		t.Fatalf("the replacement buffer will not take a sample: %v", err)
	}
	if held := count(t, ring); held != 1 {
		t.Fatalf("the replacement buffer holds %d samples, want 1", held)
	}
}

func TestAppendingNothingIsNotAWrite(t *testing.T) {
	ring := openTestBuffer(t, 4)
	if err := ring.append(context.Background(), nil); err != nil {
		t.Fatalf("appending an empty batch failed: %v", err)
	}
	if held := count(t, ring); held != 0 {
		t.Fatalf("%d rows written for an empty batch", held)
	}
}
