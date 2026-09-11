package state

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// observed is one workload's status with every field a restart has to preserve.
func observed(id string, phase wisperpb.WorkloadPhase, restarts int32) *wisperpb.WorkloadStatus {
	return &wisperpb.WorkloadStatus{
		WorkloadId:       id,
		Phase:            phase,
		ContainerId:      "c-" + id,
		ImageDigest:      "sha256:9ab1",
		RestartCount:     restarts,
		ExitCode:         137,
		Message:          "killed for exceeding its memory limit",
		StartedAt:        timestamppb.New(noon.Add(-time.Hour)),
		LastTransitionAt: timestamppb.New(noon),
		Runtime:          wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC,
		ReleaseId:        "dep-1042",
	}
}

func TestWorkloadStatusesRoundTripWholeAndSorted(t *testing.T) {
	// The phase a workload is in is the difference between "crash-looping for an hour" and
	// "just started", and the restart count is what says which. Both are lost on every
	// upgrade unless the whole message survives.
	store := openStore(t)
	ctx := context.Background()
	batch := []*wisperpb.WorkloadStatus{
		observed("wl-site", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0),
		observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_CRASH_LOOPING, 14),
	}

	if err := store.SaveWorkloadStatuses(ctx, batch, noon); err != nil {
		t.Fatalf("save the batch: %v", err)
	}

	stored, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the statuses: %v", err)
	}
	if len(stored) != 2 {
		t.Fatalf("read %d statuses, want 2", len(stored))
	}
	// Sorted by workload id, so a status batch is byte-for-byte the same between two passes
	// that observed the same thing.
	if stored[0].Status.GetWorkloadId() != "wl-api" || stored[1].Status.GetWorkloadId() != "wl-site" {
		t.Fatalf("statuses came back in the order %s, %s",
			stored[0].Status.GetWorkloadId(), stored[1].Status.GetWorkloadId())
	}
	if !proto.Equal(stored[0].Status, batch[1]) {
		t.Fatalf("the status of wl-api did not survive:\n got %v\nwant %v", stored[0].Status, batch[1])
	}
	if !stored[0].ObservedAt.Equal(noon) {
		t.Fatalf("observed at %s, want %s", stored[0].ObservedAt, noon)
	}
}

func TestSavingAStatusAgainReplacesIt(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	later := noon.Add(30 * time.Second)

	if err := store.SaveWorkloadStatuses(ctx,
		[]*wisperpb.WorkloadStatus{observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_STARTING, 0)}, noon); err != nil {
		t.Fatalf("save the first observation: %v", err)
	}
	if err := store.SaveWorkloadStatuses(ctx,
		[]*wisperpb.WorkloadStatus{observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 1)}, later); err != nil {
		t.Fatalf("save the second observation: %v", err)
	}

	one, err := store.WorkloadStatus(ctx, "wl-api")
	if err != nil {
		t.Fatalf("read the status: %v", err)
	}
	if one.Status.GetPhase() != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("phase = %s, want RUNNING", one.Status.GetPhase())
	}
	if !one.ObservedAt.Equal(later) {
		t.Fatalf("observed at %s, want %s", one.ObservedAt, later)
	}

	all, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the statuses: %v", err)
	}
	if len(all) != 1 {
		t.Fatalf("re-observing one workload produced %d rows", len(all))
	}
}

func TestAnEmptyBatchKeepsWhatWasAlreadyKnown(t *testing.T) {
	// A pass on which Docker did not answer must not look like a pass that found nothing. The
	// caller keeps the previous rows by not calling this, so an empty batch has to be a
	// no-op rather than a truncation.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveWorkloadStatuses(ctx,
		[]*wisperpb.WorkloadStatus{observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0)}, noon); err != nil {
		t.Fatalf("save the batch: %v", err)
	}
	if err := store.SaveWorkloadStatuses(ctx, nil, noon.Add(time.Minute)); err != nil {
		t.Fatalf("save an empty batch: %v", err)
	}

	all, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the statuses: %v", err)
	}
	if len(all) != 1 {
		t.Fatalf("an empty batch left %d statuses, want 1", len(all))
	}
}

func TestABatchIsWrittenWholeOrNotAtAll(t *testing.T) {
	// A pass observed the machine at a moment. Half a pass on disk describes a machine that
	// never existed.
	store := openStore(t)
	ctx := context.Background()

	err := store.SaveWorkloadStatuses(ctx, []*wisperpb.WorkloadStatus{
		observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0),
		{WorkloadId: ""},
	}, noon)
	if err == nil {
		t.Fatal("a status with no workload id was accepted")
	}

	all, readErr := store.WorkloadStatuses(ctx)
	if readErr != nil {
		t.Fatalf("read the statuses: %v", readErr)
	}
	if len(all) != 0 {
		t.Fatalf("the failed batch left %d rows behind", len(all))
	}
}

func TestAnUnknownWorkloadIsNotFound(t *testing.T) {
	_, err := openStore(t).WorkloadStatus(context.Background(), "wl-ghost")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("an unobserved workload returned %v, want ErrNotFound", err)
	}
}

func TestPruningKeepsOnlyTheWorkloadsInTheSpec(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveWorkloadStatuses(ctx, []*wisperpb.WorkloadStatus{
		observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0),
		observed("wl-site", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0),
		observed("wl-gone", wisperpb.WorkloadPhase_WORKLOAD_PHASE_STOPPED, 0),
	}, noon); err != nil {
		t.Fatalf("save the batch: %v", err)
	}

	removed, err := store.PruneWorkloadStatuses(ctx, []string{"wl-api", "wl-site"})
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("pruned %d statuses, want 1", removed)
	}

	all, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the statuses: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("%d statuses survived the prune, want 2", len(all))
	}
}

func TestPruningWithNothingToKeepEmptiesTheTable(t *testing.T) {
	// A spec with no workloads means a node with no workloads, and a drained node has to be
	// able to reach that state.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveWorkloadStatuses(ctx,
		[]*wisperpb.WorkloadStatus{observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0)}, noon); err != nil {
		t.Fatalf("save the batch: %v", err)
	}
	removed, err := store.PruneWorkloadStatuses(ctx, nil)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("pruned %d statuses, want 1", removed)
	}

	all, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the statuses: %v", err)
	}
	if len(all) != 0 {
		t.Fatalf("%d statuses survived an empty keep list", len(all))
	}
}
