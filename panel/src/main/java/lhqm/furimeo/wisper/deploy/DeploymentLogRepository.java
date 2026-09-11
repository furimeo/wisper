package lhqm.furimeo.wisper.deploy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code deployment_log} table.
 *
 * <p>Both reads seek on {@code deployment_log_cursor_key}, the unique index over
 * {@code (deployment_id, sequence)} that is simultaneously the ordering, the resume
 * cursor and the duplicate rejector for a chunk a node redelivers after a reconnect.
 *
 * <p>Nothing here updates or deletes a line. Lines go when their deployment goes, by
 * {@code ON DELETE CASCADE}.
 */
public interface DeploymentLogRepository extends ListCrudRepository<DeploymentLog, UUID> {

    /**
     * Everything after line {@code cursor}, in order.
     *
     * <p>What an SSE client asks for when it reconnects, and what the deployment page
     * asks for with a cursor of zero when it first renders. The limit is a ceiling on one
     * response, not on the log: a client that hits it comes back with a higher cursor.
     */
    @Query("""
            SELECT * FROM deployment_log
             WHERE deployment_id = :deploymentId
               AND sequence > :cursor
             ORDER BY sequence
             LIMIT :limit
            """)
    List<DeploymentLog> findAfter(@Param("deploymentId") UUID deploymentId,
                                  @Param("cursor") long cursor,
                                  @Param("limit") int limit);

    /**
     * The highest line number stored for this deployment, or zero.
     *
     * <p>Seeds the in-memory counter in {@link AppendDeploymentLog} when the panel has
     * restarted in the middle of somebody's build, so numbering continues instead of
     * starting again at 1 and colliding with every row already there.
     */
    @Query("""
            SELECT COALESCE(MAX(sequence), 0) FROM deployment_log
             WHERE deployment_id = :deploymentId
            """)
    long lastSequence(@Param("deploymentId") UUID deploymentId);
}
