package state

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func startedRestore(id string, at time.Time) RestoreRun {
	return RestoreRun{
		RestoreID:      id,
		RestorePointID: "rp-2026-03-04",
		Kind:           wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectID:      "vol-data",
		WorkloadID:     "wl-api",
		StopWorkload:   true,
		StartedAt:      at,
		Detail:         "fetching the archive",
	}
}

func TestBeginRestoreRecordsWhatItIsAbout(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginRestore(ctx, startedRestore("rs-1", noon)); err != nil {
		t.Fatalf("begin the restore: %v", err)
	}
	run, err := store.Restore(ctx, "rs-1")
	if err != nil {
		t.Fatalf("read the restore: %v", err)
	}
	if run.Finished || run.Result != nil {
		t.Fatalf("a restore that has just started reads as finished=%v result=%v", run.Finished, run.Result)
	}
	// A crash mid-restore leaves the workload stopped, and whoever cleans up has to know that
	// was intentional.
	if !run.StopWorkload {
		t.Fatal("the restore forgot that it had stopped the workload")
	}
	if run.DryRun {
		t.Fatal("a real restore was recorded as a dry run")
	}
	if run.RestorePointID != "rp-2026-03-04" {
		t.Fatalf("restore point = %q", run.RestorePointID)
	}
}

func TestADryRunIsRecordedAsOne(t *testing.T) {
	// Restoring alongside instead of over the top is what makes testing a restore safe enough
	// that people do it, and an operator reading the history has to be able to tell which
	// kind they are looking at.
	store := openStore(t)
	ctx := context.Background()

	trial := startedRestore("rs-2", noon)
	trial.DryRun = true
	trial.StopWorkload = false
	if err := store.BeginRestore(ctx, trial); err != nil {
		t.Fatalf("begin the dry run: %v", err)
	}

	run, err := store.Restore(ctx, "rs-2")
	if err != nil {
		t.Fatalf("read the restore: %v", err)
	}
	if !run.DryRun || run.StopWorkload {
		t.Fatalf("dry run=%v stop workload=%v, want true and false", run.DryRun, run.StopWorkload)
	}
}

func TestBeginRestoreTwiceIsRefused(t *testing.T) {
	// Idempotency matters more here than anywhere else in the package: a resent
	// RestoreBackup that ran twice would overwrite a volume the first run had already put
	// back, possibly while an application was reading it.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginRestore(ctx, startedRestore("rs-3", noon)); err != nil {
		t.Fatalf("begin the restore: %v", err)
	}
	err := store.BeginRestore(ctx, startedRestore("rs-3", noon.Add(time.Minute)))
	if !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("starting the same restore twice returned %v, want ErrAlreadyExists", err)
	}
}

func TestBeginRestoreValidatesItsIdentifiers(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginRestore(ctx, RestoreRun{RestorePointID: "rp-1"}); err == nil {
		t.Fatal("a restore with no id was recorded")
	}
	if err := store.BeginRestore(ctx, RestoreRun{RestoreID: "rs-4"}); err == nil {
		t.Fatal("a restore that names no restore point was recorded")
	}
}

func TestRestoreProgressIsWhatACustomerWatches(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	later := noon.Add(90 * time.Second)

	if err := store.BeginRestore(ctx, startedRestore("rs-5", noon)); err != nil {
		t.Fatalf("begin the restore: %v", err)
	}
	if err := store.RecordRestoreProgress(ctx, "rs-5", later, "18 of 40 GiB written"); err != nil {
		t.Fatalf("record the progress: %v", err)
	}

	run, err := store.Restore(ctx, "rs-5")
	if err != nil {
		t.Fatalf("read the restore: %v", err)
	}
	if run.Detail != "18 of 40 GiB written" {
		t.Fatalf("detail = %q", run.Detail)
	}
	if !run.UpdatedAt.Equal(later) {
		t.Fatalf("updated at %s, want %s", run.UpdatedAt, later)
	}

	if err := store.RecordRestoreProgress(ctx, "rs-5", later, ""); err == nil {
		t.Fatal("progress with no detail was recorded, which says nothing")
	}
	err = store.RecordRestoreProgress(ctx, "rs-ghost", later, "something")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("progress against an unknown restore returned %v, want ErrNotFound", err)
	}
}

