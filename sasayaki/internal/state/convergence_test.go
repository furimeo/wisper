package state

import (
	"context"
	"testing"
	"time"
)

func TestConvergenceOnAFreshDatabaseIsAllZero(t *testing.T) {
	// "Never converged" and "no row yet" are the same fact, and a caller reporting the first
	// heartbeat should not have to tell them apart.
	store := openStore(t)

	converged, err := store.Convergence(context.Background())
	if err != nil {
		t.Fatalf("read convergence on a fresh database: %v", err)
	}
	if converged != (Convergence{}) {
		t.Fatalf("a fresh database reported %+v, want the zero value", converged)
	}
}

func TestMarkAppliedRecordsThePassAndTheGeneration(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.MarkApplied(ctx, 12, noon); err != nil {
		t.Fatalf("mark applied: %v", err)
	}
	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 12 {
		t.Fatalf("applied generation = %d, want 12", converged.AppliedGeneration)
	}
	if !converged.AppliedAt.Equal(noon) || !converged.LastPassAt.Equal(noon) {
		t.Fatalf("timestamps are %s and %s, want %s", converged.AppliedAt, converged.LastPassAt, noon)
	}
	if converged.Passes != 1 {
		t.Fatalf("passes = %d after one pass", converged.Passes)
	}
	if converged.LastError != "" {
		t.Fatalf("a successful pass recorded the error %q", converged.LastError)
	}
}

func TestTheAppliedGenerationNeverGoesBackwards(t *testing.T) {
	// A pass that started before a newer spec arrived converges the machine to the older one.
	// Reporting that as a regression would have the panel resend a spec the node already has,
	// forever.
	store := openStore(t)
	ctx := context.Background()
	later := noon.Add(time.Minute)

	if err := store.MarkApplied(ctx, 20, noon); err != nil {
		t.Fatalf("mark generation 20 applied: %v", err)
	}
	if err := store.MarkApplied(ctx, 19, later); err != nil {
		t.Fatalf("mark generation 19 applied: %v", err)
	}

	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 20 {
		t.Fatalf("applied generation fell to %d", converged.AppliedGeneration)
	}
	if !converged.AppliedAt.Equal(noon) {
		t.Fatalf("the older pass moved applied_at to %s", converged.AppliedAt)
	}
	// The pass still happened, and a stalled loop is diagnosed by this counter not moving.
	if converged.Passes != 2 {
		t.Fatalf("passes = %d, want 2", converged.Passes)
	}
	if !converged.LastPassAt.Equal(later) {
		t.Fatalf("last pass at %s, want %s", converged.LastPassAt, later)
	}
}

func TestAFailedPassKeepsTheAppliedGeneration(t *testing.T) {
	// Docker being unreachable is the ordinary case. The containers that were already right
	// are still right, so failing to converge is not regressing.
	store := openStore(t)
	ctx := context.Background()
	later := noon.Add(time.Minute)

	if err := store.MarkApplied(ctx, 31, noon); err != nil {
		t.Fatalf("mark applied: %v", err)
	}
	if err := store.MarkPassFailed(ctx, later, "docker unreachable since 12:04"); err != nil {
		t.Fatalf("mark the pass failed: %v", err)
	}

	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 31 {
		t.Fatalf("a failed pass moved the applied generation to %d", converged.AppliedGeneration)
	}
	if !converged.AppliedAt.Equal(noon) {
		t.Fatalf("a failed pass moved applied_at to %s", converged.AppliedAt)
	}
	if converged.LastError != "docker unreachable since 12:04" {
		t.Fatalf("last error = %q", converged.LastError)
	}
	if converged.Passes != 2 {
		t.Fatalf("passes = %d, want 2", converged.Passes)
	}
}

func TestASuccessfulPassClearsThePreviousError(t *testing.T) {
	// health_detail is built from this field. A node that recovered must stop explaining an
	// outage that is over.
	store := openStore(t)
	ctx := context.Background()

	if err := store.MarkPassFailed(ctx, noon, "docker unreachable"); err != nil {
		t.Fatalf("mark the pass failed: %v", err)
	}
	if err := store.MarkApplied(ctx, 3, noon.Add(15*time.Second)); err != nil {
		t.Fatalf("mark applied: %v", err)
	}

	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.LastError != "" {
		t.Fatalf("the recovered node still reports %q", converged.LastError)
	}
}

func TestAFailedPassMustSayWhy(t *testing.T) {
	if err := openStore(t).MarkPassFailed(context.Background(), noon, ""); err == nil {
		t.Fatal("a failure with no detail was recorded, which tells an operator nothing")
	}
}

func TestAppliedGenerationIsReadableWithoutTheWholeRecord(t *testing.T) {
	// Asked for on every heartbeat, so it has its own accessor.
	store := openStore(t)
	ctx := context.Background()

	generation, err := store.AppliedGeneration(ctx)
	if err != nil {
		t.Fatalf("read the applied generation on a fresh database: %v", err)
	}
	if generation != 0 {
		t.Fatalf("a node that has converged to nothing reported generation %d", generation)
	}

	if err := store.MarkApplied(ctx, 88, noon); err != nil {
		t.Fatalf("mark applied: %v", err)
	}
	if generation, err = store.AppliedGeneration(ctx); err != nil {
		t.Fatalf("read the applied generation: %v", err)
	}
	if generation != 88 {
		t.Fatalf("applied generation = %d, want 88", generation)
	}
}
