package state

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func startedBackup(id, subject string, at time.Time) BackupRun {
	return BackupRun{
		BackupID:   id,
		Kind:       wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectID:  subject,
		WorkloadID: "wl-api",
		Stage:      wisperpb.BackupStage_BACKUP_STAGE_QUIESCE,
		StartedAt:  at,
		Detail:     "pausing writes",
	}
}

func TestBeginBackupRecordsAnUnfinishedRun(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, startedBackup("bk-1", "vol-data", noon)); err != nil {
		t.Fatalf("begin the backup: %v", err)
	}
	run, err := store.Backup(ctx, "bk-1")
	if err != nil {
		t.Fatalf("read the backup: %v", err)
	}
	if run.Finished || run.Result != nil {
		t.Fatalf("a backup that has just started reads as finished=%v result=%v", run.Finished, run.Result)
	}
	if run.Kind != wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME {
		t.Fatalf("kind = %s, want VOLUME", run.Kind)
	}
	// Minted by the node once the archive has a final name, so it is empty until then.
	if run.RestorePointID != "" || run.Location != "" {
		t.Fatalf("a running backup already has a restore point %q at %q", run.RestorePointID, run.Location)
	}
}

func TestBeginBackupTwiceIsRefused(t *testing.T) {
	// backup.proto: a repeated command with the same id is the same backup, not a second one.
	// Without this a resent RunBackup produces two archives and a retention rule that counts
	// them as two generations.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, startedBackup("bk-2", "vol-data", noon)); err != nil {
		t.Fatalf("begin the backup: %v", err)
	}
	err := store.BeginBackup(ctx, startedBackup("bk-2", "vol-data", noon.Add(time.Minute)))
	if !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("starting the same backup twice returned %v, want ErrAlreadyExists", err)
	}
}

func TestBeginBackupValidatesItsIdentifiers(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, BackupRun{SubjectID: "vol-data"}); err == nil {
		t.Fatal("a backup with no id was recorded")
	}
	if err := store.BeginBackup(ctx, BackupRun{BackupID: "bk-3"}); err == nil {
		t.Fatal("a backup that names no subject was recorded")
	}
}

func TestRecordBackupStageIsWhatSurvivesACrashDuringQuiesce(t *testing.T) {
	// QUIESCE is the only stage a customer feels. A daemon that dies during it leaves the
	// stage on disk, so whoever looks afterwards knows to check whether the workload was left
	// stopped.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, startedBackup("bk-4", "vol-data", noon)); err != nil {
		t.Fatalf("begin the backup: %v", err)
	}
	if err := store.RecordBackupStage(ctx, "bk-4", wisperpb.BackupStage_BACKUP_STAGE_SNAPSHOT,
		noon.Add(2*time.Second), "snapshotting"); err != nil {
		t.Fatalf("record the stage: %v", err)
	}

	run, err := store.Backup(ctx, "bk-4")
	if err != nil {
		t.Fatalf("read the backup: %v", err)
	}
	if run.Stage != wisperpb.BackupStage_BACKUP_STAGE_SNAPSHOT {
		t.Fatalf("stage = %s, want SNAPSHOT", run.Stage)
	}

	err = store.RecordBackupStage(ctx, "bk-ghost", wisperpb.BackupStage_BACKUP_STAGE_UPLOAD, noon, "uploading")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("a stage against an unknown backup returned %v, want ErrNotFound", err)
	}
}

func TestFinishBackupStoresWhereTheArchiveWent(t *testing.T) {
	// The location is enough to find the archive by hand when the panel's row is the thing
	// that was lost.
	store := openStore(t)
	ctx := context.Background()
	finished := noon.Add(6 * time.Minute)

	if err := store.BeginBackup(ctx, startedBackup("bk-5", "vol-data", noon)); err != nil {
		t.Fatalf("begin the backup: %v", err)
	}
	if err := store.FinishBackup(ctx, "bk-5", &wisperpb.BackupCompleted{
		BackupId:       "bk-5",
		Success:        true,
		RestorePointId: "rp-2026-03-04",
		Location:       "s3://wisper-backups/vol-data/rp-2026-03-04.tar.zst",
		SizeBytes:      812 << 20,
		Sha256:         "3f79bb7b435b05321651daefd374cdc681dc06faa65e374e38337b88ca046dea",
		QuiesceMillis:  340,
		Verified:       true,
		Detail:         "uploaded",
	}, finished); err != nil {
		t.Fatalf("finish the backup: %v", err)
	}

	run, err := store.Backup(ctx, "bk-5")
	if err != nil {
		t.Fatalf("read the backup: %v", err)
	}
	if !run.Finished || !run.Success {
		t.Fatalf("finished=%v success=%v after a successful backup", run.Finished, run.Success)
	}
	if run.Stage != wisperpb.BackupStage_BACKUP_STAGE_PRUNE {
		t.Fatalf("stage = %s, want PRUNE", run.Stage)
	}
	if run.RestorePointID != "rp-2026-03-04" {
		t.Fatalf("restore point = %q", run.RestorePointID)
	}
	if run.Location != "s3://wisper-backups/vol-data/rp-2026-03-04.tar.zst" {
		t.Fatalf("location = %q", run.Location)
	}
	if run.Result.GetSha256() == "" || run.Result.GetQuiesceMillis() != 340 {
		t.Fatalf("the stored result lost detail: %v", run.Result)
	}
}