func TestProgressAgainstAFinishedRestoreIsRefused(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginRestore(ctx, startedRestore("rs-6", noon)); err != nil {
		t.Fatalf("begin the restore: %v", err)
	}
	if err := store.FinishRestore(ctx, "rs-6",
		&wisperpb.RestoreCompleted{RestoreId: "rs-6", Success: true}, noon.Add(time.Minute)); err != nil {
		t.Fatalf("finish the restore: %v", err)
	}
	err := store.RecordRestoreProgress(ctx, "rs-6", noon.Add(2*time.Minute), "still going")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("progress against a finished restore returned %v, want ErrNotFound", err)
	}
}

func TestFinishRestoreStoresTheResultWhole(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	finished := noon.Add(11 * time.Minute)

	if err := store.BeginRestore(ctx, startedRestore("rs-7", noon)); err != nil {
		t.Fatalf("begin the restore: %v", err)
	}
	if err := store.FinishRestore(ctx, "rs-7", &wisperpb.RestoreCompleted{
		RestoreId:         "rs-7",
		Success:           true,
		BytesRestored:     40 << 30,
		RestoredTo:        "vol-data",
		WorkloadRestarted: true,
		Detail:            "restored and restarted",
	}, finished); err != nil {
		t.Fatalf("finish the restore: %v", err)
	}

	run, err := store.Restore(ctx, "rs-7")
	if err != nil {
		t.Fatalf("read the restore: %v", err)
	}
	if !run.Finished || !run.Success {
		t.Fatalf("finished=%v success=%v after a successful restore", run.Finished, run.Success)
	}
	if !run.FinishedAt.Equal(finished) {
		t.Fatalf("finished at %s, want %s", run.FinishedAt, finished)
	}
	if !run.Result.GetWorkloadRestarted() || run.Result.GetBytesRestored() != 40<<30 {
		t.Fatalf("the stored result lost detail: %v", run.Result)
	}
}

func TestFinishingAnUnknownRestoreOrWithoutAResult(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.FinishRestore(ctx, "rs-8", nil, noon); err == nil {
		t.Fatal("a restore finished with no result")
	}
	err := store.FinishRestore(ctx, "rs-ghost", &wisperpb.RestoreCompleted{RestoreId: "rs-ghost"}, noon)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("finishing an unknown restore returned %v, want ErrNotFound", err)
	}
	if _, err := store.Restore(ctx, "rs-ghost"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("reading an unknown restore returned %v, want ErrNotFound", err)
	}
}

func TestUnfinishedRestoresAreHalfWrittenVolumes(t *testing.T) {
	// Each one is a volume that may be half written and an application that may still be
	// down with nothing on any screen explaining why.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginRestore(ctx, startedRestore("rs-done", noon)); err != nil {
		t.Fatalf("begin the finished restore: %v", err)
	}
	if err := store.FinishRestore(ctx, "rs-done",
		&wisperpb.RestoreCompleted{RestoreId: "rs-done", Success: true}, noon); err != nil {
		t.Fatalf("finish the restore: %v", err)
	}
	inflight := startedRestore("rs-inflight", noon.Add(time.Minute))
	if err := store.BeginRestore(ctx, inflight); err != nil {
		t.Fatalf("begin the in-flight restore: %v", err)
	}

	unfinished, err := store.UnfinishedRestores(ctx)
	if err != nil {
		t.Fatalf("list the unfinished restores: %v", err)
	}
	if len(unfinished) != 1 || unfinished[0].RestoreID != "rs-inflight" {
		t.Fatalf("unfinished restores = %+v, want only rs-inflight", unfinished)
	}
	if !unfinished[0].StopWorkload {
		t.Fatal("the in-flight restore forgot that it had stopped the workload")
	}
}

func TestRestoresAreListedNewestFirstAndFilteredBySubject(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for i, id := range []string{"rs-a", "rs-b", "rs-c"} {
		run := startedRestore(id, noon.Add(time.Duration(i)*time.Hour))
		if err := store.BeginRestore(ctx, run); err != nil {
			t.Fatalf("begin %s: %v", id, err)
		}
	}
	other := startedRestore("rs-other", noon)
	other.SubjectID = "grant-9"
	other.Kind = wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE
	if err := store.BeginRestore(ctx, other); err != nil {
		t.Fatalf("begin the database restore: %v", err)
	}

	history, err := store.Restores(ctx, "vol-data", 0)
	if err != nil {
		t.Fatalf("list the history: %v", err)
	}
	if len(history) != 3 || history[0].RestoreID != "rs-c" {
		t.Fatalf("history = %+v, want three attempts newest first", history)
	}
	limited, err := store.Restores(ctx, "", 2)
	if err != nil {
		t.Fatalf("list every subject: %v", err)
	}
	if len(limited) != 2 {
		t.Fatalf("the limit produced %d rows, want 2", len(limited))
	}
}
