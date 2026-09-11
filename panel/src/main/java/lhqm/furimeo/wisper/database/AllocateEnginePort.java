package lhqm.furimeo.wisper.database;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Picks the port a new engine container binds on a node.
 *
 * <p>{@code database_engine_node_port_key} allows one port to be bound once per node, so
 * this exists to make that a chosen number rather than a constraint violation somebody
 * reads at three in the morning.
 *
 * <p>The engine's own default is tried first, so the first PostgreSQL on a machine is on
 * 5432 and reads normally in {@code docker ps}. Everything after it comes out of
 * {@code wisper.database.first-engine-port}..{@code last-engine-port}, which sits above
 * the registered range: a dedicated instance on 5433 would collide with an operator's own
 * second PostgreSQL, and the panel has no way to know about that one.
 *
 * <p>Ports are not reused deliberately-quickly: the lowest free one is taken, so a
 * dedicated engine that was deleted this morning does hand its number back. That is
 * acceptable because nothing outside the node ever dials these, and a customer's
 * connection string is rebuilt from the row rather than remembered.
 */
@Component
public class AllocateEnginePort {

    private final DatabaseEngineRepository engines;
    private final DatabaseSettings settings;

    public AllocateEnginePort(DatabaseEngineRepository engines, DatabaseSettings settings) {
        this.engines = engines;
        this.settings = settings;
    }

    /**
     * A free port on {@code nodeId} for an engine of this kind.
     *
     * @throws RequestRejected when the configured range is exhausted on that machine,
     *         which means several hundred dedicated instances on one node and is a
     *         capacity decision rather than something to work around
     */
    public int onNode(UUID nodeId, EngineKind engine) {
        List<Integer> taken = engines.findPortsOn(nodeId);
        Set<Integer> used = Set.copyOf(taken);
        if (!used.contains(engine.defaultPort())) {
            return engine.defaultPort();
        }
        for (int port = settings.firstEnginePort(); port <= settings.lastEnginePort(); port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw new RequestRejected(null, "Every database port on this node is in use. Add a node, "
                + "or put this instance somewhere else.");
    }
}
