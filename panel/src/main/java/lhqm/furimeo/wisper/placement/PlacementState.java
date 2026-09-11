package lhqm.furimeo.wisper.placement;

/**
 * Where a binding between a service and a node stands. Mirrors the
 * {@code placement_state_known} CHECK.
 *
 * <p>Intent, not observation. Whether the container is actually up is
 * {@link ReportedWorkloadState} on the same row, written by the node; this column says
 * what the panel has decided about <em>which machine holds this service</em>. The two are
 * separate because a workload that crashed has not moved, and a placement that is being
 * drained is still serving.
 *
 * <p>The partial unique index {@code placement_one_active_per_service_idx} allows exactly
 * one {@link #ACTIVE} row per service. It is partial so that a {@link #DRAINING} row and
 * its replacement can exist at the same time, which is the whole of how a migration works
 * here: the old binding is marked draining, the new one becomes active, and both nodes are
 * republished in one transaction.
 */
public enum PlacementState {

    /**
     * Chosen but not yet carried in a spec.
     *
     * <p>Nothing in this package writes it. The panel cannot observe "the node has applied
     * this" without reading a node-owned column, and it already has two better answers to
     * that question - {@code node_status.applied_generation} for the spec as a whole and
     * {@code placement.reported_state} for this workload in particular. A third, weaker
     * copy of the same fact, written from the panel's side and therefore always a guess,
     * is exactly the kind of column that drifts. The value stays in the vocabulary because
     * an operator importing placements by hand has a use for it and because removing a
     * CHECK value costs a migration.
     */
    PLANNED,

    /** The binding the rest of the panel means when it asks where a service runs. */
    ACTIVE,

    /**
     * Being evacuated. A replacement is {@link #ACTIVE} on another node, and this row
     * survives until the node confirms the workload is gone, so the spec keeps carrying it
     * and the old machine keeps serving until the new one takes over.
     */
    DRAINING,

    /**
     * Historical. Kept so the audit trail can answer "where did this run in March", which
     * is a question that only ever gets asked after the row would have been deleted.
     */
    RELEASED;

    /** Whether a spec built for the node still carries this binding. */
    public boolean isLive() {
        return this != RELEASED;
    }

    /** Whether this is the binding "where does this service run" resolves to. */
    public boolean isCurrent() {
        return this == ACTIVE || this == PLANNED;
    }
}