func TestAFailedBackupKeepsTheStageItFailedAt(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, startedBackup("bk-6", "vol-data", noon)); err != nil {
		t.Fatalf("begin the backup: %v", err)
	}
	if err := store.FinishBackup(ctx, "bk-6", &wisperpb.BackupCompleted{
		BackupId:    "bk-6",
		Success:     false,
		FailedStage: wisperpb.BackupStage_BACKUP_STAGE_UPLOAD,
		Detail:      "the destination refused the connection",
	}, noon.Add(time.Minute)); err != nil {
		t.Fatalf("finish the backup: %v", err)
	}

	run, err := store.Backup(ctx, "bk-6")
	if err != nil {
		t.Fatalf("read the backup: %v", err)
	}
	if run.Success || run.Stage != wisperpb.BackupStage_BACKUP_STAGE_UPLOAD {
		t.Fatalf("success=%v stage=%s after a failed upload", run.Success, run.Stage)
	}
}

func TestFinishingAnUnknownBackupOrWithoutAResult(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.FinishBackup(ctx, "bk-7", nil, noon); err == nil {
		t.Fatal("a backup finished with no result")
	}
	err := store.FinishBackup(ctx, "bk-ghost", &wisperpb.BackupCompleted{BackupId: "bk-ghost"}, noon)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("finishing an unknown backup returned %v, want ErrNotFound", err)
	}
	if _, err := store.Backup(ctx, "bk-ghost"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("reading an unknown backup returned %v, want ErrNotFound", err)
	}
}

func TestUnfinishedBackupsAreTheOnesThatMayHaveLeftAWorkloadPaused(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBackup(ctx, startedBackup("bk-done", "vol-data", noon)); err != nil {
		t.Fatalf("begin the finished backup: %v", err)
	}
	if err := store.FinishBackup(ctx, "bk-done",
		&wisperpb.BackupCompleted{BackupId: "bk-done", Success: true}, noon); err != nil {
		t.Fatalf("finish the backup: %v", err)
	}
	if err := store.BeginBackup(ctx, startedBackup("bk-inflight", "vol-data", noon.Add(time.Minute))); err != nil {
		t.Fatalf("begin the in-flight backup: %v", err)
	}

	unfinished, err := store.UnfinishedBackups(ctx)
	if err != nil {
		t.Fatalf("list the unfinished backups: %v", err)
	}
	if len(unfinished) != 1 || unfinished[0].BackupID != "bk-inflight" {
		t.Fatalf("unfinished backups = %+v, want only bk-inflight", unfinished)
	}
	if unfinished[0].Stage != wisperpb.BackupStage_BACKUP_STAGE_QUIESCE {
		t.Fatalf("the in-flight backup lost the stage it was paused at: %s", unfinished[0].Stage)
	}
}

func TestBackupsAreListedNewestFirstAndFilteredBySubject(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		id := fmt.Sprintf("bk-vol-%d", i)
		if err := store.BeginBackup(ctx, startedBackup(id, "vol-data", noon.Add(time.Duration(i)*time.Hour))); err != nil {
			t.Fatalf("begin %s: %v", id, err)
		}
	}
	if err := store.BeginBackup(ctx, startedBackup("bk-db", "grant-9", noon)); err != nil {
		t.Fatalf("begin the database backup: %v", err)
	}

	history, err := store.Backups(ctx, "vol-data", 0)
	if err != nil {
		t.Fatalf("list the history: %v", err)
	}
	if len(history) != 3 || history[0].BackupID != "bk-vol-2" {
		t.Fatalf("history = %+v, want three runs newest first", history)
	}
	everything, err := store.Backups(ctx, "", 2)
	if err != nil {
		t.Fatalf("list every subject: %v", err)
	}
	if len(everything) != 2 {
		t.Fatalf("the limit produced %d rows, want 2", len(everything))
	}
}

func TestPruneBackupRunsKeepsTheRecentFinishedOnes(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for i := 0; i < 4; i++ {
		id := fmt.Sprintf("bk-%d", i)
		at := noon.Add(time.Duration(i) * time.Hour)
		if err := store.BeginBackup(ctx, startedBackup(id, "vol-data", at)); err != nil {
			t.Fatalf("begin %s: %v", id, err)
		}
		if err := store.FinishBackup(ctx, id, &wisperpb.BackupCompleted{BackupId: id, Success: true}, at); err != nil {
			t.Fatalf("finish %s: %v", id, err)
		}
	}
	if err := store.BeginBackup(ctx, startedBackup("bk-inflight", "vol-data", noon.Add(5*time.Hour))); err != nil {
		t.Fatalf("begin the in-flight backup: %v", err)
	}

	removed, err := store.PruneBackupRuns(ctx, 2)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 2 {
		t.Fatalf("pruned %d runs, want 2", removed)
	}
	if _, err := store.Backup(ctx, "bk-inflight"); err != nil {
		t.Fatalf("the running backup was pruned: %v", err)
	}
	if _, err := store.PruneBackupRuns(ctx, -1); err == nil {
		t.Fatal("a negative retention was accepted")
	}
}
