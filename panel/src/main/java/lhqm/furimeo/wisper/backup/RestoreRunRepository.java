package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code restore_run} table. */
public interface RestoreRunRepository extends ListCrudRepository<RestoreRun, UUID> {

    /** "Has this snapshot ever been restored, and did it work" - the verify badge. */
    List<RestoreRun> findByRestorePointIdOrderByCreatedAtDesc(UUID restorePointId);

    /**
     * The in-flight run holding a volume, if there is one.
     *
     * <p>Asked before inserting, so a customer who presses restore twice is told what is
     * already happening rather than being shown the constraint violation from
     * {@code restore_run_one_active_per_volume_idx}.
     */
    @Query("""
            SELECT * FROM restore_run
             WHERE target_volume_id = :volumeId
               AND state IN ('QUEUED', 'RUNNING')
             LIMIT 1
            """)
    Optional<RestoreRun> findActiveForVolume(@Param("volumeId") UUID volumeId);

    /** The same for a managed database. */
    @Query("""
            SELECT * FROM restore_run
             WHERE target_managed_database_id = :managedDatabaseId
               AND state IN ('QUEUED', 'RUNNING')
             LIMIT 1
            """)
    Optional<RestoreRun> findActiveForDatabase(
            @Param("managedDatabaseId") UUID managedDatabaseId);

    /**
     * Runs that have been in flight too long.
     *
     * <p>Served by {@code restore_run_active_idx}. A run whose node went away has to be
     * failed, or the two partial unique indexes keep the target locked against a second
     * attempt for ever - which turns a dropped connection into a target nobody can ever
     * restore again.
     */
    @Query("""
            SELECT * FROM restore_run
             WHERE state IN ('QUEUED', 'RUNNING')
               AND created_at <= :before
             ORDER BY created_at
             LIMIT :limit
            """)
    List<RestoreRun> findStale(@Param("before") Instant before, @Param("limit") int limit);

    /** The restores an organization can see, newest first, for the history panel. */
    @Query("""
            SELECT r.* FROM restore_run r
              JOIN restore_point p ON p.id = r.restore_point_id
             WHERE p.organization_id = :organizationId
             ORDER BY r.created_at DESC
             LIMIT :limit
            """)
    List<RestoreRun> findRecentFor(@Param("organizationId") UUID organizationId,
                                   @Param("limit") int limit);
}
