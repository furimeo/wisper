package state

import (
	"context"
	"errors"
	"sort"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func enrolledAs(nodeID, panel string) Enrolment {
	return Enrolment{
		NodeID:                 nodeID,
		NodeName:               "sg-edge-1",
		Panel:                  panel,
		PanelCertificateSHA256: "0e3b1c5f2ab4d6e78901234567890abcdef1234567890abcdef1234567890abc",
		MachineFingerprint:     "fp-9f1c2b",
		AgentVersion:           "0.1.0",
		ProtocolVersion:        1,
		EnrolledAt:             noon,
	}
}

// fill writes one row into every table SaveEnrolment is supposed to clear, so a test can
// assert that all of them went and that nothing else did.
func fill(t *testing.T, store *Store) {
	t.Helper()
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(11), "publish", noon); err != nil {
		t.Fatalf("save the spec: %v", err)
	}
	if err := store.MarkApplied(ctx, 11, noon); err != nil {
		t.Fatalf("mark applied: %v", err)
	}
	if err := store.SaveWorkloadStatuses(ctx,
		[]*wisperpb.WorkloadStatus{observed("wl-api", wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING, 0)}, noon); err != nil {
		t.Fatalf("save a workload status: %v", err)
	}
	if _, err := store.BeginUpload(ctx, sampleSession("up-old")); err != nil {
		t.Fatalf("open an upload session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-old", ByteRange{0, 1 << 20}, noon); err != nil {
		t.Fatalf("record a chunk: %v", err)
	}
	if err := store.SaveCertificate(ctx, issued("api.example.test", noon)); err != nil {
		t.Fatalf("save a certificate record: %v", err)
	}
	if err := store.BeginBuild(ctx, startedBuild("b-old", "wl-site", noon)); err != nil {
		t.Fatalf("begin a build: %v", err)
	}
	if err := store.BeginBackup(ctx, startedBackup("bk-old", "vol-data", noon)); err != nil {
		t.Fatalf("begin a backup: %v", err)
	}
	if err := store.BeginRestore(ctx, startedRestore("rs-old", noon)); err != nil {
		t.Fatalf("begin a restore: %v", err)
	}
	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("start a cron run: %v", err)
	}
}

// rowsIn counts a table directly, which is how a test asserts that clearing happened
// without going through nine accessors.
func rowsIn(t *testing.T, store *Store, table string) int {
	t.Helper()
	var count int
	if err := store.db.QueryRowContext(context.Background(), `SELECT count(*) FROM `+table).Scan(&count); err != nil {
		t.Fatalf("count %s: %v", table, err)
	}
	return count
}

func TestEnrolmentRoundTrips(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	wanted := enrolledAs("node-7", "https://panel.example")

	cleared, err := store.SaveEnrolment(ctx, wanted)
	if err != nil {
		t.Fatalf("save the enrolment: %v", err)
	}
	if cleared {
		t.Fatal("the first enrolment of a fresh database claimed to have discarded state")
	}

	read, err := store.Enrolment(ctx)
	if err != nil {
		t.Fatalf("read the enrolment: %v", err)
	}
	if read != wanted {
		t.Fatalf("the enrolment differs:\n got %+v\nwant %+v", read, wanted)
	}
}

func TestANodeThatHasNeverEnrolledIsNotFound(t *testing.T) {
	// The state between `sasayaki run` finding a state directory and the credential being
	// read. Not an error, and the caller branches on it.
	_, err := openStore(t).Enrolment(context.Background())
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("a database that has never been enrolled returned %v, want ErrNotFound", err)
	}
}

func TestReEnrollingAsTheSameNodeKeepsEverything(t *testing.T) {
	// The daemon records its enrolment on every start. Clearing on that would empty the
	// state database each time the machine reboots.
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.SaveEnrolment(ctx, enrolledAs("node-7", "https://panel.example")); err != nil {
		t.Fatalf("save the enrolment: %v", err)
	}
	fill(t, store)

	again := enrolledAs("node-7", "https://panel.example")
	again.AgentVersion = "0.2.0"
	again.EnrolledAt = noon.Add(24 * time.Hour)
	cleared, err := store.SaveEnrolment(ctx, again)
	if err != nil {
		t.Fatalf("record the same enrolment again: %v", err)
	}
	if cleared {
		t.Fatal("re-recording the same enrolment discarded the node's state")
	}

	if _, err := store.LoadSpec(ctx); err != nil {
		t.Fatalf("the spec did not survive: %v", err)
	}
	for _, table := range nodeScopedTables() {
		if rowsIn(t, store, table) == 0 {
			t.Fatalf("%s was emptied by an unchanged enrolment", table)
		}
	}

	read, err := store.Enrolment(ctx)
	if err != nil {
		t.Fatalf("read the enrolment: %v", err)
	}
	if read.AgentVersion != "0.2.0" {
		t.Fatalf("the upgraded agent version was not recorded: %q", read.AgentVersion)
	}
}

