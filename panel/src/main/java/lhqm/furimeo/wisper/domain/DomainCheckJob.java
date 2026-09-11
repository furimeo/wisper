package lhqm.furimeo.wisper.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code domain-verify} job (panel-ports.md §2.3).
 *
 * <p>An id and nothing else. The row already holds the hostname, the token and where the
 * last check got to; a payload carrying copies would age in {@code scheduled_tasks} while
 * the customer edited the domain, and a check would then run against a hostname that is no
 * longer the one stored.
 *
 * <p>{@link Serializable} because db-scheduler writes {@code task_data} as bytes and, with
 * no {@code Serializer} bean configured, that is Java serialization. A record serialises
 * through its canonical constructor, so the field is reconstructed by the same validation
 * that built it.
 */
public record DomainCheckJob(UUID domainId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public DomainCheckJob {
        if (domainId == null) {
            throw new IllegalArgumentException("A domain check needs a domain id");
        }
    }

    /**
     * The db-scheduler instance id for this job.
     *
     * <p>The domain's id, so a customer pressing "Check now" while a check is already queued
     * gets the queued one: {@code scheduled_tasks} is keyed by
     * {@code (task_name, task_instance)} and the second enqueue is refused.
     */
    public String instanceId() {
        return domainId.toString();
    }
}
