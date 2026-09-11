package state

import (
	"context"
	"errors"
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
)

func TestSaveAndLoadSpecRoundTripExactly(t *testing.T) {
	// The spec is the one document a cold start reads before it touches Docker. Every field
	// that came off the wire has to come back, not just the generation, because whatever is
	// lost here is a container the node stops managing correctly with the panel unreachable.
	store := openStore(t)
	ctx := context.Background()
	original := sampleSpec(7)

	if err := store.SaveSpec(ctx, original, "deployment 412", noon); err != nil {
		t.Fatalf("save spec: %v", err)
	}

	stored, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("load spec: %v", err)
	}
	if !proto.Equal(stored.Spec, original) {
		t.Fatalf("the spec that came back is not the spec that went in:\n got %v\nwant %v", stored.Spec, original)
	}
	if stored.Generation != 7 {
		t.Fatalf("generation = %d, want 7", stored.Generation)
	}
	if stored.Reason != "deployment 412" {
		t.Fatalf("reason = %q, want %q", stored.Reason, "deployment 412")
	}
	if !stored.ReceivedAt.Equal(noon) {
		t.Fatalf("received at %s, want %s", stored.ReceivedAt, noon)
	}
	if len(stored.SHA256) != 64 {
		t.Fatalf("sha256 = %q, want 64 hex characters", stored.SHA256)
	}
}

func TestSavingTheSameSpecTwiceProducesTheSameChecksum(t *testing.T) {
	// The reconcile loop decides "nothing changed" by comparing this string. If the encoding
	// were not deterministic, every pass would look like drift and the node would churn.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(1), "first", noon); err != nil {
		t.Fatalf("save the first spec: %v", err)
	}
	first, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("load the first spec: %v", err)
	}

	if err := store.SaveSpec(ctx, sampleSpec(1), "reconnect", noon); err != nil {
		t.Fatalf("save the same spec again: %v", err)
	}
	second, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("load the second spec: %v", err)
	}

	if first.SHA256 != second.SHA256 {
		t.Fatalf("the same spec hashed to %s and then %s", first.SHA256, second.SHA256)
	}
	if second.Reason != "reconnect" {
		t.Fatalf("the resend did not overwrite the reason: %q", second.Reason)
	}
}

func TestSaveSpecAcceptsAnEqualGeneration(t *testing.T) {
	// node-spec.md section 2: an equal generation is accepted and overwrites. The panel
	// resends the whole spec on every reconnect and the bytes may differ even when the
	// number does not.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(4), "publish", noon); err != nil {
		t.Fatalf("save the first spec: %v", err)
	}

	changed := sampleSpec(4)
	changed.Workloads = changed.Workloads[:1]
	if err := store.SaveSpec(ctx, changed, "reconnect", noon); err != nil {
		t.Fatalf("an equal generation was refused: %v", err)
	}

	stored, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("load spec: %v", err)
	}
	if got := len(stored.Spec.GetWorkloads()); got != 1 {
		t.Fatalf("the resent spec did not replace the stored one: %d workloads, want 1", got)
	}
}

func TestSaveSpecRefusesAnOlderGeneration(t *testing.T) {
	// A retried frame arriving late must not roll a machine backwards.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(9), "publish", noon); err != nil {
		t.Fatalf("save generation 9: %v", err)
	}
	err := store.SaveSpec(ctx, sampleSpec(8), "a frame that took the slow road", noon)
	if !errors.Is(err, ErrSupersededGeneration) {
		t.Fatalf("saving generation 8 over 9 returned %v, want ErrSupersededGeneration", err)
	}

	stored, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("load spec: %v", err)
	}
	if stored.Generation != 9 {
		t.Fatalf("the refused spec still moved the generation to %d", stored.Generation)
	}
	if stored.Reason != "publish" {
		t.Fatalf("the refused spec still overwrote the reason: %q", stored.Reason)
	}
}

func TestLoadSpecOnANodeThatHasNeverBeenGivenOne(t *testing.T) {
	// A freshly enrolled node is in this state until its first ApplySpec. The reconcile loop
	// branches on it to remove nothing, so it must be distinguishable from a failed read.
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.LoadSpec(ctx); !errors.Is(err, ErrNoSpec) {
		t.Fatalf("LoadSpec on a fresh database returned %v, want ErrNoSpec", err)
	}
	if _, err := store.SpecGeneration(ctx); !errors.Is(err, ErrNoSpec) {
		t.Fatalf("SpecGeneration on a fresh database returned %v, want ErrNoSpec", err)
	}
}

func TestSaveSpecRefusesNil(t *testing.T) {
	if err := openStore(t).SaveSpec(context.Background(), nil, "nothing", noon); err == nil {
		t.Fatal("a nil spec was stored")
	}
}

func TestSpecGenerationMatchesTheStoredSpec(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(42), "publish", noon); err != nil {
		t.Fatalf("save spec: %v", err)
	}
	generation, err := store.SpecGeneration(ctx)
	if err != nil {
		t.Fatalf("read the generation: %v", err)
	}
	if generation != 42 {
		t.Fatalf("generation = %d, want 42", generation)
	}
}

func TestLoadSpecRefusesAPayloadThatDoesNotMatchItsChecksum(t *testing.T) {
	// SQLite's own integrity check verifies its b-trees, not the contents of a blob. A
	// flipped bit inside the payload would decode into a spec that is subtly wrong and get
	// reconciled - containers stopped, routes withdrawn - so the checksum is the guard.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveSpec(ctx, sampleSpec(5), "publish", noon); err != nil {
		t.Fatalf("save spec: %v", err)
	}
	if _, err := store.db.ExecContext(ctx,
		`UPDATE node_spec SET payload = ? WHERE id = 1`, []byte("not a protobuf")); err != nil {
		t.Fatalf("damage the stored payload: %v", err)
	}

	_, err := store.LoadSpec(ctx)
	if err == nil {
		t.Fatal("a damaged spec was handed to the reconcile loop")
	}
	if !strings.Contains(err.Error(), "checksum") {
		t.Fatalf("the error does not name the cause: %v", err)
	}
}
