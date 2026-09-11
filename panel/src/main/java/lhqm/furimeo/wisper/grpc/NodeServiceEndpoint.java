package lhqm.furimeo.wisper.grpc;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.AuthenticatedNode;
import lhqm.furimeo.wisper.node.EnrolNode;
import lhqm.furimeo.wisper.node.EnrolmentRefused;
import lhqm.furimeo.wisper.proto.v1.Ack;
import lhqm.furimeo.wisper.proto.v1.EnrollRequest;
import lhqm.furimeo.wisper.proto.v1.EnrollResponse;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.NodeMessage;
import lhqm.furimeo.wisper.proto.v1.NodeServiceGrpc;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.StatSample;
import lhqm.furimeo.wisper.proto.v1.StatusBatch;
import lhqm.furimeo.wisper.proto.v1.TerminalFrame;
import lhqm.furimeo.wisper.stats.IngestStatSample;

/**
 * The gRPC service every node talks to.
 *
 * <p>An adapter and nothing else. It parses a frame, works out who owns the content, and
 * hands it over; it writes no table and makes no decision that belongs to a domain. That
 * is what keeps seven packages' invariants in the seven packages that know them, and it is
 * why this file is short despite being the busiest surface in the panel.
 *
 * <p>Authentication happened before any of these methods ran, in
 * {@link AuthenticateNodeCall}. {@code Enroll} is the exception the interceptor names, and
 * the only method here that must not call {@link NodeCallMetadata#caller()}.
 *
 * <p>Three of the seven RPCs are long-lived streams belonging to a feature rather than to
 * the control plane, so each is one line handing the stream to the class in this package
 * that owns it: {@code NodeTerminalSessions} for the web shell,
 * {@code NodeFileTransfers} for the file manager and {@code NodeLogSubscriptions} for log
 * feeds - the implementations of {@code files.NodeTerminals}, {@code files.NodeFiles} and
 * {@code stats.NodeLogs} named in panel-ports.md §2. They are the same shape on purpose:
 * the node dials, the panel drives, so each takes the response side and returns the
 * listener for what comes back.
 */
