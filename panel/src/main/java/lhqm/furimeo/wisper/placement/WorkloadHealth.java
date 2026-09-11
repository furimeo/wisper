package lhqm.furimeo.wisper.placement;

import lhqm.furimeo.wisper.proto.v1.WorkloadPhase;

/**
 * Whether the node considers the running copy well. Mirrors the
 * {@code placement_health_known} CHECK.
 *
 * <p>Three values and no fourth. A container the node cannot see, one that has never
 * started and one that is deliberately stopped are all {@link #UNKNOWN} here, because none
 * of them is evidence of anything: painting a stopped service amber would teach an
 * operator to ignore amber.
 */
public enum WorkloadHealth {

    /** Running, and passing its health check if it has one. */
    HEALTHY,

    /** Running and failing its check, or not running when it was asked to be. */
    UNHEALTHY,

    /** Nothing is known: not started, deliberately stopped, or Docker did not answer. */
    UNKNOWN;

    /** What a reported phase implies about health. */
    public static WorkloadHealth from(WorkloadPhase phase) {
        return switch (phase) {
            case WORKLOAD_PHASE_RUNNING -> HEALTHY;
            case WORKLOAD_PHASE_UNHEALTHY, WORKLOAD_PHASE_FAILED, WORKLOAD_PHASE_CRASH_LOOPING ->
                    UNHEALTHY;
            default -> UNKNOWN;
        };
    }
}
