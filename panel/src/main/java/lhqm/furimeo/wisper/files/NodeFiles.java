package lhqm.furimeo.wisper.files;

import java.util.UUID;
import java.util.function.Consumer;

import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Runs one file operation on a node and brings the answer back.
 *
 * <p>There is no SSH, no SFTP and no WebDAV in wisper, so the web file manager is the
 * entire surface a customer has on a node's filesystem (design §8.2). This interface is
 * the panel-side half of that: a controller under {@code /services/{id}/files/**} builds
 * a {@link FileRequest}, hands it here, and gets {@link FileEvent}s back.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.grpc.NodeFileTransfers}, which owns the
 * {@code FileOp} stream. The stream was opened by the node - it is the only side that
 * can dial - so the panel writes {@code FileRequest} down the response side of a call
 * whose request type is {@code FileEvent}. None of that inversion is visible here, which
 * is the point of the seam.
 *
 * <h2>Two methods, not fourteen</h2>
 *
 * <p>The operations are the {@code oneof} in {@code files.proto} and they stay there. A
 * method per operation would be fourteen signatures that have to be kept in step with a
 * protobuf file by hand, and the first one that drifts is a file manager that cannot
 * chmod. What differs between operations is only how many events answer them, so that -
 * one answer or many - is the distinction the API makes.
 *
 * <h2>Paths</h2>
 *
 * <p>Every request names a {@code root_id} from the node's current spec and a path
 * relative to it. <strong>The panel never builds an absolute path on a node.</strong>
 * The node resolves the root and refuses anything that escapes it with
 * {@code FILE_ERROR_CODE_PATH_ESCAPES_ROOT}, which the caller must record in the audit
 * log rather than show as a friendly hint: it is an attack or a bug, never a typo.
 */
public interface NodeFiles {

    /**
     * Runs an operation that produces exactly one answer, and returns it.
     *
     * <p>For {@code list}, {@code stat}, {@code write}, {@code resume},
     * {@code complete}, {@code abort}, {@code create_directory}, {@code move},
     * {@code delete}, {@code change_mode}, {@code archive}, {@code extract} and
     * {@code measure}. Blocks the calling thread, which is a virtual thread, so blocking
     * is what it costs and nothing more.
     *
     * @return the terminal event: a listing, a {@code FileInfo}, an {@code UploadAck},
     *         an {@code UploadState}, a {@code DirectorySize} or an
     *         {@code OperationDone}
     * @throws FileOperationFailed if the node answered with a {@code FileError}. The
     *         error code is on the exception, because "not found" and "path escapes
     *         root" need completely different handling and a string does not carry that.
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no {@code FileOp}
     *         stream open
     */
    FileEvent call(UUID nodeId, FileRequest request);

    /**
     * Runs an operation that answers with a series of events, and pumps them to a sink.
     *
     * <p>For {@code read}, which is many {@code FileChunk}s: a download the browser is
     * streaming, or the first megabyte of a large file for the inline editor. Returns
     * when the node sends a chunk marked {@code last} or an error, so the caller can
     * close its response afterwards.
     *
     * <p>The sink is called on the calling thread, in order. It must not block for long:
     * one {@code FileOp} stream serves the whole node, and a sink that waits on a slow
     * browser holds up every other operation on that node. Write into the servlet
     * output stream and flush; that is fast enough and it is what backpressure through
     * a virtual thread is for.
     *
     * @throws FileOperationFailed if the node answered with a {@code FileError}
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no stream open
     */
    void stream(UUID nodeId, FileRequest request, Consumer<FileEvent> events);

    /**
     * Abandons an operation that is still running.
     *
     * <p>The customer closed the download, or navigated away from a directory whose size
     * was being measured. One stream carries every operation for a node, so an
     * abandoned tree walk keeps that node's disk busy until somebody says stop.
     *
     * <p>Does nothing if the request has already finished, and never throws: cancelling
     * something that is already over is the normal outcome of a browser closing a
     * connection.
     */
    void cancel(UUID nodeId, String requestId);
}
