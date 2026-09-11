package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.UUID;

/**
 * One service and the node currently holding it, as the operator's board shows it.
 *
 * <p>Deliberately flat and deliberately not {@link PlacedService}. That record is the
 * spec builder's view - what a node has to be told to run - and it carries an image
 * digest and a desired state because the spec needs them. This one answers a different
 * question, "who is where and can it move", and needs the tenant and the project to
 * answer it. Reusing the spec's record here would mean widening it with two columns the
 * spec has no use for.
 *
 * @param pinned   true once a volume exists on this node, which is what makes a move a
 *                 decision rather than a scheduling detail: the data does not follow
 * @param draining a replacement placement exists elsewhere and this one is being let go.
 *                 Shown because a board that hid it would look like a service running in
 *                 two places for no reason
 */
public record PlacedServiceRow(
        UUID serviceId,
        String serviceName,
        String serviceSlug,
        String kind,
        String desiredState,
        UUID projectId,
        String projectSlug,
        UUID organizationId,
        String organizationName,
        UUID nodeId,
        String nodeName,
        String state,
        boolean pinned,
        boolean draining,
        long volumeBytes,
        Instant placedAt,
        String reason) {

    /** Whether a move is offered at all: a draining placement is already going somewhere. */
    public boolean isMovable() {
        return !draining;
    }

    /** Whether moving it would leave data behind, which the operator must be told first. */
    public boolean isCarryingData() {
        return volumeBytes > 0;
    }
}
