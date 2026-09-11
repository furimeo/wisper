package lhqm.furimeo.wisper.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.DrainNode;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.ReconcileNow;

/**
 * A control stream through a tunnel drops routinely, so dropping and reconnecting is the
 * normal path rather than the error path (design §5.2). These are the properties the rest
 * of the panel relies on while that happens.
 */
class ConnectedNodesTest {

    private final UUID nodeId = UUID.randomUUID();
    private final ConnectedNodes connections = new ConnectedNodes();

    @Test
    void anAttachedStreamIsReachableAndEveryMessageGetsAFreshCommandId() {
        Attached node = attach();

        connections.send(nodeId, reconcile());
        connections.send(nodeId, reconcile());

        assertThat(connections.isConnected(nodeId)).isTrue();
        assertThat(connections.connectedNodeIds()).containsExactly(nodeId);
        assertThat(node.observer().written()).hasSize(2);
        // Correlation has exactly one owner. Two callers minting ids eventually produces a
        // command nobody is waiting on and a future that never completes.
        assertThat(node.observer().written().get(0).getCommandId())
                .isNotBlank()
                .isNotEqualTo(node.observer().written().get(1).getCommandId());
    }

    @Test
    void aCommandCompletesWhenItsResultComesBackUnderTheSameId() {
        Attached node = attach();

        CompletableFuture<CommandResult> answer = connections.call(nodeId, drain());
        String commandId = node.observer().last().getCommandId();

        assertThat(answer).isNotDone();
        assertThat(node.stream().complete(CommandResult.newBuilder()
                .setCommandId(commandId).setOk(true).setDetail("drained").build())).isTrue();
        assertThat(answer).isCompletedWithValueMatching(CommandResult::getOk);
    }

    @Test
    void aResultForACommandNobodyIsWaitingOnIsIgnoredRatherThanFatal() {
        Attached node = attach();

        // Happens whenever a caller timed out, and whenever a node replays a result after
        // a reconnect. Neither is a problem.
        assertThat(node.stream().complete(CommandResult.newBuilder()
                .setCommandId("a-command-that-timed-out").setOk(true).build())).isFalse();
    }

    @Test
    void aDroppedStreamMakesTheNodeUnreachableAndReleasesEverythingWaitingOnIt() {
        Attached node = attach();
        CompletableFuture<CommandResult> answer = connections.call(nodeId, drain());

        connections.detach(node.stream(), "tunnel closed");

        assertThat(connections.isConnected(nodeId)).isFalse();
        assertThat(connections.connectedNodeIds()).isEmpty();
        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(answer::get)
                .withCauseInstanceOf(NodeOffline.class);
        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> connections.send(nodeId, reconcile()));
    }

    @Test
    void aCallToANodeThatIsAwayFailsTheFutureRatherThanThrowing() {
        CompletableFuture<CommandResult> answer = connections.call(nodeId, drain());

        assertThat(answer).isCompletedExceptionally();
        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(answer::get)
                .withCauseInstanceOf(NodeOffline.class);
    }

    @Test
    void reconnectingAfterADropRestoresReachabilityOnTheNewStream() {
        Attached first = attach();
        connections.detach(first.stream(), "tunnel closed");

        Attached second = attach();
        connections.send(nodeId, reconcile());

        assertThat(connections.isConnected(nodeId)).isTrue();
        assertThat(second.observer().written()).hasSize(1);
        assertThat(first.observer().written()).isEmpty();
    }

    @Test
    void aSecondStreamReplacesTheFirstAndTheFirstStopsBeingWritable() {
        Attached stale = attach();
        CompletableFuture<CommandResult> onTheStaleStream = connections.call(nodeId, drain());

        Attached fresh = attach();
        connections.send(nodeId, reconcile());

        assertThat(stale.stream().isOpen()).isFalse();
        assertThat(onTheStaleStream).isCompletedExceptionally();
        assertThat(fresh.observer().written()).hasSize(1);
        assertThat(stale.observer().written()).hasSize(1);
    }

    @Test
    void aTransportThatDiesUnderAWriteBecomesNodeOfflineLikeEveryOtherWayOfBeingAway() {
        Attached node = attach();
        node.observer().breakTransport();

        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> connections.send(nodeId, reconcile()));
        assertThat(connections.isConnected(nodeId)).isFalse();
    }

    @Test
    void detachingAStreamThatWasAlreadyReplacedDoesNotUnhookTheNewOne() {
        Attached stale = attach();
        Attached fresh = attach();

        // A late onError for a stream that was already replaced. Removing by node id alone
        // here would leave the node connected and unreachable until its next reconnect.
        connections.detach(stale.stream(), "late error from the old stream");

        assertThat(connections.isConnected(nodeId)).isTrue();
        connections.send(nodeId, reconcile());
        assertThat(fresh.observer().written()).hasSize(1);
    }

    private Attached attach() {
        RecordingStream observer = new RecordingStream();
        return new Attached(observer,
                connections.attach(nodeId, "node-a", "198.51.100.7", observer));
    }

    /** What the panel wrote, and the handle the control stream would be holding. */
    private record Attached(RecordingStream observer, NodeStream stream) {
    }

    private static PanelMessage.Builder reconcile() {
        return PanelMessage.newBuilder()
                .setReconcileNow(ReconcileNow.newBuilder().setReason("test"));
    }

    private static PanelMessage.Builder drain() {
        return PanelMessage.newBuilder()
                .setDrain(DrainNode.newBuilder().setEvacuateStateless(false).setReason("survey"));
    }
}
