package backup

import (
	"context"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A whole restore, end to end, and the property that makes it safe to press the button: the
// data that was there a moment ago is still somewhere.

// takeVolumeBackup runs a backup and returns the key it landed at.
func takeVolumeBackup(t *testing.T, h *harness, files map[string]string) string {
	t.Helper()
	h.seedVolume(t, "w1", "vol1", files)

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the backup failed: %s", completed.GetDetail())
	}
	return completed.GetLocation()
}

func volumeRestore(key string) *wisperpb.RestoreBackup {
	return &wisperpb.RestoreBackup{
		RestoreId:      "r1",
		RestorePointId: "rp-20260911T100001Z-b1",
		Location:       key,
		Kind:           wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectId:      "vol1",
		WorkloadId:     "w1",
		Source:         localTarget("nightly"),
		StopWorkload:   true,
	}
}

func TestRestorePutsTheVolumeBackAndKeepsWhatWasThere(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	original := map[string]string{"app.db": "monday", "cache/one.txt": "warm"}
	key := takeVolumeBackup(t, h, original)

	// Tuesday happens: the customer's application rewrites the volume, then somebody asks for
	// Monday back.
	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	if err := os.RemoveAll(live); err != nil {
		t.Fatalf("clear the volume: %v", err)
	}
	writeTree(t, live, map[string]string{"app.db": "tuesday", "new.txt": "written today"})

	completed, err := h.Runner.RestoreBackup(context.Background(), volumeRestore(key))
	if err != nil {
		t.Fatalf("run the restore: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the restore failed: %s", completed.GetDetail())
	}

	if got := readTree(t, live); !maps.Equal(got, original) {
		t.Errorf("the volume holds %v, want %v", got, original)
	}

	// The point of the exercise: Tuesday is still on the node.
	aside, err := rollbackPath(h.StateDir, "w1", "vol1", "r1")
	if err != nil {
		t.Fatalf("resolve the rollback copy: %v", err)
	}
	kept := readTree(t, aside)
	want := map[string]string{"app.db": "tuesday", "new.txt": "written today"}
	if !maps.Equal(kept, want) {
		t.Errorf("the rollback copy holds %v, want %v", kept, want)
	}
	if got := completed.GetRestoredTo(); got != live {
		t.Errorf("restored_to is %q, want %q", got, live)
	}
}

func TestRestoreStopsTheWorkloadAndStartsItAgain(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	key := takeVolumeBackup(t, h, map[string]string{"app.db": "monday"})

	completed, err := h.Runner.RestoreBackup(context.Background(), volumeRestore(key))
	if err != nil {
		t.Fatalf("run the restore: %v", err)
	}
	if !completed.GetWorkloadRestarted() {
		t.Error("the workload was stopped and the result does not say it was started again")
	}

	events := h.Workloads.seen()
	stop := slices.Index(events, "stop:w1")
	start := slices.Index(events, "start:w1")
	if stop < 0 || start < 0 || stop > start {
		t.Errorf("the runtime saw %v; a restore stops and then starts", events)
	}
}

// The rehearsal. This is the verify-restore path, and the whole reason it exists is that a
// backup nobody has ever put back is a backup nobody knows is any good.
func TestDryRunRestoresBesideTheLiveDataAndTouchesNothing(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	original := map[string]string{"app.db": "monday"}
	key := takeVolumeBackup(t, h, original)

	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	writeTree(t, live, map[string]string{"app.db": "tuesday"})
	before := h.Workloads.seen()

	request := volumeRestore(key)
	request.DryRun = true
	completed, err := h.Runner.RestoreBackup(context.Background(), request)
	if err != nil {
		t.Fatalf("rehearse the restore: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the rehearsal failed: %s", completed.GetDetail())
	}

	if got := readTree(t, live); got["app.db"] != "tuesday" {
		t.Errorf("the rehearsal changed the live volume: %v", got)
	}
	if got := h.Workloads.seen(); !slices.Equal(got, before) {
		t.Errorf("the rehearsal stopped something: %v", got)
	}

	restoredTo := completed.GetRestoredTo()
	if restoredTo == live || restoredTo == "" {
		t.Fatalf("the rehearsal reports restored_to %q, which is not somewhere beside the volume", restoredTo)
	}
	if got := readTree(t, restoredTo); !maps.Equal(got, original) {
		t.Errorf("the rehearsal produced %v, want %v", got, original)
	}
}

