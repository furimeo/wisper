package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code placement} table.
 *
 * <p>{@code save()} is for inserts only. Every change to an existing row is one of the
 * targeted statements below, for the reason spelled out on {@link Placement}: the row has
 * a panel half and a node half, and a whole-record update writes both from whatever the
 * caller happened to be holding. The statements here each touch one half, name the columns
 * they touch, and step {@code version} so optimistic locking stays meaningful.
 */
public interface PlacementRepository extends ListCrudRepository<Placement, UUID> {

    /** The binding that answers "where does this service run", if there is one. */
    @Query("""
            SELECT * FROM placement
             WHERE service_id = :serviceId
               AND state IN ('PLANNED', 'ACTIVE')
             ORDER BY placed_at DESC
             LIMIT 1
            """)
    Optional<Placement> findCurrentFor(@Param("serviceId") UUID serviceId);

    /**
     * Every binding a spec would still carry for this service, newest first.
     *
     * <p>At most two in v1 - a draining row and its replacement - which is why
     * {@code NodeSpecSource.nodesHosting} is a list and not an {@code Optional}.
     */
    @Query("""
            SELECT * FROM placement
             WHERE service_id = :serviceId
               AND state <> 'RELEASED'
             ORDER BY placed_at DESC
            """)
    List<Placement> findLiveFor(@Param("serviceId") UUID serviceId);

    /**
     * Everything this node must be running, service by service.
     *
     * <p>Exactly the predicate {@code placement_active_by_node_idx} was made partial for.
     * Read every time a generation is cut.
     */
    @Query("""
            SELECT * FROM placement
             WHERE node_id = :nodeId
               AND state IN ('PLANNED', 'ACTIVE', 'DRAINING')
             ORDER BY service_id
            """)
    List<Placement> findLiveOn(@Param("nodeId") UUID nodeId);

    /** The drain screen: what is left on a node that is emptying. */
    @Query("""
            SELECT * FROM placement
             WHERE node_id = :nodeId
               AND state = 'DRAINING'
             ORDER BY placed_at
            """)
    List<Placement> findDrainingOn(@Param("nodeId") UUID nodeId);

    /** How much this node is still holding, for the admin list and the delete guard. */
    @Query("""
            SELECT count(*) FROM placement
             WHERE node_id = :nodeId
               AND state <> 'RELEASED'
            """)
    long countLiveOn(@Param("nodeId") UUID nodeId);

    /**
     * Ends a binding.
     *
     * <p>{@code state} and {@code released_at} move together because
     * {@code placement_released_consistent} says they are the same fact; setting one
     * without the other is a constraint violation rather than a half-released row, which
     * is the point of writing them in one statement.
     */
    @Modifying
    @Query("""
            UPDATE placement
               SET state       = 'RELEASED',
                   released_at = :at,
                   reason      = :reason,
                   updated_at  = :at,
                   version     = version + 1
             WHERE id = :placementId
               AND state <> 'RELEASED'
            """)
    int release(@Param("placementId") UUID placementId, @Param("reason") String reason,
                @Param("at") Instant at);

    /**
     * Starts evacuating a binding. The row stays in the node's spec: the old machine keeps
     * serving until the replacement is up, which is what makes a migration not an outage.
     */
    @Modifying
    @Query("""
            UPDATE placement
               SET state      = 'DRAINING',
                   reason     = :reason,
                   updated_at = :at,
                   version    = version + 1
             WHERE id = :placementId
               AND state IN ('PLANNED', 'ACTIVE')
            """)
    int markDraining(@Param("placementId") UUID placementId, @Param("reason") String reason,
                     @Param("at") Instant at);

    /**
     * Recomputes {@code pinned} from the volume table for every live binding on a node.
     *
     * <p>Pinning is a fact derived from storage, and storage arrives after the placement
     * does: a customer adds a volume to a service that has been running for a month, and
     * nothing about that write goes through this package. Rather than have every volume
     * write remember to call back here, the value is refreshed at the one moment it
     * decides anything - immediately before a drain or a migration reads it.
     */
    @Modifying
    @Query("""
            UPDATE placement
               SET pinned = EXISTS (SELECT 1 FROM volume v
                                     WHERE v.service_id = placement.service_id),
                   updated_at = :at,
                   version    = version + 1
             WHERE node_id = :nodeId
               AND state <> 'RELEASED'
               AND pinned <> EXISTS (SELECT 1 FROM volume v
                                      WHERE v.service_id = placement.service_id)
            """)
    int refreshPinningOn(@Param("nodeId") UUID nodeId, @Param("at") Instant at);

    /** The same refresh for one service, before it is migrated. */
    @Modifying
    @Query("""
            UPDATE placement
               SET pinned = EXISTS (SELECT 1 FROM volume v
                                     WHERE v.service_id = placement.service_id),
                   updated_at = :at,
                   version    = version + 1
             WHERE service_id = :serviceId
               AND state <> 'RELEASED'
               AND pinned <> EXISTS (SELECT 1 FROM volume v
                                      WHERE v.service_id = placement.service_id)
            """)
    int refreshPinningFor(@Param("serviceId") UUID serviceId, @Param("at") Instant at);

    /**
     * The node's half of the row, written from one {@code WorkloadStatus}.
     *
     * <p>Keyed by node <em>and</em> service, so a status batch from a machine that no
     * longer holds a service cannot overwrite the row belonging to the machine that does -
     * which is precisely the window a migration opens.
     */
    @Modifying
    @Query("""
            UPDATE placement
               SET reported_state       = :reportedState,
                   reported_at          = :observedAt,
                   container_id         = :containerId,
                   running_image_digest = :imageDigest,
                   restart_count        = :restartCount,
                   last_exit_code       = :exitCode,
                   last_error           = :lastError,
                   health               = :health,
                   updated_at           = :observedAt,
                   version              = version + 1
             WHERE node_id = :nodeId
               AND service_id = :serviceId
               AND state <> 'RELEASED'
            """)
    int recordReport(@Param("nodeId") UUID nodeId,
                     @Param("serviceId") UUID serviceId,
                     @Param("reportedState") String reportedState,
                     @Param("observedAt") Instant observedAt,
                     @Param("containerId") String containerId,
                     @Param("imageDigest") String imageDigest,
                     @Param("restartCount") int restartCount,
                     @Param("exitCode") Integer exitCode,
                     @Param("lastError") String lastError,
                     @Param("health") String health);
}
