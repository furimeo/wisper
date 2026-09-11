package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code service} table.
 *
 * <p>There is no "find by id and organization" here, because a service is two joins away
 * from its tenant and the join has one owner: {@code org.ResolveMembership.forService}.
 * A use-case resolves the membership first and then loads by id, which is the same
 * guarantee in the order that keeps the ownership walk in one place (schema.md §3).
 *
 * <p>The screen queries - a project's services with what the node reports about each -
 * are in {@link ListServicesInProject}, because they join {@code placement} and this
 * repository maps only {@code service}.
 */
public interface ServiceRepository extends ListCrudRepository<Service, UUID> {

    boolean existsByProjectIdAndSlug(UUID projectId, String slug);

    Optional<Service> findByProjectIdAndSlug(UUID projectId, String slug);

    /**
     * One service, but only if it is inside the given tenant.
     *
     * <p>Every use-case in this package starts with this call. A caller holding a
     * {@code Membership} has proved what it may do in <em>one</em> organization; loading a
     * service by primary key alone would happily hand it one from another, and the
     * authorization check would have been performed against the wrong subject. Pairing
     * the two in the query means the check and the row cannot come apart, and an id from
     * another tenant produces the same empty result a nonexistent id does.
     */
    @Query("""
            SELECT s.* FROM service s
              JOIN project p ON p.id = s.project_id
             WHERE s.id = :serviceId
               AND p.organization_id = :organizationId
            """)
    Optional<Service> findOwnedBy(@Param("serviceId") UUID serviceId,
                                  @Param("organizationId") UUID organizationId);

    /**
     * The services in a project that are not archived, alphabetically.
     *
     * <p>Matches {@code service_by_project_idx}, which is partial on exactly this
     * predicate and sorted on exactly this column.
     */
    @Query("""
            SELECT * FROM service
             WHERE project_id = :projectId
               AND archived_at IS NULL
             ORDER BY name
            """)
    List<Service> findLiveIn(@Param("projectId") UUID projectId);

    /** The archived ones, for the restore that follows a project coming back. */
    @Query("""
            SELECT * FROM service
             WHERE project_id = :projectId
               AND archived_at IS NOT NULL
             ORDER BY name
            """)
    List<Service> findArchivedIn(@Param("projectId") UUID projectId);
}
