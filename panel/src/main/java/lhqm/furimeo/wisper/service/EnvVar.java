package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A plain environment variable: stored as typed, shown in full, sent to the node as-is.
 *
 * <p>Anything that must not be readable is a {@link Secret} instead. The split is a
 * different table rather than an {@code is_secret} column because it changes three
 * behaviours - how the value is stored, whether a GET returns it, and what an audit entry
 * may contain - and a flag makes all three a runtime {@code if} somebody eventually
 * forgets (schema.md §3).
 *
 * @param buildTime also handed to the ephemeral build container, not only to the running
 *                  workload. This is how a static site gets an API base URL baked into its
 *                  bundle, and it is the only kind of variable a site can usefully have -
 *                  a site has no process to read one at runtime.
 */
public record EnvVar(
        @Id UUID id,
        UUID serviceId,
        String name,
        String value,
        boolean buildTime,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A new variable on a service. */
    public static EnvVar of(UUID id, UUID serviceId, String name, String value,
                            boolean buildTime) {
        return new EnvVar(id, serviceId, name, value, buildTime, null, null, null);
    }

    /** The same variable with a new value; the name is its identity and does not move. */
    public EnvVar withValue(String newValue, boolean newBuildTime) {
        return new EnvVar(id, serviceId, name, newValue, newBuildTime, createdAt, updatedAt,
                version);
    }
}
