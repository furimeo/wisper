package lhqm.furimeo.wisper.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.Ack;
import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.LogRequest;
import lhqm.furimeo.wisper.proto.v1.LogSource;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.stats.LogSubscription;

/**
 * Subscriptions start and stop on the control stream; their output arrives on another.
 *
 * <p>Joining those two channels by {@code stream_id} is the whole of this class, so the
 * cases worth asserting are the ones where the join can go wrong: a chunk for a feed
 * nobody is reading, a chunk from the wrong machine, and the output stream disappearing
 * while a browser is still watching.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NodeLogSubscriptionsTest {

    private final UUID nodeId = UUID.randomUUID();
    private final List<PanelMessage> commands = new ArrayList<>();
    private final List<LogChunk> toBrowser = new ArrayList<>();

    @Mock
    private NodeConnections connections;

    private NodeLogSubscriptions subscriptions;

    private NodeLogSubscriptions following() {
        subscriptions = new NodeLogSubscriptions(connections);
        willAnswer(invocation -> {
            PanelMessage.Builder command = invocation.getArgument(1);
            commands.add(command.build());
            return null;
        }).given(connections).send(eq(nodeId), any(PanelMessage.Builder.class));
        return subscriptions;
    }

    @Test
    void followingPutsALogRequestOnTheControlStreamAndRegistersTheFeed() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getStartLogStream().getStreamId()).isEqualTo("s-1");
        assertThat(commands.get(0).getStartLogStream().getTailLines()).isEqualTo(200);
        assertThat(feed.streamId()).isEqualTo("s-1");
        assertThat(feed.isFinished()).isFalse();
        assertThat(subscriptions.openFeeds()).isEqualTo(1);
    }

    @Test
    void chunksAreRoutedToTheFeedTheirStreamIdNames() {
        following().follow(nodeId, request("s-1"), toBrowser::add);
        List<LogChunk> other = new ArrayList<>();
        subscriptions.follow(nodeId, request("s-2"), other::add);
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, new RecordingAcks());

        fromNode.onNext(chunk("s-1", "building...", false));
        fromNode.onNext(chunk("s-2", "listening on :3000", false));

        assertThat(toBrowser).hasSize(1);
        assertThat(toBrowser.get(0).getData().toStringUtf8()).isEqualTo("building...");
        assertThat(other).hasSize(1);
    }

    @Test
    void droppedBytesAccumulateBecauseTheTotalIsWhatTheCustomerIsShown() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, new RecordingAcks());

        fromNode.onNext(LogChunk.newBuilder().setStreamId("s-1").setDroppedBytes(2048).build());
        fromNode.onNext(LogChunk.newBuilder().setStreamId("s-1").setDroppedBytes(1024).build());

        assertThat(feed.droppedBytes()).isEqualTo(3072L);
    }

    @Test
    void aSourceThatEndsFinishesTheFeedWithoutSendingAStopNobodyNeeds() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, new RecordingAcks());

        fromNode.onNext(chunk("s-1", "build finished", true));

        assertThat(feed.isFinished()).isTrue();
        assertThat(subscriptions.openFeeds()).isZero();
        assertThat(commands).hasSize(1);
        feed.close();
        assertThat(commands).hasSize(1);
    }

    @Test
    void closingAFeedStopsTheNodeProducingItAndIsSafeToCallTwice() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);

        feed.close();
        feed.close();

        assertThat(commands).hasSize(2);
        assertThat(commands.get(1).getStopLogStream().getStreamId()).isEqualTo("s-1");
        assertThat(subscriptions.openFeeds()).isZero();
    }

    @Test
    void closingAFeedForANodeThatWentAwayIsNotAnError() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);
        willThrow(new NodeOffline(nodeId)).given(connections)
                .send(eq(nodeId), any(PanelMessage.Builder.class));

        // close() runs from an SSE callback, which has nowhere to put a failure - and a
        // node that is away needs no stop command anyway.
        feed.close();

        assertThat(subscriptions.openFeeds()).isZero();
    }

    @Test
    void aNodeWithNoControlStreamCannotBeFollowedAndLeavesNoFeedBehind() {
        subscriptions = new NodeLogSubscriptions(connections);
        willThrow(new NodeOffline(nodeId)).given(connections)
                .send(eq(nodeId), any(PanelMessage.Builder.class));

        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> subscriptions.follow(nodeId, request("s-1"), toBrowser::add));
        assertThat(subscriptions.openFeeds()).isZero();
    }

    @Test
    void theOutputStreamGoingAwayTellsTheWatcherRatherThanLeavingThePageSilent() {
        LogSubscription feed = following().follow(nodeId, request("s-1"), toBrowser::add);
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, new RecordingAcks());

        fromNode.onError(Status.UNAVAILABLE.withDescription("tunnel closed").asRuntimeException());

        assertThat(feed.isFinished()).isTrue();
        LogChunk last = toBrowser.get(toBrowser.size() - 1);
        assertThat(last.getEnd()).isTrue();
        assertThat(last.getData().toStringUtf8()).contains("UNAVAILABLE");
        // No figure is invented for output nobody counted.
        assertThat(feed.droppedBytes()).isZero();
        assertThat(subscriptions.openFeeds()).isZero();
    }

    @Test
    void aChunkForAFeedNobodyIsReadingIsDroppedRatherThanFatal() {
        following();
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, new RecordingAcks());

        fromNode.onNext(chunk("a-feed-that-was-closed", "output", false));

        assertThat(toBrowser).isEmpty();
    }

    @Test
    void aNodeCannotAnswerAnotherNodesFeed() {
        following().follow(nodeId, request("s-1"), toBrowser::add);
        StreamObserver<LogChunk> impostor =
                subscriptions.attach(UUID.randomUUID(), new RecordingAcks());

        impostor.onNext(chunk("s-1", "output from the wrong machine", false));

        assertThat(toBrowser).isEmpty();
    }

    @Test
    void theStreamIsAcknowledgedOnceWithHowManyChunksItCarried() {
        following();
        RecordingAcks acks = new RecordingAcks();
        StreamObserver<LogChunk> fromNode = subscriptions.attach(nodeId, acks);

        fromNode.onNext(chunk("s-1", "one", false));
        fromNode.onNext(chunk("s-1", "two", false));
        fromNode.onCompleted();

        assertThat(acks.written()).hasSize(1);
        assertThat(acks.written().get(0).getAccepted()).isEqualTo(2L);
        assertThat(acks.isCompleted()).isTrue();
    }

    @Test
    void aRequestWithNoStreamIdIsARefusalRatherThanAFeedNobodyCanFind() {
        subscriptions = new NodeLogSubscriptions(connections);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> subscriptions.follow(nodeId,
                        LogRequest.newBuilder().setFollow(true).build(), toBrowser::add));
    }

    private static LogRequest request(String streamId) {
        return LogRequest.newBuilder()
                .setStreamId(streamId)
                .setSource(LogSource.LOG_SOURCE_CONTAINER)
                .setSubjectId("workload-1")
                .setTailLines(200)
                .setFollow(true)
                .build();
    }

    private static LogChunk chunk(String streamId, String text, boolean end) {
        return LogChunk.newBuilder()
                .setStreamId(streamId)
                .setData(ByteString.copyFromUtf8(text))
                .setEnd(end)
                .build();
    }

    /** The node's side of the {@code LogStream} call: it only ever reads one Ack. */
    private static final class RecordingAcks implements StreamObserver<Ack> {

        private final List<Ack> written = new ArrayList<>();
        private boolean completed;

        List<Ack> written() {
            return List.copyOf(written);
        }

        boolean isCompleted() {
            return completed;
        }

        @Override
        public void onNext(Ack ack) {
            written.add(ack);
        }

        @Override
        public void onError(Throwable failure) {
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