@Component
public class NodeServiceEndpoint extends NodeServiceGrpc.NodeServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(NodeServiceEndpoint.class);

    private final EnrolNode enrolNode;
    private final PanelCertificate panelCertificate;
    private final NodeControlStream controlStream;
    private final IngestStatusBatch statusBatch;
    private final IngestStatSample statSample;
    private final NodeTerminalSessions terminals;
    private final NodeFileTransfers files;
    private final NodeLogSubscriptions logs;

    public NodeServiceEndpoint(EnrolNode enrolNode, PanelCertificate panelCertificate,
                               NodeControlStream controlStream, IngestStatusBatch statusBatch,
                               IngestStatSample statSample, NodeTerminalSessions terminals,
                               NodeFileTransfers files, NodeLogSubscriptions logs) {
        this.enrolNode = enrolNode;
        this.panelCertificate = panelCertificate;
        this.controlStream = controlStream;
        this.statusBatch = statusBatch;
        this.statSample = statSample;
        this.terminals = terminals;
        this.files = files;
        this.logs = logs;
    }

    /**
     * The one unauthenticated call. Its proof is the single-use bootstrap token in its
     * body, and every way it can be refused is named, because an operator is watching an
     * installer's output and "enrolment failed" costs them an hour (design §7.1).
     */
    @Override
    public void enroll(EnrollRequest request, StreamObserver<EnrollResponse> answer) {
        try {
            answer.onNext(enrolNode.enrol(request, NodeCallMetadata.remoteAddress(),
                    panelCertificate.sha256()));
            answer.onCompleted();
        } catch (EnrolmentRefused refused) {
            answer.onError(statusFor(refused).withDescription(refused.getMessage())
                    .asRuntimeException());
        } catch (RuntimeException failed) {
            log.error("Enrolment from {} failed unexpectedly", NodeCallMetadata.remoteAddress(),
                    failed);
            answer.onError(Status.INTERNAL.withDescription(
                    "The panel could not complete that enrolment. Nothing was written.")
                    .asRuntimeException());
        }
    }

    @Override
    public StreamObserver<NodeMessage> connect(StreamObserver<PanelMessage> toNode) {
        return controlStream.open(toNode);
    }

    /**
     * The result of a reconcile pass.
     *
     * <p>Unary rather than a frame on {@code Connect} because it is a discrete, retryable
     * unit: a node whose batch fails while the panel restarts retries this with backoff
     * instead of tearing down the control stream and every command queued on it.
     */
    @Override
    public void reportStatus(StatusBatch batch, StreamObserver<Ack> answer) {
        AuthenticatedNode node = NodeCallMetadata.caller();
        Instant receivedAt = Instant.now();
        if (!statusBatch.accept(node.nodeId(), batch, receivedAt)) {
            answer.onError(Status.PERMISSION_DENIED.withDescription(
                    "This node reports a generation the panel never published. Two panels are "
                    + "driving it; it has been suspended and no container was touched.")
                    .asRuntimeException());
            return;
        }
        answer.onNext(Ack.newBuilder()
                .setReceivedAt(now(receivedAt))
                .setAccepted(1)
                .build());
        answer.onCompleted();
    }

    /**
     * Metrics, pushed continuously.
     *
     * <p>Its own stream so a burst of samples cannot delay a heartbeat. One {@code Ack} at
     * the end rather than one per sample: the node is not waiting on them, and answering
     * every sample would double the traffic this call exists to keep cheap.
     */
    @Override
    public StreamObserver<StatSample> pushStats(StreamObserver<Ack> answer) {
        AuthenticatedNode node = NodeCallMetadata.caller();
        return new StreamObserver<>() {
            private long accepted;

            @Override
            public void onNext(StatSample sample) {
                statSample.accept(node.nodeId(), sample);
                accepted++;
            }

            @Override
            public void onError(Throwable failure) {
                // The node will reopen the stream and resend whatever it is unsure landed;
                // the unique index on (node, service, sampled_at) rejects the duplicates.
                log.debug("Stats stream from {} ended: {}", node.name(), failure.toString());
            }

            @Override
            public void onCompleted() {
                answer.onNext(Ack.newBuilder()
                        .setReceivedAt(now(Instant.now()))
                        .setAccepted(accepted)
                        .build());
                answer.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<TerminalFrame> terminal(StreamObserver<TerminalFrame> toNode) {
        return terminals.attach(NodeCallMetadata.caller().nodeId(), toNode);
    }

    @Override
    public StreamObserver<LogChunk> logStream(StreamObserver<Ack> answer) {
        return logs.attach(NodeCallMetadata.caller().nodeId(), answer);
    }

    @Override
    public StreamObserver<FileEvent> fileOp(StreamObserver<FileRequest> toNode) {
        return files.attach(NodeCallMetadata.caller().nodeId(), toNode);
    }

    /**
     * The status an installer branches on.
     *
     * <p>None of these is worth retrying with the same input, so none of them is
     * {@code UNAVAILABLE} - a backoff loop against an expired token would run until
     * somebody killed it.
     */
    private static Status statusFor(EnrolmentRefused refused) {
        return switch (refused.reason()) {
            case TOKEN_UNKNOWN, TOKEN_EXPIRED, TOKEN_SPENT, TOKEN_REVOKED, PROOF_INVALID ->
                    Status.PERMISSION_DENIED;
            case MALFORMED -> Status.INVALID_ARGUMENT;
            case NODE_ALREADY_ENROLLED, FINGERPRINT_TAKEN, PROTOCOL_UNSUPPORTED, DOCTOR_FAILED ->
                    Status.FAILED_PRECONDITION;
        };
    }

    private static Timestamp now(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano())
                .build();
    }
}
