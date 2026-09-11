package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * Which node runs a service, and what that node says about the copy it is running.
 *
 * <p>The binding is sticky on purpose. A service with a volume is pinned to the machine
 * holding the bytes, and moving it is a deliberate migration rather than something the
 * scheduler does while nobody is watching (design §7.8). Nothing in this package ever
 * reschedules by itself.
 *
 * <p>This row has two owners and the split runs down the middle of the component list.
 * The panel writes {@link #state}, {@link #pinned}, {@link #reason}, {@link #placedAt} and
 * {@link #releasedAt}; everything from {@link #reportedState} to {@link #health} is
 * written by {@link RecordWorkloadStatus} out of a node's status batch and by nothing else
 * (schema.md §2).
 *
 * <p>Because of that split this record is used for <strong>inserts and reads only</strong>.
 * Every state change goes through a targeted {@code UPDATE} on
 * {@link PlacementRepository}, not through {@code save()}: Spring Data JDBC writes every
 * mapped column on an update, so saving a record loaded a second before a status batch
 * landed would quietly blank what the node had just reported.
 *
 * @param pinned  true once the service has storage on this node. A pinned placement is
 *                never evacuated by a drain; it is listed for an operator to decide about.
 * @param reason  why this binding exists or ended, in a sentence. Shown on the placement
 *                screen and copied into the audit entry.
 * @param version null means new; see schema.md §1
 */
public record Placement(
        @Id UUID id,
        UUID serviceId,
        UUID nodeId,
        PlacementState state,
        boolean pinned,
        String reason,
        Instant placedAt,
        Instant releasedAt,

        ReportedWorkloadState reportedState,
        Instant reportedAt,
        String containerId,
        String runningImageDigest,
        int restartCount,
        Integer lastExitCode,
        String lastError,
        WorkloadHealth health,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * A fresh binding, active from this instant, with nothing reported about it yet.
     *
     * <p>{@code placedAt} is passed in rather than defaulted in the DDL because Spring
     * Data JDBC writes every mapped column on insert, nulls included, and would overwrite
     * the {@code DEFAULT now()} with NULL against a {@code NOT NULL} column.
     *
     * <p>It starts {@link PlacementState#ACTIVE} rather than {@code PLANNED}: the binding
     * is real the moment it is written, the spec carrying it is published in the same
     * transaction, and the rest of the panel - {@code LocateService}, the service list -
     * resolves "where does this run" through the active row. See
     * {@link PlacementState#PLANNED} for why that state is not written here.
     */
    public static Placement active(UUID id, UUID serviceId, UUID nodeId, boolean pinned,
                                   String reason, Instant at) {
        return new Placement(id, serviceId, nodeId, PlacementState.ACTIVE, pinned,
                reason == null ? "" : reason, at, null,
                null, null, null, null, 0, null, null, null,
                null, null, null);
    }

    /** Whether a spec built for {@link #nodeId} still carries this service. */
    public boolean isLive() {
        return state.isLive();
    }

    /** Whether this is the row that answers "where does this service run". */
    public boolean isCurrent() {
        return state.isCurrent();
    }

    /** Whether a drain may move this service without moving bytes with it. */
    public boolean isEvacuable() {
        return !pinned && state.isCurrent();
    }
}
