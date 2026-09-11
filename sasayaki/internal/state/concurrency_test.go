package state

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Everything in the daemon shares one *Store: the reconcile loop, the upload handler, the
// cron runner, the edge and the build worker all write to it at once, and an operator with
// sqlite3 or a second daemon that has not finished exiting can be on the same file. These
// tests are the evidence that the single connection, the immediate transactions and the
// busy timeout add up to writers that queue rather than writers that lose each other's
// work.

// racers runs fn count times at once and fails the test with the first error any of them
// returned.
func racers(t *testing.T, count int, fn func(index int) error) {
	t.Helper()

	start := make(chan struct{})
	failures := make(chan error, count)
	var running sync.WaitGroup
	running.Add(count)

	for i := 0; i < count; i++ {
		go func(index int) {
			defer running.Done()
			<-start
			if err := fn(index); err != nil {
				failures <- err
			}
		}(i)
	}
	close(start)
	running.Wait()
	close(failures)

	for err := range failures {
		t.Fatalf("a concurrent writer failed: %v", err)
	}
}

func TestConcurrentWritersDoNotLoseEachOthersWork(t *testing.T) {
	// MarkApplied reads the row, changes it and writes it back. Without a transaction that
	// takes its write lock at BEGIN, two passes landing together both read the same count and
	// one of them disappears - and a pass counter that undercounts is exactly the signal an
	// operator uses to decide the reconcile loop has stopped ticking.
	store := openStore(t)
	ctx := context.Background()
	const writers = 40

	racers(t, writers, func(index int) error {
		return store.MarkApplied(ctx, uint64(index), noon.Add(time.Duration(index)*time.Second))
	})

	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.Passes != writers {
		t.Fatalf("%d concurrent passes were recorded as %d: an update was lost", writers, converged.Passes)
	}
	if converged.AppliedGeneration != writers-1 {
		t.Fatalf("applied generation = %d, want %d", converged.AppliedGeneration, writers-1)
	}
}

func TestConcurrentUploadsDoNotInterleave(t *testing.T) {
	// Several files uploading at once from one browser, each cut into chunks that arrive out
	// of order. Every session has to end up covered, with nobody else's bytes in it.
	store := openStore(t)
	ctx := context.Background()
	const sessions = 8
	const chunks = 10

	racers(t, sessions, func(index int) error {
		id := fmt.Sprintf("up-race-%d", index)
		if _, err := store.BeginUpload(ctx, sampleSession(id)); err != nil {
			return fmt.Errorf("open %s: %w", id, err)
		}
		// Odd chunks first, then even, so the ranges have to be coalesced from both ends.
		for _, parity := range []int{1, 0} {
			for chunk := parity; chunk < chunks; chunk += 2 {
				run := ByteRange{int64(chunk) * megabyte, int64(chunk+1) * megabyte}
				if _, err := store.RecordChunk(ctx, id, run, noon); err != nil {
					return fmt.Errorf("record %v of %s: %w", run, id, err)
				}
			}
		}
		return nil
	})

	for index := 0; index < sessions; index++ {
		id := fmt.Sprintf("up-race-%d", index)
		progress, err := store.UploadProgress(ctx, id)
		if err != nil {
			t.Fatalf("read the progress of %s: %v", id, err)
		}
		if !progress.Covered {
			t.Fatalf("%s is not covered after all its chunks: %v", id, progress.Received)
		}
		if len(progress.Received) != 1 {
			t.Fatalf("%s came back as %d runs rather than one coalesced range", id, len(progress.Received))
		}
	}
}

func TestReadsAndWritesDoNotBlockEachOtherIntoADeadlock(t *testing.T) {
	// The heartbeat reads the applied generation while the reconcile loop is writing statuses
	// and the edge is writing certificate records. One connection serialises them, and the
	// thing being proved here is that they serialise rather than wait on each other forever.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(1), "publish", noon); err != nil {
		t.Fatalf("save the spec: %v", err)
	}

	racers(t, 24, func(index int) error {
		switch index % 4 {
		case 0:
			return store.SaveCertificate(ctx, issued(fmt.Sprintf("host-%d.example.test", index), noon))
		case 1:
			return store.MarkApplied(ctx, 1, noon)
		case 2:
			_, err := store.SpecGeneration(ctx)
			return err
		default:
			_, err := store.Certificates(ctx)
			return err
		}
	})

	generation, err := store.SpecGeneration(ctx)
	if err != nil {
		t.Fatalf("read the generation: %v", err)
	}
	if generation != 1 {
		t.Fatalf("generation = %d, want 1", generation)
	}
}

