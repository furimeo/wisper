package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads the {@code node_status} table.
 *
 * <p>Read-only by intent. Every column here belongs to the node, and the handlers that
 * write them do it with the explicit statements in {@code RecordHandshake},
 * {@code RecordHeartbeat}, {@code RecordStatusReport} and {@code RecordDisconnect}, each
 * touching only the columns that frame actually carries. Calling {@code save()} with a
 * whole record would write the columns the caller did not know about as well, which is how
 * a heartbeat erases the doctor report.
 */
public interface NodeStatusRepository extends ListCrudRepository<NodeStatus, UUID> {

    /** Every status row, for the fleet screen. Joined to names by the caller. */
    @Query("SELECT * FROM node_status")
    List<NodeStatus> findAllRows();

    /**
     * Nodes whose stream we believe is open but which have not been heard from in time.
     *
     * <p>{@code connection_state <> 'DISCONNECTED'} is the predicate
     * {@code node_status_heartbeat_idx} was made partial for, and it is what keeps this
     * off the rows that are already correct.
     *
     * <p>The {@code COALESCE} covers the case a plain heartbeat comparison misses: a node
     * that opened a stream and died before its first beat has a null
     * {@code last_heartbeat_at} and would otherwise sit on the fleet screen as connected
     * forever.
     */
    @Query("""
            SELECT * FROM node_status
             WHERE connection_state <> 'DISCONNECTED'
               AND COALESCE(last_heartbeat_at, last_connected_at) < :before
            """)
    List<NodeStatus> findSilentSince(@Param("before") Instant before);

    /** The status of one node, when the caller already has the id. */
    @Query("SELECT * FROM node_status WHERE node_id = :nodeId")
    Optional<NodeStatus> findRow(@Param("nodeId") UUID nodeId);
}
