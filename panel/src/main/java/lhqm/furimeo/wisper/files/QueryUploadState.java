package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.ResumeUpload;
import lhqm.furimeo.wisper.proto.v1.UploadState;

/**
 * Asks the node what it already has of an upload.
 *
 * <p>The cheap poll a client makes while it is uploading, and the first thing it does after
 * a connection came back. Separate from {@link BeginUpload} because it needs no metadata:
 * the client has a session id and wants the ranges, and requiring it to resend the path, the
 * size and the checksum to ask a question would mean a client that lost that metadata could
 * not ask at all.
 *
 * <p>A session this panel is not tracking answers "unknown" rather than failing. The client
 * then calls {@link BeginUpload}, which does have the metadata and can ask the node
 * properly - so a panel restart costs one extra round trip and no bytes.
 */
@Component
public class QueryUploadState {

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;

    public QueryUploadState(DispatchFileRequest dispatch, UploadSessions sessions) {
        this.dispatch = dispatch;
        this.sessions = sessions;
    }

    /** What the node holds, coalesced into ranges, with the first gap worked out. */
    public UploadProgress of(FileAccess access, String sessionId) {
        UploadTicket ticket = sessions.find(access.serviceId(), sessionId).orElse(null);
        if (ticket == null) {
            return UploadProgress.unknown(sessionId, 0, 0);
        }
        UploadState state = dispatch.call(access, FileRequest.newBuilder()
                        .setResume(ResumeUpload.newBuilder()
                                .setSessionId(ticket.nodeSessionId())))
                .state();
        UploadProgress progress = UploadProgress.of(state, ticket.chunkSize());
        // Answer in the id the browser minted; it has never seen the service prefix.
        return new UploadProgress(sessionId, progress.known(),
                progress.totalBytes() > 0 ? progress.totalBytes() : ticket.totalBytes(),
                progress.receivedBytes(), progress.received(), progress.nextOffset(),
                ticket.chunkSize(), progress.expiresAt(), progress.complete());
    }
}
