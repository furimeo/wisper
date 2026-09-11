package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code backup} table: the policies, not the snapshots. */
public interface BackupRepository extends ListCrudRepository<Backup, UUID> {

    List<Backup> findByOrganizationIdOrderByName(UUID organizationId);

    Optional<Backup> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /** Policies pointed at a destination. What makes deleting one a refusal rather than a
     * cascade. */
    long countByDestinationId(UUID destinationId);

    List<Backup> findByVolumeId(UUID volumeId);

    List<Backup> findByManagedDatabaseId(UUID managedDatabaseId);

    /**
     * Policies whose cron has come round, oldest due first.
     *
     * <p>Served by {@code backup_due_idx}, which is partial on exactly this predicate. The
     * limit is there so one sweep cannot enqueue ten thousand runs after a panel has been
     * down for a week; the next sweep picks up the rest a few seconds later.
     */
    @Query("""
            SELECT * FROM backup
             WHERE enabled
               AND schedule IS NOT NULL
               AND next_run_at IS NOT NULL
               AND next_run_at <= :now
             ORDER BY next_run_at
             LIMIT :limit
            """)
    List<Backup> findDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Every policy that could still produce or hold snapshots, for the retention sweep.
     *
     * <p>Disabled policies are included deliberately. Turning a policy off stops it taking
     * new snapshots; it does not mean the customer wants the old ones kept for ever, and a
     * sweep that skipped them would leave a disabled policy's snapshots accumulating with
     * nothing ever ageing them out.
     */
    @Query("SELECT * FROM backup ORDER BY created_at")
    List<Backup> findAllForRetention();
}
