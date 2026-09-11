package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/** Reads and writes the {@code volume} table. */
public interface VolumeRepository extends ListCrudRepository<Volume, UUID> {

    List<Volume> findByServiceIdOrderByName(UUID serviceId);

    Optional<Volume> findByServiceIdAndName(UUID serviceId, String name);

    boolean existsByServiceIdAndName(UUID serviceId, String name);

    /**
     * Two volumes on one mount point would make the spec ambiguous, and the
     * {@code volume_service_mount_path_key} index refuses it. Asking first turns a
     * constraint violation into a sentence under the input.
     */
    boolean existsByServiceIdAndMountPath(UUID serviceId, String mountPath);

    /** Whether this service has any storage, which is what pins its placement to a node. */
    long countByServiceId(UUID serviceId);
}
