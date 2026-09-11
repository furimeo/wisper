package backup

import (
	"context"
	"errors"
	"path/filepath"
	"slices"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A whole volume backup, end to end, against a real filesystem and the local destination.

func volumeBackup(destination *wisperpb.BackupDestination) *wisperpb.RunBackup {
	return &wisperpb.RunBackup{
		BackupId:    "b1",
		Kind:        wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectId:   "vol1",
		WorkloadId:  "w1",
		Destination: destination,
		Verify:      true,
	}
}

func TestVolumeBackupUploadsAnArchiveAndItsChecksum(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{
		"app.db":        "the customer's data",
		"cache/one.txt": "and a subdirectory",
	})

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the backup failed: %s (stage %s)", completed.GetDetail(), completed.GetFailedStage())
	}

	const key = "nightly/vol1/20260911T100001Z-b1.tar.gz"
	objects := h.objectsAt(t)
	if _, found := objects[key]; !found {
		t.Fatalf("the archive is not at %s; the destination holds %v", key, objects)
	}
	if _, found := objects[key+digestSuffix]; !found {
		t.Fatalf("no checksum was written beside %s; the destination holds %v", key, objects)
	}

	// The location is the key and nothing else: the panel hands this same string back as
	// RestoreBackup.location, so anything decorative in it would have to be undecorated there.
	if got := completed.GetLocation(); got != key {
		t.Errorf("location is %q, want %q", got, key)
	}
	if got, want := completed.GetRestorePointId(), "rp-20260911T100001Z-b1"; got != want {
		t.Errorf("restore point is %q, want %q", got, want)
	}
	if completed.GetSizeBytes() != objects[key] {
		t.Errorf("reported %d bytes and the object is %d", completed.GetSizeBytes(), objects[key])
	}
	if len(completed.GetSha256()) != 64 {
		t.Errorf("the digest %q is not a sha256", completed.GetSha256())
	}
	if !completed.GetVerified() {
		t.Error("verify was asked for and the result does not say it passed")
	}
}

// The pause is what makes a copy a backup, and the length of it is the only number in the
// message the customer feels. Both are asserted: that it happened, and that it was measured
// over the snapshot rather than over the whole run.
func TestVolumeBackupPausesForTheSnapshotAndNoLonger(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}

	if got, want := h.Workloads.seen(), []string{"pause:w1", "unpause:w1"}; !slices.Equal(got, want) {
		t.Fatalf("the runtime saw %v, want %v", got, want)
	}
	// The harness clock advances one second per read, and exactly two reads happen inside the
	// window: the one before the pause and the one after the unpause. The upload, the verify
	// and the prune all read it too, and none of them may be inside this number.
	if got := completed.GetQuiesceMillis(); got != 1000 {
		t.Errorf("the pause is reported as %dms, want 1000 - anything larger means a stage after "+
			"the snapshot was counted inside the window", got)
	}
}

func TestBackupOfAWorkloadThatIsNotRunningPausesNothing(t *testing.T) {
	h := newHarness(t)
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the backup failed: %s", completed.GetDetail())
	}
	if got := h.Workloads.seen(); len(got) != 0 {
		t.Errorf("a stopped workload was still %v", got)
	}
	if completed.GetQuiesceMillis() != 0 {
		t.Errorf("nothing was paused and the pause is reported as %dms", completed.GetQuiesceMillis())
	}
}

// "Cannot see it" is not "does not exist", and it is not "it is safe to copy" either. A
// runtime that will not answer stops the backup at QUIESCE rather than producing a copy of a
// directory that may have been open.
func TestBackupRefusesWhenTheRuntimeWillNotSayWhetherTheWorkloadIsRunning(t *testing.T) {
	h := newHarness(t)
	h.Workloads.failRunning = errors.New("the engine is not answering")
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("the command itself should succeed and report a failed backup: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("the backup reported success without knowing whether anything was writing")
	}
	if got := completed.GetFailedStage(); got != wisperpb.BackupStage_BACKUP_STAGE_QUIESCE {
		t.Errorf("failed at stage %s, want QUIESCE", got)
	}
	if objects := h.objectsAt(t); len(objects) != 0 {
		t.Errorf("a backup that never ran left %v at the destination", objects)
	}
}

// The stage is written down before each step, so a daemon that dies leaves behind where it had
// got to rather than an unexplained gap.
func TestBackupRecordsEveryStageItReaches(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	if _, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly"))); err != nil {
		t.Fatalf("run the backup: %v", err)
	}

	want := []wisperpb.BackupStage{
		wisperpb.BackupStage_BACKUP_STAGE_QUIESCE,
		wisperpb.BackupStage_BACKUP_STAGE_UPLOAD,
		wisperpb.BackupStage_BACKUP_STAGE_VERIFY,
		wisperpb.BackupStage_BACKUP_STAGE_PRUNE,
	}
	if !slices.Equal(h.Store.stages, want) {
		t.Errorf("the stages recorded were %v, want %v", h.Store.stages, want)
	}
}

// backup.proto: "a repeated command with the same id is the same backup, not a second one".
func TestRepeatedBackupIdReplaysTheStoredResultRatherThanCopyingAgain(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	first, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	before := h.Workloads.seen()

	second, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup again: %v", err)
	}

	if second.GetRestorePointId() != first.GetRestorePointId() {
		t.Errorf("the second run minted restore point %q; the first was %q",
			second.GetRestorePointId(), first.GetRestorePointId())
	}
	if got := h.Workloads.seen(); !slices.Equal(got, before) {
		t.Errorf("the replay paused the application again: %v", got)
	}
	if keys := archiveKeys(h.objectsAt(t)); len(keys) != 1 {
		t.Errorf("one backup produced %d archives: %v", len(keys), keys)
	}
}

// Nothing is left in the work directory once a backup has finished. A node that kept every
// staged archive would fill the disk the customers' volumes are on.
func TestASuccessfulBackupLeavesNothingStaged(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	if _, err := h.Runner.RunBackup(context.Background(), volumeBackup(localTarget("nightly"))); err != nil {
		t.Fatalf("run the backup: %v", err)
	}

	left := readTree(t, filepath.Join(h.StateDir, workDirectory))
	if len(left) != 0 {
		t.Errorf("the work directory still holds %v", left)
	}
}
