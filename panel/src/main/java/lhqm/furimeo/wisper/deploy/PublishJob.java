package lhqm.furimeo.wisper.deploy;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code deploy-publish} job: put this deployment in front of
 * customers.
 *
 * <p>A separate job from {@code deploy-run}, and separate on purpose. Publishing is
 * reached from two places that have nothing else in common - a build finishing on a node,
 * and an app deployment that has no build at all - and it is the step that must survive
 * the panel restarting between "the build succeeded" and "the symlink moved". A job row
 * survives that; a callback in memory does not.
 *
 * @param deploymentId the deployment to promote. Also the instance id, so a build result
 *                     redelivered after a reconnect enqueues nothing new
 */
public record PublishJob(UUID deploymentId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public PublishJob {
        if (deploymentId == null) {
            throw new IllegalArgumentException("A publish job needs a deployment id");
        }
    }

    /** The db-scheduler instance id: the deployment being published. */
    public String instanceId() {
        return deploymentId.toString();
    }
}
