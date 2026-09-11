package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.AbortUpload;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Throws away a partial upload.
 *
 * <p>The customer pressed cancel, or closed the tab and the client sent a beacon on its way
 * out. Deleting the parts now rather than leaving them for the node's own sweeper matters
 * because the parts count against the customer's quota: a phone that gave up on three
 * attempts at a 2 GB file has 6 GB of nothing on disk until the TTL expires, and the next
 * upload fails for a reason that makes no sense to them.
 *
 * <p>Not audited. Nothing the customer owns changed - the file was never assembled - and an
 * entry per abandoned upload would fill the trail with the ordinary noise of a bad
 * connection.
 *
 * <p>Forgetting the session is unconditional. If the node refuses the abort, the parts are
 * still swept by its own TTL, and keeping a ticket for an upload nobody will continue means
 * the panel's sweep tries again forever.
 */
@Component
public class AbandonUpload {

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;

    public AbandonUpload(DispatchFileRequest dispatch, UploadSessions sessions) {
        this.dispatch = dispatch;
        this.sessions = sessions;
    }

    /**
     * Abandons the session.
     *
     * <p>Returns quietly for a session this panel does not know: a client cancelling an
     * upload it had already finished, or one whose panel restarted, is not an error and there
     * is nothing for the customer to do about it.
     */
    public void abandon(FileAccess access, String sessionId) {
        UploadTicket ticket = sessions.find(access.serviceId(), sessionId).orElse(null);
        if (ticket == null) {
            return;
        }
        try {
            dispatch.call(access, FileRequest.newBuilder()
                            .setAbort(AbortUpload.newBuilder()
                                    .setSessionId(ticket.nodeSessionId())))
                    .done();
        } finally {
            sessions.forget(access.serviceId(), sessionId);
        }
    }
}
