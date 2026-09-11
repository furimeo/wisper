package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code cron_task} table. */
public interface CronTaskRepository extends ListCrudRepository<CronTask, UUID> {

    List<CronTask> findByServiceIdOrderByName(UUID serviceId);

    Optional<CronTask> findByServiceIdAndName(UUID serviceId, String name);

    boolean existsByServiceIdAndName(UUID serviceId, String name);

    /** For the tab badge on the service overview. */
    long countByServiceId(UUID serviceId);

    /**
     * The entries the spec builder sends, which is only the enabled ones - matching
     * {@code cron_task_enabled_by_service_idx}, a partial index on exactly that predicate.
     * A disabled entry stays in the table so its history and its schedule survive being
     * switched off.
     */
    @Query("""
            SELECT * FROM cron_task
             WHERE service_id = :serviceId
               AND enabled
             ORDER BY name
            """)
    List<CronTask> findEnabledIn(@Param("serviceId") UUID serviceId);
}
