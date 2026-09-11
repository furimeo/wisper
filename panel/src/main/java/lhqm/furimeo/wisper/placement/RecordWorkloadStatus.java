package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.WorkloadStatus;

/**
 * Writes what a node reports about the workloads it is holding.
 *
 * <p>Called by {@code grpc} for the {@code workloads} half of every {@code StatusBatch}
 * (panel-ports.md §3), and the only writer of the node-owned columns on {@code placement}.
 * No controller, no use-case and no admin screen may touch one of them: the panel owns
 * intent, the node owns fact, and a column written from both directions is the rule this
 * whole split exists to protect (AGENTS.md §4.2).
 *
 * <h2>Two things it deliberately does not do</h2>
 *
 * <p><strong>It never concludes that something has gone away.</strong> A workload the batch
 * does not mention is left exactly as it was. Absence from a status report is not evidence
 * of anything - the node reports what it observed, and what it did not observe it does not
 * mention. Deleting or blanking a row on that basis is the fastest way to lose a
 * customer's container (AGENTS.md §4.5).
 *
 * <p><strong>It never moves {@code placement.state}.</strong> That column is the panel's
 * decision about which machine holds a service, and a status report is not a decision. A
 * workload that crashed has not moved.
 */
@Component
public class RecordWorkloadStatus {

    private static final Logger log = LoggerFactory.getLogger(RecordWorkloadStatus.class);

    private final PlacementRepository placements;

    public RecordWorkloadStatus(PlacementRepository placements) {
        this.placements = placements;
    }

    /**
     * Applies one batch.
     *
     * @param observedAt when the node looked, not when this arrived. A chart drawn on
     *                   arrival time shows spikes that never happened.
     * @param partial    Docker did not answer, so the figures are last-known rather than
     *                   observed. Statuses that carry no information are then skipped
     *                   instead of overwriting a good state with {@code UNKNOWN} - a
     *                   customer whose service goes amber every time a daemon restarts
     *                   learns to ignore amber.
     */
    @Transactional
    public void accept(UUID nodeId, List<WorkloadStatus> statuses, Instant observedAt,
                       boolean partial) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        Instant at = observedAt == null ? Instant.now() : observedAt;
        for (WorkloadStatus status : statuses) {
            UUID serviceId = serviceIdOf(status, nodeId);
            if (serviceId == null) {
                continue;
            }
            ReportedWorkloadState state = ReportedWorkloadState.from(status.getPhase());
            if (partial && state.isUninformative()) {
                continue;
            }
            int updated = placements.recordReport(nodeId, serviceId, state.name(), at,
                    emptyToNull(status.getContainerId()),
                    emptyToNull(status.getImageDigest()),
                    Math.max(0, status.getRestartCount()),
                    exitCodeOf(status, state),
                    emptyToNull(status.getMessage()),
                    WorkloadHealth.from(status.getPhase()).name());
            if (updated == 0) {
                // The node is still running something this panel no longer places there:
                // a service deleted while the node was offline, or a workload left over
                // from a migration. Reconciliation removes it from the node on the next
                // pass, because it is not in the spec any more. Nothing to write here.
                log.debug("Status for workload {} on node {} matched no live placement",
                        status.getWorkloadId(), nodeId);
            }
        }
    }

    /**
     * The service a reported workload belongs to.
     *
     * <p>A workload id <em>is</em> a service id - that is the convention the spec builder
     * and {@code deploy} both write, and it is what makes the two sides line up without a
     * lookup table. Anything else came from a container this panel did not create, so it
     * is logged and dropped rather than thrown on: one strange line must not cost the rest
     * of the batch.
     */
    private static UUID serviceIdOf(WorkloadStatus status, UUID nodeId) {
        try {
            return UUID.fromString(status.getWorkloadId());
        } catch (IllegalArgumentException notAnId) {
            log.warn("Node {} reported a status for workload id \"{}\", which is not a service id",
                    nodeId, status.getWorkloadId());
            return null;
        }
    }

    /**
     * The exit code, only where it means something.
     *
     * <p>{@code WorkloadStatus.exit_code} is a proto3 scalar, so "no exit" and "exited
     * zero" are the same wire value. Storing that zero against a running container would
     * make every service look like it had finished successfully a moment ago.
     */
    private static Integer exitCodeOf(WorkloadStatus status, ReportedWorkloadState state) {
        boolean hasExited = state == ReportedWorkloadState.STOPPED
                || state == ReportedWorkloadState.CRASHED;
        return hasExited ? status.getExitCode() : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
