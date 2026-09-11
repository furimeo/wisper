package lhqm.furimeo.wisper.database;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the {@code database_engine} table.
 *
 * <p>Two of these queries carry the index they were written for in their comment. The
 * placement query in particular matches {@code database_engine_placeable_idx} predicate
 * for predicate: it is asked every time a customer creates a database, and a sequential
 * scan there would be a scan of every engine on every node.
 */
public interface DatabaseEngineRepository extends ListCrudRepository<DatabaseEngine, UUID> {

    /** The admin list, grouped the way the screen shows it. */
    @Query("SELECT * FROM database_engine ORDER BY engine, node_id, port")
    List<DatabaseEngine> findAllOrdered();

    /** Every engine on one node, for the node's own page and for deletion checks. */
    List<DatabaseEngine> findByNodeIdOrderByPort(UUID nodeId);

    /**
     * The shared instance of one engine on one node, if there is one.
     *
     * <p>At most one can exist: {@code database_engine_shared_per_node_key} is a partial
     * unique index on exactly this pair.
     */
    @Query("""
            SELECT * FROM database_engine
             WHERE node_id = :nodeId AND engine = :engine AND mode = 'SHARED'
            """)
    Optional<DatabaseEngine> findSharedOn(@Param("nodeId") UUID nodeId,
                                          @Param("engine") String engine);

    /**
     * Shared engines of one kind that an operator wants running, least loaded first.
     *
     * <p>The load figure is how many customer databases the engine already holds, not
     * disk or connections. It is the number that decides whether the next database should
     * go somewhere else, and it is the only one the panel knows without asking a node.
     *
     * <p>Matches {@code database_engine_placeable_idx}.
     */
    @Query("""
            SELECT de.* FROM database_engine de
             WHERE de.engine = :engine
               AND de.mode = 'SHARED'
               AND de.desired_state = 'RUNNING'
             ORDER BY (SELECT count(*) FROM managed_database md
                        WHERE md.database_engine_id = de.id
                          AND md.state <> 'DELETING'), de.created_at
            """)
    List<DatabaseEngine> findSharedCandidates(@Param("engine") String engine);

    /**
     * A dedicated instance this organization already paid for, of the right kind.
     *
     * <p>Reused rather than multiplied: the point of a dedicated engine is one container
     * nobody else queries, not one container per database. Matches
     * {@code database_engine_by_organization_idx}.
     */
    @Query("""
            SELECT * FROM database_engine
             WHERE organization_id = :organizationId
               AND engine = :engine
               AND mode = 'DEDICATED'
               AND desired_state = 'RUNNING'
             ORDER BY created_at
             LIMIT 1
            """)
    Optional<DatabaseEngine> findDedicatedFor(@Param("organizationId") UUID organizationId,
                                              @Param("engine") String engine);

    /**
     * Ports already bound on one node.
     *
     * <p>{@code database_engine_node_port_key} refuses a duplicate, so asking first turns
     * a constraint violation into a port that is simply not chosen.
     */
    @Query("SELECT port FROM database_engine WHERE node_id = :nodeId ORDER BY port")
    List<Integer> findPortsOn(@Param("nodeId") UUID nodeId);

    /**
     * How many customer databases an engine holds.
     *
     * <p>{@code managed_database.database_engine_id} is {@code ON DELETE RESTRICT}, so
     * this is the number {@link DeleteDatabaseEngine} checks before it tries - a
     * constraint violation would be true but unreadable.
     */
    @Query("""
            SELECT count(*) FROM managed_database
             WHERE database_engine_id = :engineId AND state <> 'DELETING'
            """)
    long countDatabasesOn(@Param("engineId") UUID engineId);
}
