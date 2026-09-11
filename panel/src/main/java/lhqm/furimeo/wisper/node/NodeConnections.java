package lhqm.furimeo.wisper.node;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * The live {@code Connect()} streams, seen from the panel's side.
 *
 * <p>The panel has no route to a node: every stream is opened by the node and the panel
 * writes down the response side of it (design §5.2). This interface is that fact turned
 * into an API. A caller names a node by id and hands over a {@link PanelMessage} to put
 * on its stream; whether a stream exists at all is the other question this answers.
 *
 * <p>Declared here rather than in {@code grpc} on purpose. Every package that has to
 * reach a node - {@code deploy}, {@code database}, {@code backup}, {@code placement} -
 * already depends on {@code node} for the node record. The implementation lives in
 * {@code lhqm.furimeo.wisper.grpc.ConnectedNodes}, which depends on this package, so the
 * arrow points one way and there is no cycle.
 *
 * <h2>Delivery is not guaranteed and does not need to be</h2>
 *
 * <p>A tunnel drops streams routinely, so nothing here retries and nothing queues. The
 * two messages that matter survive that by design:
 *
 * <ul>
 * <li>{@code ApplySpec} is idempotent and complete. If it is lost, the node reconnects,
 *     the panel sends the whole spec again, and the generation makes the redelivery
 *     harmless.</li>
 * <li>Every command that has a result is answered by a {@code CommandResult} carrying
 *     the same {@code command_id}. If the stream dies first, the future completes
 *     exceptionally with {@link NodeOffline} and the caller decides whether to try
 *     again - which is a decision about a customer's deployment, not about a socket.</li>
 * </ul>
 *
 * <p>Implementations are thread-safe. Several request threads and the reconcile
 * scheduler reach a node at the same time; the implementation serialises writes onto the
 * stream, because a gRPC {@code StreamObserver} is not safe for concurrent use.
 */
public interface NodeConnections {

    /**
     * Whether this node has an open control stream that finished its handshake.
     *
     * <p>False is not an alarm. A node with no stream keeps running every container it
     * already has; only management is unavailable. Use it to grey out an action, not to
     * conclude anything about the workloads.
     */
    boolean isConnected(UUID nodeId);

    /**
     * Every node with an open, handshaken stream, in no particular order.
     *
     * <p>For screens that show the fleet and for the sweep that marks the rest lost. A
     * snapshot: a node may drop between this call and the next line.
     */
    List<UUID> connectedNodeIds();

    /**
     * Puts one message on the node's stream and returns.
     *
     * <p>For the messages that carry no reply: {@code ApplySpec}, {@code ReconcileNow},
     * {@code LogRequest}, {@code StopLogStream}. The builder arrives with its payload
     * set and without a {@code command_id}; the implementation stamps a fresh one so
     * the daemon's log can correlate a command with the frame it produced.
     *
     * @throws NodeOffline if the node has no open stream. Callers that are pushing a
     *         spec should catch it and do nothing: the reconnect path resends the whole
     *         spec, so a spec that could not be delivered is not a spec that is lost.
     */
    void send(UUID nodeId, PanelMessage.Builder message);

    /**
     * Puts one command on the node's stream and completes when its result comes back.
     *
     * <p>For the commands that produce an outcome: {@code StartBuild}, {@code RunBackup},
     * {@code RestoreBackup}, {@code ProvisionDatabase}, {@code RotateDatabasePassword},
     * {@code DropDatabase}, {@code DrainNode}, {@code UpgradeNode}. The implementation
     * stamps the {@code command_id}, remembers the future against it, and completes it
     * when the matching {@code CommandResult} arrives on the way back.
     *
     * <p>No timeout is applied here, because the sensible one differs by two orders of
     * magnitude between rotating a password and restoring a database. Callers apply
     * their own with {@link CompletableFuture#orTimeout}; the implementation drops the
     * pending entry when the future is completed by anybody, including a timeout.
     *
     * <p>The future completes exceptionally with {@link NodeOffline} if the node is not
     * connected when the call is made, or if its stream ends before the result arrives.
     * It completes normally with a {@code CommandResult} whose {@code ok} is false when
     * the node tried and failed - that is an answer, not an error.
     */
    CompletableFuture<CommandResult> call(UUID nodeId, PanelMessage.Builder command);
}
