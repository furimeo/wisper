package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in a project's service list: what the customer asked for, next to what the node
 * says is happening.
 *
 * <p>Both halves are here on purpose, and they are labelled differently on purpose.
 * {@code desiredState} is intent and comes from {@code service}; {@code reportedState} and
 * {@code health} are fact and come from {@code placement}, where a node wrote them. The
 * interesting rows are the ones where the two disagree - asked to run, reported crashed -
 * and a list that showed only one of them could not draw that.
 *
 * <p>The node's two values are strings rather than enums. They are
 * {@code placement.reported_state} and {@code placement.health}, whose vocabularies belong
 * to the {@code placement} package and to {@code workload.proto}; copying them into a
 * third enum here would be a fourth place to update when a value is added, for a value
 * this record only passes to a status pill. Null means the node has not reported yet.
 *
 * @param nodeId the node with the {@code ACTIVE} placement, or null when nothing holds it
 */
public record ServiceSummary(
        UUID id,
        UUID projectId,
        String name,
        String slug,
        ServiceKind kind,
        DesiredState desiredState,
        Instant archivedAt,
        String image,
        BuildPreset buildPreset,
        UUID nodeId,
        String reportedState,
        String health,
        Instant reportedAt,
        Instant createdAt) {

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Whether the customer has asked for this to be up. */
    public boolean isWantedRunning() {
        return desiredState == DesiredState.RUNNING;
    }

    /**
     * Whether intent and fact disagree in the direction worth a warning: the customer
     * asked for it to run and the node is not reporting it running.
     *
     * <p>A node that has reported nothing yet is not drift. It is a service on its way up,
     * or one whose node is reconnecting, and painting that red teaches customers to ignore
     * red.
     */
    public boolean isDrifting() {
        return isWantedRunning() && reportedState != null && !"RUNNING".equals(reportedState);
    }
}