// A restore of a volume that has never existed is a legitimate thing to ask for - restoring
// onto a replacement node - and there is nothing to keep a copy of.
func TestRestoreIntoAVolumeThatIsNotThereKeepsNoCopy(t *testing.T) {
	h := newHarness(t)
	key := takeVolumeBackup(t, h, map[string]string{"app.db": "monday"})

	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	if err := os.RemoveAll(live); err != nil {
		t.Fatalf("remove the volume: %v", err)
	}

	completed, err := h.Runner.RestoreBackup(context.Background(), volumeRestore(key))
	if err != nil {
		t.Fatalf("run the restore: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the restore failed: %s", completed.GetDetail())
	}
	if got := readTree(t, live); got["app.db"] != "monday" {
		t.Errorf("the volume holds %v", got)
	}

	aside, err := rollbackPath(h.StateDir, "w1", "vol1", "r1")
	if err != nil {
		t.Fatalf("resolve the rollback copy: %v", err)
	}
	if _, err := os.Lstat(aside); !os.IsNotExist(err) {
		t.Errorf("a copy was invented at %s for data that did not exist", aside)
	}
}

// The two renames a restore does are the moment a machine going down could lose a volume, so
// the recovery that puts it back is tested directly rather than trusted.
func TestRecoverPutsBackAVolumeARestoreWasKilledBetweenTheRenames(t *testing.T) {
	h := newHarness(t)
	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	writeTree(t, live, map[string]string{"app.db": "monday"})

	// Exactly the state a kill between rename one and rename two leaves: the row open, the
	// volume gone, and the copy in the rollback tree.
	if err := h.Store.BeginRestore(context.Background(), restoreRunFor("r1")); err != nil {
		t.Fatalf("record the restore: %v", err)
	}
	aside, err := rollbackPath(h.StateDir, "w1", "vol1", "r1")
	if err != nil {
		t.Fatalf("resolve the rollback copy: %v", err)
	}
	if err := os.MkdirAll(filepath.Dir(aside), 0o755); err != nil {
		t.Fatalf("create the rollback tree: %v", err)
	}
	if err := os.Rename(live, aside); err != nil {
		t.Fatalf("move the volume aside: %v", err)
	}

	if err := h.Runner.Recover(context.Background()); err != nil {
		t.Fatalf("recover: %v", err)
	}

	if got := readTree(t, live); got["app.db"] != "monday" {
		t.Errorf("the volume was not put back; it holds %v", got)
	}
	run, err := h.Store.Restore(context.Background(), "r1")
	if err != nil {
		t.Fatalf("read the restore back: %v", err)
	}
	if !run.Finished || run.Success {
		t.Errorf("the interrupted restore is recorded as finished=%v success=%v; it must be "+
			"closed out as a failure so a retry is not refused forever", run.Finished, run.Success)
	}
}

// A backup killed during the pause leaves a frozen application, and nothing else on the node
// will unfreeze it.
func TestRecoverUnfreezesAWorkloadAnInterruptedBackupLeftPaused(t *testing.T) {
	h := newHarness(t)
	err := h.Store.BeginBackup(context.Background(), backupRunFor("b1"))
	if err != nil {
		t.Fatalf("record the backup: %v", err)
	}

	if err := h.Runner.Recover(context.Background()); err != nil {
		t.Fatalf("recover: %v", err)
	}
	if got, want := h.Workloads.seen(), []string{"unpause:w1"}; !slices.Equal(got, want) {
		t.Errorf("the runtime saw %v, want %v", got, want)
	}

	// And the run is left open, because an interrupted backup is resumed rather than failed.
	run, err := h.Store.Backup(context.Background(), "b1")
	if err != nil {
		t.Fatalf("read the backup back: %v", err)
	}
	if run.Finished {
		t.Error("recovery closed out an interrupted backup; it has to stay resumable")
	}
}
