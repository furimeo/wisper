package lhqm.furimeo.wisper.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.FileOperationFailed;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.DirectoryListing;
import lhqm.furimeo.wisper.proto.v1.FileChunk;
import lhqm.furimeo.wisper.proto.v1.FileError;
import lhqm.furimeo.wisper.proto.v1.FileErrorCode;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileInfo;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.ListDirectory;
import lhqm.furimeo.wisper.proto.v1.ReadFile;

/**
 * One stream, many operations, and the three ways one of them ends.
 *
 * <p>The file manager is the only way a customer touches a node's disk, so what happens
 * when an operation does <em>not</em> answer matters as much as when it does. Two of the
 * cases here are about that: a stream that dies under a waiting request thread, and a
 * download the browser abandoned.
 */
class NodeFileTransfersTest {

    private final UUID nodeId = UUID.randomUUID();
    private final NodeFileTransfers transfers = new NodeFileTransfers();
    private final NodeSideStream node = new NodeSideStream();

    private StreamObserver<FileEvent> fromNode;

    @Test
    void anOperationIsAnsweredByTheEventTheNodeSendsBackUnderItsRequestId() {
        attachAnswering(request -> node.answer(FileEvent.newBuilder()
                .setRequestId(request.getRequestId())
                .setListing(DirectoryListing.newBuilder().setPath("/")
                        .addEntries(FileInfo.newBuilder().setName("app.js")))));

        FileEvent answer = transfers.call(nodeId, list("req-1", "/"));

        assertThat(answer.getResultCase()).isEqualTo(FileEvent.ResultCase.LISTING);
        assertThat(answer.getListing().getEntries(0).getName()).isEqualTo("app.js");
        assertThat(node.written()).hasSize(1);
        assertThat(node.written().get(0).getRootId()).isEqualTo("root-1");
    }

    @Test
    void aRefusalArrivesWithItsCodeIntactBecauseASentenceCannotBeBranchedOn() {
        attachAnswering(request -> node.answer(FileEvent.newBuilder()
                .setRequestId(request.getRequestId())
                .setError(FileError.newBuilder()
                        .setCode(FileErrorCode.FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
                        .setPath("../../etc/shadow")
                        .setDetail("that path leaves the volume"))));

        assertThatExceptionOfType(FileOperationFailed.class)
                .isThrownBy(() -> transfers.call(nodeId, list("req-1", "../../etc")))
                .matches(FileOperationFailed::isTraversalAttempt)
                .matches(failed -> failed.path().equals("../../etc/shadow"));
    }

    @Test
    void aReadIsPumpedToTheSinkInOrderUntilTheChunkThatSaysItIsTheLast() {
        attachAnswering(request -> {
            node.answer(chunk(request.getRequestId(), "first", false));
            node.answer(chunk(request.getRequestId(), "second", false));
            node.answer(chunk(request.getRequestId(), "third", true));
        });

        List<String> received = new ArrayList<>();
        transfers.stream(nodeId, read("req-2", "/app/log.txt"),
                event -> received.add(event.getChunk().getData().toStringUtf8()));

        assertThat(received).containsExactly("first", "second", "third");
    }

    @Test
    void aNodeWithNoFileStreamIsOfflineRatherThanASilentFailure() {
        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> transfers.call(nodeId, list("req-1", "/")));
    }

