package backup

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The logical half. A database needs no pause, needs no rename, and needs the safety copy more
// than a volume does - because a replay that half succeeds cannot be undone by moving a
// directory back.

func databaseBackup(destination *wisperpb.BackupDestination) *wisperpb.RunBackup {
	return &wisperpb.RunBackup{
		BackupId:     "b1",
		Kind:         wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE,
		SubjectId:    "grant7",
		WorkloadId:   "w1",
		Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		DatabaseName: "customer_seven",
		Destination:  destination,
		Verify:       true,
	}
}

func databaseRestore(key string) *wisperpb.RestoreBackup {
	return &wisperpb.RestoreBackup{
		RestoreId:      "r1",
		RestorePointId: "rp-20260911T100001Z-b1",
		Location:       key,
		Kind:           wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE,
		SubjectId:      "grant7",
		WorkloadId:     "w1",
		Engine:         wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		DatabaseName:   "customer_seven",
		Source:         localTarget("nightly"),
	}
}

func TestADatabaseBackupDumpsWithoutPausingAnything(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.Dumps.contents["customer_seven"] = "CREATE TABLE orders (id int);"

	completed, err := h.Runner.RunBackup(context.Background(), databaseBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the backup failed: %s (stage %s)", completed.GetDetail(), completed.GetFailedStage())
	}

	// A dump is consistent on its own, so the honest quiesce is zero rather than a small number
	// invented for symmetry with the volume path.
	if completed.GetQuiesceMillis() != 0 {
		t.Errorf("a database backup reported a %dms pause", completed.GetQuiesceMillis())
	}
	if got := h.Workloads.seen(); len(got) != 0 {
		t.Errorf("a database backup touched the workload: %v", got)
	}

	const key = "nightly/grant7/20260911T100001Z-b1.sql.gz"
	if _, found := h.objectsAt(t)[key]; !found {
		t.Errorf("the dump is not at %s; the destination holds %v", key, h.objectsAt(t))
	}
}

func TestADatabaseRestoreReplaysTheDumpAndKeepsOneOfWhatWasThere(t *testing.T) {
	h := newHarness(t)
	h.Dumps.contents["customer_seven"] = "monday's schema"

	completed, err := h.Runner.RunBackup(context.Background(), databaseBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	h.Dumps.contents["customer_seven"] = "tuesday's schema"

	restored, err := h.Runner.RestoreBackup(context.Background(), databaseRestore(completed.GetLocation()))
	if err != nil {
		t.Fatalf("run the restore: %v", err)
	}
	if !restored.GetSuccess() {
		t.Fatalf("the restore failed: %s", restored.GetDetail())
	}
	if got := h.Dumps.contents["customer_seven"]; got != "monday's schema" {
		t.Errorf("the database holds %q, want monday's", got)
	}
	if got := restored.GetRestoredTo(); got != "customer_seven" {
		t.Errorf("restored_to is %q", got)
	}

	// Tuesday is still on the node, as a dump taken immediately before the replay.
	aside, err := rollbackPath(h.StateDir, "w1", "grant7", "r1")
	if err != nil {
		t.Fatalf("resolve the rollback path: %v", err)
	}
	dump := aside + databaseExtension
	if _, err := os.Stat(dump); err != nil {
		t.Fatalf("no rollback dump at %s: %v", dump, err)
	}
	if !strings.Contains(restored.GetDetail(), dump) {
		t.Errorf("the result does not say where the rollback dump is: %q", restored.GetDetail())
	}
}

// The rehearsal for a database: a second database beside the live one, which is what makes
// "has this backup ever been restored" a question with an answer.
func TestADatabaseDryRunRestoresIntoASecondDatabase(t *testing.T) {
	h := newHarness(t)
	h.Dumps.contents["customer_seven"] = "monday's schema"

	completed, err := h.Runner.RunBackup(context.Background(), databaseBackup(localTarget("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	h.Dumps.contents["customer_seven"] = "tuesday's schema"

	request := databaseRestore(completed.GetLocation())
	request.DryRun = true
	restored, err := h.Runner.RestoreBackup(context.Background(), request)
	if err != nil {
		t.Fatalf("rehearse the restore: %v", err)
	}
	if !restored.GetSuccess() {
		t.Fatalf("the rehearsal failed: %s", restored.GetDetail())
	}

	if got := h.Dumps.contents["customer_seven"]; got != "tuesday's schema" {
		t.Errorf("the rehearsal changed the live database to %q", got)
	}
	rehearsal := restored.GetRestoredTo()
	if rehearsal == "customer_seven" || rehearsal == "" {
		t.Fatalf("the rehearsal reports restored_to %q, which is the live database", rehearsal)
	}
	if got := h.Dumps.contents[rehearsal]; got != "monday's schema" {
		t.Errorf("%s holds %q, want monday's", rehearsal, got)
	}
	if len(h.Dumps.created) != 1 || h.Dumps.created[0] != rehearsal {
		t.Errorf("the rehearsal created %v", h.Dumps.created)
	}
}

// The panel's own schema constrains a database name, so a suffix that pushed one over the
// limit would be refused by the engine in front of somebody running a rehearsal they were told
// was safe.
func TestARehearsalNameFitsInsideWhatTheEngineAccepts(t *testing.T) {
	long := strings.Repeat("a", 63)
	name, err := dryRunDatabaseName(long, "restore-00000000000000000000")
	if err != nil {
		t.Fatalf("build the rehearsal name: %v", err)
	}
	if len(name) > 63 {
		t.Errorf("the rehearsal name is %d characters: %q", len(name), name)
	}
	if name == long {
		t.Error("the rehearsal name is the live database's name")
	}
}
