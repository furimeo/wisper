package lhqm.furimeo.wisper.backup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code backup_destination} table. */
public interface BackupDestinationRepository
        extends ListCrudRepository<BackupDestination, UUID> {

    /**
     * The destinations an organization may choose: its own, plus the platform-wide ones an
     * operator configured. Disabled rows are excluded - a picker that offers something the
     * next save will refuse is a form that lies.
     */
    @Query("""
            SELECT * FROM backup_destination
             WHERE enabled
               AND (organization_id = :organizationId OR organization_id IS NULL)
             ORDER BY organization_id NULLS LAST, name
            """)
    List<BackupDestination> findUsableBy(@Param("organizationId") UUID organizationId);

    /**
     * Everything an organization's screen shows, disabled rows included, so a customer can
     * turn one back on.
     */
    @Query("""
            SELECT * FROM backup_destination
             WHERE organization_id = :organizationId OR organization_id IS NULL
             ORDER BY organization_id NULLS LAST, name
            """)
    List<BackupDestination> findVisibleTo(@Param("organizationId") UUID organizationId);

    /** Every platform-wide destination, for the operator screen. */
    @Query("SELECT * FROM backup_destination WHERE organization_id IS NULL ORDER BY name")
    List<BackupDestination> findPlatformWide();

    /**
     * The name check behind {@code backup_destination_scope_name_key}.
     *
     * <p>{@code IS NOT DISTINCT FROM} rather than {@code =}, because the index is
     * {@code NULLS NOT DISTINCT} and the scope being compared is null for a platform-wide
     * destination. An equality test would silently pass and the insert would then fail on
     * the constraint.
     */
    @Query("""
            SELECT count(*) > 0 FROM backup_destination
             WHERE organization_id IS NOT DISTINCT FROM :organizationId
               AND name = :name
            """)
    boolean existsInScope(@Param("organizationId") UUID organizationId,
                          @Param("name") String name);

    Optional<BackupDestination> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
