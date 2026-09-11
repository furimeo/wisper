package lhqm.furimeo.wisper.node;

import lhqm.furimeo.wisper.sql.SqlTimestamp;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a control stream ended.
 *
 * <p>Called for every way a stream can finish: the daemon shut down, the tunnel dropped
 * it, the panel refused the handshake, or gRPC saw the peer go away. All four are the same
 * fact and none of them is an incident - a stream through a tunnel drops routinely, and the
 * node keeps running every container it was given (design §5.2). The log line is at info
 * for exactly that reason.
 *
 * <p>It touches three columns and no others. In particular it leaves
 * {@code applied_generation}, the capacity numbers and the doctor report alone: they were
 * true when they were written and they are still the last thing known about the machine.
 * Blanking them would turn "we cannot see this node" into "this node has nothing", which
 * is the mistake this codebase names in AGENTS.md §4.5 and refuses to make anywhere.
 */
@Component
public class RecordDisconnect {

    private static final Logger log = LoggerFactory.getLogger(RecordDisconnect.class);

    private static final String SQL = """
            UPDATE node_status
               SET connection_state     = 'DISCONNECTED',
                   last_disconnected_at = :at,
                   updated_at           = :at,
                   version              = version + 1
             WHERE node_id = :nodeId
               AND connection_state <> 'DISCONNECTED'
            """;

    private final JdbcClient jdbc;

    public RecordDisconnect(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param reason one short phrase for the log - {@code "peer closed"},
     *               {@code "protocol refused"}, {@code "heartbeat timeout"}
     */
    @Transactional
    public void accept(UUID nodeId, String reason) {
        int updated = jdbc.sql(SQL)
                .param("nodeId", nodeId)
                .param("at", SqlTimestamp.at(Instant.now()))
                .update();
        if (updated > 0) {
            log.info("Node {} disconnected: {}. Its workloads are unaffected.", nodeId, reason);
        }
        // Nothing updated means the row already said disconnected. A refused handshake and
        // the stream closing behind it both land here, and the second one has nothing to
        // do - which is not worth a log line of its own.
    }
}
