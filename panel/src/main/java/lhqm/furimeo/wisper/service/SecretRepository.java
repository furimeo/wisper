package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the {@code secret} table.
 *
 * <p>Every method here returns rows that contain ciphertext, so none of them belongs in a
 * controller. What the screens use is {@link ListSecrets}, a projection with no value
 * column; what the spec builder uses is {@link #findByServiceIdOrderByName}, once, on its
 * way to the node.
 */
public interface SecretRepository extends ListCrudRepository<Secret, UUID> {

    /** For the spec builder. Every row carries an encrypted value. */
    List<Secret> findByServiceIdOrderByName(UUID serviceId);

    Optional<Secret> findByServiceIdAndName(UUID serviceId, String name);

    boolean existsByServiceIdAndName(UUID serviceId, String name);

    /** For the tab badge. Counting rows reveals nothing a name list would not. */
    long countByServiceId(UUID serviceId);
}
