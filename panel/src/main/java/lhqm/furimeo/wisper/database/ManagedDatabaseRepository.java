package lhqm.furimeo.wisper.database;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code managed_database} table.
 *
 * <p>There is no "find by id and organization" taking a single id. A database is two
 * joins from its tenant - through {@code project} - and the ownership walk has exactly one
 * owner, {@code org.ResolveMembership}. Callers resolve the membership first and then load
 * by id, and {@link #findOwnedBy} is the pairing of the two so the check and the row it
 * protects cannot come apart.
 */
public interface ManagedDatabaseRepository extends ListCrudRepository<ManagedDatabase, UUID> {

    /**
     * One database, but only if it belongs to this organization.
     *
     * <p>Walks {@code project} rather than reading a denormalised column, because there
     * is not one: two places to read ownership from is one place to get it wrong
     * (schema.md §3).
     */
    @Query("""
            SELECT md.* FROM managed_database md
              JOIN project p ON p.id = md.project_id
             WHERE md.id = :databaseId AND p.organization_id = :organizationId
            """)
    Optional<ManagedDatabase> findOwnedBy(@Param("databaseId") UUID databaseId,
                                          @Param("organizationId") UUID organizationId);

    /** Everything one project has, for the project's own database tab. */
    List<ManagedDatabase> findByProjectIdOrderByName(UUID projectId);

    /**
     * Which project a database belongs to, and nothing else.
     *
     * <p>The first half of resolving a request that names only a database id. The
     * controller reads this, hands the project to {@code org.ResolveMembership}, and loads
     * the row with the organization it gets back - so the ownership walk still happens in
     * the one class that owns it, and an id belonging to somebody else produces a 404 from
     * the membership lookup rather than a row from here.
     */
    @Query("SELECT project_id FROM managed_database WHERE id = :databaseId")
    Optional<UUID> findProjectIdOf(@Param("databaseId") UUID databaseId);

    /**
     * Whether this engine already carries a database with this name.
     *
     * <p>{@code managed_database_engine_name_key} refuses the duplicate anyway; asking
     * first is what turns it into a sentence under the input. The name already carries the
     * tenant's slug (see {@link DatabaseIdentifier}), so a true match here is the same
     * customer asking twice, never a stranger having taken the word.
     */
    @Query("""
            SELECT count(*) > 0 FROM managed_database
             WHERE database_engine_id = :engineId AND name = :name
            """)
    boolean existsOnEngineNamed(@Param("engineId") UUID engineId, @Param("name") String name);

    /**
     * Every database on every engine one node hosts.
     *
     * <p>Read once per status batch, which is what stops {@link RecordDatabaseStatus}
     * issuing a select per grant every fifteen seconds. It is also the authorization
     * check for a report: a grant id that is not in this set belongs to another machine,
     * and the node that named it may not write to it.
     */
    @Query("""
            SELECT md.* FROM managed_database md
              JOIN database_engine de ON de.id = md.database_engine_id
             WHERE de.node_id = :nodeId
            """)
    List<ManagedDatabase> findAllOnNode(@Param("nodeId") UUID nodeId);

    /**
     * Which tenant a database belongs to.
     *
     * <p>For the entries {@link EnforceDatabaseQuota} writes with nobody signed in. An
     * audit line with no organization is one the customer never sees, and "why was my
     * database suspended" is precisely the question that line exists to answer.
     */
    @Query("""
            SELECT p.organization_id FROM managed_database md
              JOIN project p ON p.id = md.project_id
             WHERE md.id = :databaseId
            """)
    Optional<UUID> findOrganizationIdOf(@Param("databaseId") UUID databaseId);
}
