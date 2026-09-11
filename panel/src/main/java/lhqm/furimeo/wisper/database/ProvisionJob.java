package lhqm.furimeo.wisper.database;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code database-provision} job: create this database on its node.
 *
 * <p>An id and nothing else. The row already holds every decision the request made - the
 * name, the login, the encrypted password, the engine, the quota - and a payload carrying
 * copies of them would be a second version of the truth ageing in {@code scheduled_tasks}
 * while somebody raises the quota on the screen.
 *
 * <p>{@link Serializable} because db-scheduler writes {@code task_data} as bytes and, with
 * no {@code Serializer} bean configured, that is Java serialization. A record serialises
 * through its canonical constructor, so the field is reconstructed by the same validation
 * that built it.
 */
public record ProvisionJob(UUID managedDatabaseId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProvisionJob {
        if (managedDatabaseId == null) {
            throw new IllegalArgumentException("A provision job needs a managed database id");
        }
    }

    /**
     * The db-scheduler instance id for this job.
     *
     * <p>The database's own id, so a customer who double-taps "create" on a phone cannot
     * produce two provision commands: {@code scheduled_tasks} is keyed by
     * {@code (task_name, task_instance)} and the second enqueue is refused.
     */
    public String instanceId() {
        return managedDatabaseId.toString();
    }
}