func TestEnrollingAsADifferentNodeDiscardsTheOldState(t *testing.T) {
	// The node was deleted in the panel and created again. Everything in this database
	// describes work that belonged to the previous enrolment, and handing it to a new panel
	// would have the node reconciling towards another node's desired state.
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.SaveEnrolment(ctx, enrolledAs("node-7", "https://panel.example")); err != nil {
		t.Fatalf("save the first enrolment: %v", err)
	}
	fill(t, store)

	cleared, err := store.SaveEnrolment(ctx, enrolledAs("node-8", "https://panel.example"))
	if err != nil {
		t.Fatalf("save the second enrolment: %v", err)
	}
	if !cleared {
		t.Fatal("enrolling as a different node did not report that it had discarded state")
	}

	for _, table := range nodeScopedTables() {
		if count := rowsIn(t, store, table); count != 0 {
			t.Fatalf("%s kept %d rows from the previous enrolment", table, count)
		}
	}
	// The foreign key takes the ranges with their sessions, which is why upload_range is not
	// in the list and why it has to be checked separately.
	if count := rowsIn(t, store, "upload_range"); count != 0 {
		t.Fatalf("upload_range kept %d rows from the previous enrolment", count)
	}
	if _, err := store.LoadSpec(ctx); !errors.Is(err, ErrNoSpec) {
		t.Fatalf("the previous node's spec survived: %v", err)
	}

	read, err := store.Enrolment(ctx)
	if err != nil {
		t.Fatalf("read the enrolment: %v", err)
	}
	if read.NodeID != "node-8" {
		t.Fatalf("node id = %q, want node-8", read.NodeID)
	}
}

func TestPointingAtADifferentPanelDiscardsTheOldState(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.SaveEnrolment(ctx, enrolledAs("node-7", "https://panel.example")); err != nil {
		t.Fatalf("save the first enrolment: %v", err)
	}
	fill(t, store)

	cleared, err := store.SaveEnrolment(ctx, enrolledAs("node-7", "https://other-panel.example"))
	if err != nil {
		t.Fatalf("save the second enrolment: %v", err)
	}
	if !cleared {
		t.Fatal("moving to a different panel kept the previous panel's desired state")
	}
	if _, err := store.LoadSpec(ctx); !errors.Is(err, ErrNoSpec) {
		t.Fatalf("the previous panel's spec survived: %v", err)
	}
}

func TestAnEnrolmentNeedsANodeAndAPanel(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.SaveEnrolment(ctx, Enrolment{Panel: "https://panel.example"}); err == nil {
		t.Fatal("an enrolment with no node id was recorded")
	}
	if _, err := store.SaveEnrolment(ctx, Enrolment{NodeID: "node-7"}); err == nil {
		t.Fatal("an enrolment that names no panel was recorded")
	}
}

func TestEveryNodeScopedTableIsListed(t *testing.T) {
	// A table added to the schema without a line added to nodeScopedTables would survive a
	// re-enrolment and hand the new panel the old one's workloads. The two are the same
	// decision, so the list is checked against the schema rather than trusted.
	store := openStore(t)
	ctx := context.Background()

	rows, err := store.db.QueryContext(ctx,
		`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`)
	if err != nil {
		t.Fatalf("list the tables: %v", err)
	}
	defer rows.Close()

	var found []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatalf("list the tables: %v", err)
		}
		switch name {
		// The ledger belongs to the schema, not to a node, and re-enrolling must not make the
		// migrations run again.
		case "schema_migration":
		// The row being written, and the rows the foreign key takes with their session.
		case "enrolment", "upload_range":
		default:
			found = append(found, name)
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("list the tables: %v", err)
	}

	scoped := append([]string(nil), nodeScopedTables()...)
	sort.Strings(scoped)
	sort.Strings(found)
	if len(scoped) != len(found) {
		t.Fatalf("nodeScopedTables lists %v but the schema has %v", scoped, found)
	}
	for i := range found {
		if scoped[i] != found[i] {
			t.Fatalf("nodeScopedTables lists %v but the schema has %v", scoped, found)
		}
	}
}
