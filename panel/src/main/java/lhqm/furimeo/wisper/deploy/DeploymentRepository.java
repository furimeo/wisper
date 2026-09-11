package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code deployment} table.
 *
 * <p>Every query here matches an index the migration created for it, named in the
 * comment. A query with no index behind it on this table is a sequential scan over every
 * deployment every customer has ever made, on the page they open most.
 *
 * <p>There is no "find by id and organization". A deployment is three joins from its
 * tenant and the ownership walk has exactly one owner, {@code org.ResolveMembership};
 * callers resolve the membership, then load by id and check the service matches.
 */
public interface DeploymentRepository extends ListCrudRepository<Deployment, UUID> {

    /**
     * The deployment list, newest first. Matches {@code deployment_by_service_recent_idx},
     * which is sorted on exactly this column in exactly this direction.
     */
    @Query("""
            SELECT * FROM deployment
             WHERE service_id = :serviceId
             ORDER BY sequence DESC
             LIMIT :limit
            """)
    List<Deployment> findRecent(@Param("serviceId") UUID serviceId, @Param("limit") int limit);

    /** What is serving traffic. Matches {@code deployment_current_idx}. */
    @Query("SELECT * FROM deployment WHERE service_id = :serviceId AND is_current")
    Optional<Deployment> findCurrent(@Param("serviceId") UUID serviceId);

    /**
     * The most recent successful deployment that is not the live one - the default target
     * of the rollback button.
     */
    @Query("""
            SELECT * FROM deployment
             WHERE service_id = :serviceId
               AND status = 'SUCCEEDED'
               AND NOT is_current
             ORDER BY sequence DESC
             LIMIT 1
            """)
    Optional<Deployment> findPreviousRelease(@Param("serviceId") UUID serviceId);

    /**
     * Deployments still owed work, oldest first. Matches
     * {@code deployment_pending_idx}, whose partial predicate is this exact status list.
     */
    @Query("""
            SELECT * FROM deployment
             WHERE service_id = :serviceId
               AND status IN ('QUEUED', 'ASSIGNED', 'BUILDING', 'PUBLISHING')
             ORDER BY queued_at
            """)
    List<Deployment> findInFlight(@Param("serviceId") UUID serviceId);

    /**
     * Deployments that have not been picked up yet and are older than the one just
     * queued. These are what a newer push supersedes: building a commit that a later
     * commit has already replaced spends a node's minutes on something nobody will see.
     */
    @Query("""
            SELECT * FROM deployment
             WHERE service_id = :serviceId
               AND status = 'QUEUED'
               AND sequence < :below
             ORDER BY sequence
            """)
    List<Deployment> findQueuedBelow(@Param("serviceId") UUID serviceId,
                                     @Param("below") long below);

    /**
     * Succeeded releases beyond the newest {@code keep}, oldest first.
     *
     * <p>Two rows are excluded whatever their position, and both exclusions are load
     * bearing. The live one, obviously: pruning it would delete the row for the directory
     * the node is serving from. And the row a live <em>rollback</em> points back at,
     * because that is where the bytes actually are - a customer who rolled back to release
     * three and then deployed six more times is being served by a release that is nowhere
     * near the top of this list.
     */
    @Query("""
            SELECT * FROM deployment d
             WHERE d.service_id = :serviceId
               AND d.status = 'SUCCEEDED'
               AND NOT d.is_current
               AND NOT EXISTS (
                   SELECT 1 FROM deployment live
                    WHERE live.service_id = :serviceId
                      AND live.is_current
                      AND live.rolled_back_from_deployment_id = d.id
               )
               AND d.sequence < (
                   SELECT MIN(newest.sequence) FROM (
                       SELECT sequence FROM deployment
                        WHERE service_id = :serviceId AND status = 'SUCCEEDED'
                        ORDER BY sequence DESC
                        LIMIT :keep
                   ) newest
               )
             ORDER BY d.sequence
            """)
    List<Deployment> findPrunable(@Param("serviceId") UUID serviceId, @Param("keep") int keep);

    /**
     * Takes the live flag off whatever currently holds it.
     *
     * <p>Run immediately before the new release is promoted, in the same transaction. The
     * {@code deployment_current_idx} partial unique index is checked per statement, so
     * two rows can never both be current even for the instant between the two writes.
     */
    @Modifying
    @Query("""
            UPDATE deployment
               SET is_current = false, updated_at = :at, version = version + 1
             WHERE service_id = :serviceId AND is_current
            """)
    int clearCurrent(@Param("serviceId") UUID serviceId, @Param("at") Instant at);

    /**
     * How many deployments this organization started in the last day.
     *
     * <p>The usage figure behind {@code QuotaResource.DEPLOYMENTS_PER_DAY}, which is the
     * one windowed quota: it counts a rolling twenty-four hours rather than rows that
     * exist. Walks the ownership join rather than a denormalised column, for the reason
     * schema.md §3 gives - a second place to read ownership from is a second place to get
     * it wrong.
     */
    @Query("""
            SELECT count(*) FROM deployment d
              JOIN service s ON s.id = d.service_id
              JOIN project p ON p.id = s.project_id
             WHERE p.organization_id = :organizationId
               AND d.created_at >= :since
            """)
    long countStartedSince(@Param("organizationId") UUID organizationId,
                           @Param("since") Instant since);
}
