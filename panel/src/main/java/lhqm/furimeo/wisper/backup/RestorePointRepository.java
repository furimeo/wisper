package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code restore_point} table: the snapshots themselves. */
public interface RestorePointRepository extends ListCrudRepository<RestorePoint, UUID> {

    Optional<RestorePoint> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Every snapshot a policy has produced and still owns, newest first.
     *
     * <p>This is what retention is computed over, so it deliberately includes the ones
     * that failed and the ones already expired: the tiering counts kept snapshots, and a
     * query that hid the expired ones would re-select a new victim on every sweep.
     */
    List<RestorePoint> findByBackupIdOrderByStartedAtDesc(UUID backupId);

    /**
     * The snapshots retention has to choose between: the ones that exist.
     *
     * <p>Ordered newest first, which is the order {@link RetentionPolicy} expects.
     */
    @Query("""
            SELECT * FROM restore_point
             WHERE backup_id = :backupId
               AND state = 'AVAILABLE'
             ORDER BY started_at DESC
            """)
    List<RestorePoint> findAvailableForBackup(@Param("backupId") UUID backupId);

    /**
     * Snapshots with no policy whose clock has run out.
     *
     * <p>Served by {@code restore_point_expiring_idx}. Two kinds of row land here: a
     * safety snapshot, which belongs to no series and expires purely on a clock, and a
     * snapshot whose policy was deleted - {@code backup_id} is {@code SET NULL}, so the
     * per-policy pass cannot see it and without this it would sit at the destination for
     * ever.
     *
     * <p>{@code backup_id IS NULL} is what keeps the two halves of retention from fighting.
     * A snapshot that still has a policy is decided by {@link RetentionPolicy}, whose
     * {@code keepLast} deliberately outranks any age rule - it is what stops a dormant
     * project's only backup ageing out. An age sweep that also touched those rows would
     * delete exactly the snapshot that rule exists to protect.
     */
    @Query("""
            SELECT * FROM restore_point
             WHERE state = 'AVAILABLE'
               AND backup_id IS NULL
               AND expires_at IS NOT NULL
               AND expires_at <= :now
             ORDER BY expires_at
             LIMIT :limit
            """)
    List<RestorePoint> findOrphansPastExpiry(@Param("now") Instant now,
                                             @Param("limit") int limit);

    /** The customer's list, newest first. */
    @Query("""
            SELECT * FROM restore_point
             WHERE organization_id = :organizationId
             ORDER BY started_at DESC
             LIMIT :limit
            """)
    List<RestorePoint> findRecentFor(@Param("organizationId") UUID organizationId,
                                     @Param("limit") int limit);

    /** What this volume can be restored from, newest first. */
    @Query("""
            SELECT * FROM restore_point
             WHERE volume_id = :volumeId
               AND state = 'AVAILABLE'
             ORDER BY started_at DESC
            """)
    List<RestorePoint> findRestorableForVolume(@Param("volumeId") UUID volumeId);

    /** What this database can be restored from, newest first. */
    @Query("""
            SELECT * FROM restore_point
             WHERE managed_database_id = :managedDatabaseId
               AND state = 'AVAILABLE'
             ORDER BY started_at DESC
            """)
    List<RestorePoint> findRestorableForDatabase(
            @Param("managedDatabaseId") UUID managedDatabaseId);

    /** Snapshots still pointing at a destination, which is why deleting one is refused. */
    long countByDestinationId(UUID destinationId);

    /**
     * A run the panel started and never heard the end of.
     *
     * <p>Its node dropped the stream, or the panel restarted while the command was in
     * flight. Left alone the row says {@code RUNNING} for ever and the snapshot count on
     * the screen never settles.
     */
    @Query("""
            SELECT * FROM restore_point
             WHERE state = 'RUNNING'
               AND started_at <= :before
             ORDER BY started_at
             LIMIT :limit
            """)
    List<RestorePoint> findAbandoned(@Param("before") Instant before,
                                     @Param("limit") int limit);
}
