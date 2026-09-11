package lhqm.furimeo.wisper.files;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Sends one operation to a node, and is the only place in the panel that does.
 *
 * <p>Every use-case in this package builds a {@link FileRequest.Builder} with its
 * {@code oneof} set and hands it here. Three things then happen that would otherwise be
 * written fourteen times, correctly thirteen of them:
 *
 * <ol>
 * <li><strong>The request id and the root id are stamped.</strong> Correlation has one
 *     owner, and the root comes from the {@link FileAccess} that was authorised rather than
 *     from whatever the caller had in hand.</li>
 * <li><strong>A traversal refusal becomes an audit entry.</strong>
 *     {@code FILE_ERROR_CODE_PATH_ESCAPES_ROOT} is never a customer's typo - the browser
 *     sends paths it was given - so it is recorded as {@code files.path_escape} with
 *     {@code AuditOutcome.DENIED} and answered flatly, with no hint about what would have
 *     worked (panel-ports.md §2.7).</li>
 * <li><strong>An operation that never answers is cancelled.</strong> One {@code FileOp}
 *     stream carries every operation for a machine, so a tree walk nobody is waiting for
 *     keeps that node's disk busy and everybody else's listing behind it. The port has a
 *     {@code cancel} for exactly this, and this is what calls it.</li>
 * </ol>
 *
 * <p>The timeout runs the call on a virtual thread and waits with a deadline. That is not
 * free, but it is close: the alternative is a blocked request thread with no way back, and
 * {@code NodeFiles} deliberately has no timeout of its own because the sensible one differs
 * by two orders of magnitude between a stat and an extract.
 */
@Component
public class DispatchFileRequest implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DispatchFileRequest.class);

    private final NodeFiles files;
    private final AuditTrail audit;
    private final FilesSettings settings;
    private final ExecutorService calls =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("file-op-", 0).factory());

    public DispatchFileRequest(NodeFiles files, AuditTrail audit, FilesSettings settings) {
        this.files = files;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * Runs an operation that produces one answer.
     *
     * @param access  the authorised root; supplies the node and the root id
     * @param request the operation, with its {@code oneof} set and nothing else
     * @return the terminal event
     * @throws FileOperationFailed    if the node refused
     * @throws FileOperationTimedOut  if it accepted and did not answer within
     *                                {@code wisper.files.operation-timeout}
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no file stream
     */
    public FileAnswer call(FileAccess access, FileRequest.Builder request) {
        try {
            return callOnNode(access.requireNodeId(), access.rootId(), request);
        } catch (FileOperationFailed refused) {
            recordIfTraversal(access, refused);
            throw refused;
        }
    }

    /**
     * Runs an operation the panel itself started, with no person behind it.
     *
     * <p>Only for work that names no path a customer supplied - abandoning a swept upload
     * session is the one case. There is nothing to authorise, because nothing was requested,
     * and nothing to audit, because nothing a customer did is being recorded; the node and
     * the root come from a ticket the panel wrote down itself.
     */
    public FileAnswer callOnNode(UUID nodeId, String rootId, FileRequest.Builder request) {
        String requestId = UUID.randomUUID().toString();
        request.setRequestId(requestId).setRootId(rootId);
        return FileAnswer.of(awaitCall(nodeId, requestId, request.build()));
    }

    /**
     * Runs an operation that answers with a series of events.
     *
     * <p>For {@code read}, which is many chunks. No deadline: a download of a large file is
     * legitimately slow, and the sink writing into the servlet output stream is what applies
     * backpressure. A browser that goes away makes the sink throw, which ends the call, and
     * the caller cancels on its way out.
     */
    public void stream(FileAccess access, FileRequest.Builder request,
                       Consumer<FileEvent> events) {
        UUID nodeId = access.requireNodeId();
        String requestId = stamp(access, request);
        try {
            files.stream(nodeId, request.build(), events);
        } catch (FileOperationFailed refused) {
            recordIfTraversal(access, refused);
            throw refused;
        } catch (RuntimeException interrupted) {
            // The browser closed the download. Tell the node to stop reading the file.
            files.cancel(nodeId, requestId);
            throw interrupted;
        }
    }

    /** Abandons an operation the caller is no longer waiting for. Never throws. */
    public void cancel(FileAccess access, String requestId) {
        if (requestId == null || requestId.isBlank() || !access.service().isPlaced()) {
            return;
        }
        files.cancel(access.service().nodeId(), requestId);
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }

    private FileEvent awaitCall(UUID nodeId, String requestId, FileRequest request) {
        CompletableFuture<FileEvent> answer =
                CompletableFuture.supplyAsync(() -> files.call(nodeId, request), calls);
        try {
            return answer.get(settings.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            answer.cancel(true);
            files.cancel(nodeId, requestId);
            log.warn("File operation {} on node {} did not answer within {}; cancelled",
                    requestId, nodeId, settings.operationTimeout());
            throw FileOperationTimedOut.after(settings.operationTimeout(), pathOf(request));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            files.cancel(nodeId, requestId);
            throw FileOperationTimedOut.interrupted(pathOf(request));
        } catch (ExecutionException failed) {
            throw unwrap(failed.getCause(), requestId);
        }
    }

    private static RuntimeException unwrap(Throwable cause, String requestId) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("File operation " + requestId + " failed unexpectedly",
                cause);
    }

    /**
     * Stamps the correlation id and the authorised root.
     *
     * <p>The root id comes from the {@link FileAccess} and never from the builder. A
     * use-case cannot address a root it was not given permission for, even by mistake.
     */
    private static String stamp(FileAccess access, FileRequest.Builder request) {
        String requestId = UUID.randomUUID().toString();
        request.setRequestId(requestId).setRootId(access.rootId());
        return requestId;
    }

    private void recordIfTraversal(FileAccess access, FileOperationFailed refused) {
        if (!refused.isTraversalAttempt()) {
            return;
        }
        audit.record(AuditEntry.denied(access.actor(), "files.path_escape", access.target(),
                access.organizationId(),
                "a path resolved outside " + access.root().label() + ": " + refused.path()));
    }

    /** Which path the operation was about, for the message a timeout produces. */
    private static String pathOf(FileRequest request) {
        return switch (request.getOpCase()) {
            case LIST -> request.getList().getPath();
            case STAT -> request.getStat().getPath();
            case READ -> request.getRead().getPath();
            case WRITE -> request.getWrite().getSession().getPath();
            case CREATE_DIRECTORY -> request.getCreateDirectory().getPath();
            case MOVE -> request.getMove().getFrom();
            case DELETE -> request.getDelete().getPath();
            case CHANGE_MODE -> request.getChangeMode().getPath();
            case ARCHIVE -> request.getArchive().getDestination();
            case EXTRACT -> request.getExtract().getArchivePath();
            case MEASURE -> request.getMeasure().getPath();
            case RESUME, COMPLETE, ABORT, CANCEL, OP_NOT_SET -> "";
        };
    }
}
