package lhqm.furimeo.wisper.placement;

import java.util.UUID;

import lhqm.furimeo.wisper.proto.v1.DesiredState;
import lhqm.furimeo.wisper.service.Service;

/**
 * One service as a node has to see it: the binding, the intent, the tenant it belongs to
 * and the release that is live.
 *
 * <p>Assembled by {@link LoadPlacedServices} and consumed by everything that puts a piece
 * of a {@code NodeSpec} together. It exists so that those builders take rows and return
 * protobuf and touch no database at all, which is what makes "the same input produces the
 * same spec" a thing a test can assert without a PostgreSQL.
 *
 * @param organizationId    the tenant, which decides the Docker network this workload
 *                          joins. Two customers on one node must not be able to reach each
 *                          other's containers by address.
 * @param releaseId         the deployment currently marked live for this service, as a
 *                          string, or empty when nothing has been published. For a site it
 *                          is the directory under {@code releases/} the {@code current}
 *                          symlink must point at - which is why publishing and rolling back
 *                          are both just a new generation naming a different one, with no
 *                          publish command to disagree with the spec (design §5.5).
 * @param releaseImageDigest the digest resolved when that deployment was built. Pinned so
 *                          a restart cannot silently pick up a moved tag.
 */
public record PlacedService(
        Placement placement,
        Service service,
        UUID organizationId,
        String releaseId,
        String releaseImageDigest) {

    /** The id the node knows this workload by. It is the service id; see {@code deploy}. */
    public String workloadId() {
        return service.id().toString();
    }

    /**
     * What the node should be doing with it.
     *
     * <p>A binding that is being drained is published as stopped rather than left out. Out
     * of the spec means "remove this", which would take the container and its logs with it
     * while the replacement is still coming up; stopped keeps everything and lets the
     * machine let go without destroying anything. The row is released - and the container
     * removed - only once the move is finished.
     */
    public DesiredState desiredState() {
        if (placement.state() == PlacementState.DRAINING) {
            return DesiredState.DESIRED_STATE_STOPPED;
        }
        return service.desiredState().isRunning()
                ? DesiredState.DESIRED_STATE_RUNNING
                : DesiredState.DESIRED_STATE_STOPPED;
    }

    /**
     * The image bytes to run.
     *
     * <p>The digest a deployment resolved wins over the one on the service row: the
     * deployment is what was actually built and tested, and the service row's copy is only
     * a starting point. Empty when neither has one, which means the node resolves the tag
     * itself and reports back what it got.
     */
    public String imageDigest() {
        if (releaseImageDigest != null && !releaseImageDigest.isBlank()) {
            return releaseImageDigest;
        }
        return service.imageDigest() == null ? "" : service.imageDigest();
    }

    /** Whether this service runs a container the node has to keep alive. */
    public boolean isApp() {
        return service.isApp();
    }

    /** Whether this service is a directory of files the edge serves with no process. */
    public boolean isSite() {
        return service.isSite();
    }
}
