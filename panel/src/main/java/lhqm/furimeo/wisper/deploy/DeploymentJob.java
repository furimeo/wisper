package lhqm.furimeo.wisper.deploy;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code deploy-run} job: hand this deployment to a node.
 *
 * <p>An id and nothing else. The row already holds every decision the request made, and a
 * payload that carried a copy of them would be a second version of the truth that ages in
 * {@code scheduled_tasks} while somebody edits the service - so a build would run against
 * settings the customer had already changed.
 *
 * <p>{@link Serializable} because db-scheduler writes {@code task_data} as bytes and, with
 * no {@code Serializer} bean configured, that is Java serialization. A record serialises
 * through its canonical constructor, so the field is reconstructed by the same validation
 * that built it.
 */
public record DeploymentJob(UUID deploymentId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public DeploymentJob {
        if (deploymentId == null) {
            throw new IllegalArgumentException("A deployment job needs a deployment id");
        }
    }

    /**
     * The db-scheduler instance id for this job.
     *
     * <p>The deployment id, so pressing deploy twice on one row cannot produce two builds:
     * {@code scheduled_tasks} is keyed by {@code (task_name, task_instance)} and the second
     * enqueue is refused.
     */
    public String instanceId() {
        return deploymentId.toString();
    }
}