func TestTheHighestGenerationWinsARace(t *testing.T) {
	// Reconnects on a flapping tunnel deliver retried frames out of order. Whichever order
	// they land in, the newest spec must be the one on disk and it must be intact: refusals
	// are routine, a torn document is not.
	store := openStore(t)
	ctx := context.Background()
	const generations = 16

	var refused int64
	var counting sync.Mutex
	racers(t, generations, func(index int) error {
		err := store.SaveSpec(ctx, sampleSpec(uint64(index+1)), "reconnect", noon)
		if errors.Is(err, ErrSupersededGeneration) {
			counting.Lock()
			refused++
			counting.Unlock()
			return nil
		}
		return err
	})

	stored, err := store.LoadSpec(ctx)
	if err != nil {
		// LoadSpec verifies the payload against its checksum, so this also catches a spec
		// stitched together from two writers.
		t.Fatalf("load the spec after the race: %v", err)
	}
	if stored.Generation != generations {
		t.Fatalf("generation = %d after the race, want the highest offered, %d", stored.Generation, generations)
	}
	if refused == 0 {
		t.Log("no frame arrived out of order in this run; the ordering rule is proved by TestSaveSpecRefusesAnOlderGeneration")
	}
}

func TestTwoProcessesOnOneFileQueueRatherThanFail(t *testing.T) {
	// An upgrade restarts the daemon while the old one is still shutting down, and for a
	// moment two processes hold the same file. The busy timeout is what turns that into a
	// wait instead of "database is locked" in a log nobody reads.
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()
	const passesEach = 20

	outgoing := openStoreAt(t, path)
	incoming := openStoreAt(t, path)

	racers(t, 2*passesEach, func(index int) error {
		store := outgoing
		if index%2 == 1 {
			store = incoming
		}
		return store.MarkApplied(ctx, uint64(index), noon)
	})

	converged, err := incoming.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.Passes != 2*passesEach {
		t.Fatalf("%d passes across two processes were recorded as %d", 2*passesEach, converged.Passes)
	}

	// And what one process wrote is visible to the other without reopening the file.
	if err := outgoing.SaveSpec(ctx, sampleSpec(5), "publish", noon); err != nil {
		t.Fatalf("save a spec from the first handle: %v", err)
	}
	generation, err := incoming.SpecGeneration(ctx)
	if err != nil {
		t.Fatalf("read the generation from the second handle: %v", err)
	}
	if generation != 5 {
		t.Fatalf("the second handle sees generation %d, want 5", generation)
	}
}

func TestConcurrentBuildsOfTheSameIdProduceExactlyOneRun(t *testing.T) {
	// The panel resends StartBuild after a dropped stream, and two frames can be in flight at
	// once. Exactly one may win, or the node clones and compiles the same commit twice on a
	// machine that has customers on it.
	store := openStore(t)
	ctx := context.Background()
	const attempts = 12

	var accepted int64
	var counting sync.Mutex
	racers(t, attempts, func(int) error {
		err := store.BeginBuild(ctx, startedBuild("b-race", "wl-site", noon))
		switch {
		case err == nil:
			counting.Lock()
			accepted++
			counting.Unlock()
			return nil
		case errors.Is(err, ErrAlreadyExists):
			return nil
		default:
			return err
		}
	})

	if accepted != 1 {
		t.Fatalf("%d of %d concurrent starts were accepted, want exactly 1", accepted, attempts)
	}
	runs, err := store.Builds(ctx, "wl-site", 0)
	if err != nil {
		t.Fatalf("list the builds: %v", err)
	}
	if len(runs) != 1 {
		t.Fatalf("%d rows for one build id", len(runs))
	}
}
