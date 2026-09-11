package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the {@code env_var} table.
 *
 * <p>Ordered by name everywhere. An environment list that comes back in insertion order
 * changes shape every time somebody adds a variable, and the customer scanning it for
 * {@code DATABASE_URL} has to start again.
 */
public interface EnvVarRepository extends ListCrudRepository<EnvVar, UUID> {

    List<EnvVar> findByServiceIdOrderByName(UUID serviceId);

    Optional<EnvVar> findByServiceIdAndName(UUID serviceId, String name);

    boolean existsByServiceIdAndName(UUID serviceId, String name);

    /** For the tab badge on the service overview. */
    long countByServiceId(UUID serviceId);
}
