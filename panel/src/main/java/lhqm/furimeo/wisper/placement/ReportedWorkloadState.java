package lhqm.furimeo.wisper.placement;

import lhqm.furimeo.wisper.proto.v1.WorkloadPhase;

/**
 * What the node says the workload is doing. Mirrors the
 * {@code placement_reported_state_known} CHECK.
 *
 * <p>Written only by {@link RecordWorkloadStatus}, from a status batch, and by nothing
 * else (schema.md §2). It is deliberately coarser than {@link WorkloadPhase} on the wire:
 * the wire distinguishes the phases a reconcile loop has to act on, and this column holds
 * the ones a person reading a status pill can act on. Pulling and starting are both
 * "coming up" to a customer waiting for a deploy, and a failed start and a crash loop are
 * both "it is not running and that is not what you asked for".
 */
public enum ReportedWorkloadState {

    /** Accepted into the spec, nothing created yet. */
    PENDING,

    /** Coming up: pulling an image, or starting a container that exists. */
    CREATING,

    /** Up, and its health check - if it has one - is passing. */
    RUNNING,

    /** Down on purpose. The customer asked for this, or a run-once job finished. */
    STOPPED,

    /** Exited when it should not have, or is restarting faster than it stays up. */
    CRASHED,

    /** Up, and failing its health check. Reported, not restarted: the panel decides. */
    DEGRADED,

    /**
     * Docker did not answer, so the node has nothing to report about this workload.
     *
     * <p>Not "gone". A container the daemon cannot see is almost always running perfectly
     * well behind a daemon that is restarting, and treating an unanswered query as a
     * deletion is the fastest way to remove a customer's data (AGENTS.md §4.5).
     */
    UNKNOWN;

    /** The wire phase, collapsed onto the vocabulary the column accepts. */
    public static ReportedWorkloadState from(WorkloadPhase phase) {
        return switch (phase) {
            case WORKLOAD_PHASE_PENDING -> PENDING;
            case WORKLOAD_PHASE_PULLING, WORKLOAD_PHASE_STARTING -> CREATING;
            case WORKLOAD_PHASE_RUNNING -> RUNNING;
            case WORKLOAD_PHASE_UNHEALTHY -> DEGRADED;
            case WORKLOAD_PHASE_STOPPED -> STOPPED;
            case WORKLOAD_PHASE_FAILED, WORKLOAD_PHASE_CRASH_LOOPING -> CRASHED;
            case WORKLOAD_PHASE_UNKNOWN, WORKLOAD_PHASE_UNSPECIFIED, UNRECOGNIZED -> UNKNOWN;
        };
    }

    /**
     * Whether this state carries no information about the workload.
     *
     * <p>{@link RecordWorkloadStatus} refuses to overwrite a known state with one of these
     * when the node has flagged its batch as partial.
     */
    public boolean isUninformative() {
        return this == UNKNOWN;
    }

    /** The word on the status pill. */
    public String label() {
        return switch (this) {
            case PENDING -> "Pending";
            case CREATING -> "Starting";
            case RUNNING -> "Running";
            case STOPPED -> "Stopped";
            case CRASHED -> "Crashed";
            case DEGRADED -> "Unhealthy";
            case UNKNOWN -> "Unknown";
        };
    }
}