    @Test
    void aStreamThatDiesUnderAWaitingOperationReleasesItInsteadOfParkingTheThread() {
        attachAnswering(request -> fromNode.onError(Status.UNAVAILABLE
                .withDescription("tunnel closed").asRuntimeException()));

        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> transfers.call(nodeId, list("req-1", "/")))
                .matches(offline -> offline.nodeId().equals(nodeId));
    }

    @Test
    void anAnswerAlreadyOnItsWayWhenTheStreamClosesIsStillDelivered() {
        attachAnswering(request -> {
            node.answer(FileEvent.newBuilder().setRequestId(request.getRequestId())
                    .setInfo(FileInfo.newBuilder().setName("app.js").setSizeBytes(12)));
            fromNode.onCompleted();
        });

        FileEvent answer = transfers.call(nodeId, list("req-1", "/"));

        assertThat(answer.getInfo().getName()).isEqualTo("app.js");
    }

    @Test
    void cancellingTellsTheNodeToStopAndFinishesTheWaiterWithCancelled() {
        attachAnswering(request -> transfers.cancel(nodeId, request.getRequestId()));

        assertThatExceptionOfType(FileOperationFailed.class)
                .isThrownBy(() -> transfers.stream(nodeId, read("req-2", "/big.tar"),
                        event -> { }))
                .matches(failed -> failed.code() == FileErrorCode.FILE_ERROR_CODE_CANCELLED);

        FileRequest stop = node.written().get(1);
        assertThat(stop.getOpCase()).isEqualTo(FileRequest.OpCase.CANCEL);
        assertThat(stop.getCancel().getCancelledRequestId()).isEqualTo("req-2");
        // Addressed to the root the cancelled operation was in, so the node can find it.
        assertThat(stop.getRootId()).isEqualTo("root-1");
    }

    @Test
    void cancellingSomethingThatIsAlreadyOverIsTheNormalOutcomeAndNeverThrows() {
        transfers.cancel(nodeId, "req-1");
        attach();
        transfers.cancel(nodeId, "req-1");
        transfers.cancel(nodeId, "");
    }

    @Test
    void anEventForAnOperationNobodyIsWaitingOnIsDroppedRatherThanFatal() {
        attach();

        fromNode.onNext(FileEvent.newBuilder().setRequestId("long-gone")
                .setInfo(FileInfo.newBuilder().setName("stale")).build());

        // The stream is still usable afterwards, which is the whole assertion.
        attachAnswering(request -> node.answer(FileEvent.newBuilder()
                .setRequestId(request.getRequestId()).setInfo(FileInfo.newBuilder())));
        assertThat(transfers.call(nodeId, list("req-3", "/")).hasInfo()).isTrue();
    }

    @Test
    void aSecondStreamReplacesTheFirstBecauseATunnelDroppedOneNobodyNoticed() {
        attach();
        NodeSideStream reconnected = new NodeSideStream();
        StreamObserver<FileEvent> second = transfers.attach(nodeId, reconnected);
        reconnected.onRequest(request -> reconnected.answerVia(second, FileEvent.newBuilder()
                .setRequestId(request.getRequestId()).setInfo(FileInfo.newBuilder())));

        assertThat(transfers.call(nodeId, list("req-4", "/")).hasInfo()).isTrue();
        assertThat(node.written()).isEmpty();
    }

    /** Attaches a stream whose node does nothing until told what to answer. */
    private void attach() {
        fromNode = transfers.attach(nodeId, node);
        node.bind(fromNode);
    }

    /** Attaches a stream whose node answers every request the same way, synchronously. */
    private void attachAnswering(Consumer<FileRequest> answer) {
        attach();
        node.onRequest(answer);
    }

    private static FileRequest list(String requestId, String path) {
        return FileRequest.newBuilder()
                .setRequestId(requestId)
                .setRootId("root-1")
                .setList(ListDirectory.newBuilder().setPath(path))
                .build();
    }

    private static FileRequest read(String requestId, String path) {
        return FileRequest.newBuilder()
                .setRequestId(requestId)
                .setRootId("root-1")
                .setRead(ReadFile.newBuilder().setPath(path))
                .build();
    }

    private static FileEvent.Builder chunk(String requestId, String text, boolean last) {
        return FileEvent.newBuilder().setRequestId(requestId)
                .setChunk(FileChunk.newBuilder()
                        .setData(ByteString.copyFromUtf8(text))
                        .setLast(last));
    }

    /**
     * The node's end of the {@code FileOp} call.
     *
     * <p>It answers on the thread that wrote the request, which is what a real node does
     * from the panel's point of view: the event arrives while the request thread is inside
     * {@code take}, and the queue between them is what hands it over.
     */
    private static final class NodeSideStream implements StreamObserver<FileRequest> {

        private final List<FileRequest> written = new ArrayList<>();
        private StreamObserver<FileEvent> upstream;
        private Consumer<FileRequest> answer;

        List<FileRequest> written() {
            return List.copyOf(written);
        }

        void bind(StreamObserver<FileEvent> events) {
            this.upstream = events;
        }

        void onRequest(Consumer<FileRequest> howToAnswer) {
            this.answer = howToAnswer;
        }

        void answer(FileEvent.Builder event) {
            upstream.onNext(event.build());
        }

        void answerVia(StreamObserver<FileEvent> events, FileEvent.Builder event) {
            events.onNext(event.build());
        }

        @Override
        public void onNext(FileRequest request) {
            written.add(request);
            if (answer != null && request.getOpCase() != FileRequest.OpCase.CANCEL) {
                answer.accept(request);
            }
        }

        @Override
        public void onError(Throwable failure) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
