package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.CompleteUpload;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Assembles the chunks into the file.
 *
 * <p>A separate step from the last chunk because assembly can fail on its own terms - the
 * whole-file checksum does not match, a quota filled while the upload was in flight, the
 * destination appeared underneath it - and each of those has to be reportable as something
 * other than "the last chunk failed". Until this succeeds there is no file at the
 * destination, only parts, which is what makes an abandoned upload invisible to the customer
 * rather than a half-written file their application picks up.
 *
 * <p>Stats the result afterwards so the file manager can put the new entry in the list
 * without reloading the directory. One extra round trip, at the end of an upload that took
 * minutes.
 */
@Component
public class FinishUpload {

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;
    private final StatFile stat;
    private final AuditTrail audit;

    public FinishUpload(DispatchFileRequest dispatch, UploadSessions sessions, StatFile stat,
                        AuditTrail audit) {
        this.dispatch = dispatch;
        this.sessions = sessions;
        this.stat = stat;
        this.audit = audit;
    }

    /**
     * Completes the upload and returns the file that now exists.
     *
     * @throws UploadSessionUnknown if this panel is not tracking the session
     * @throws FileOperationFailed  with {@code FILE_ERROR_CODE_CHECKSUM_MISMATCH} when the
     *         assembled file is not the one the client described - which is what a resumed
     *         upload that picked up a different version of the file looks like, and why it
     *         fails loudly here rather than producing a plausible corrupt result
     */
    public FileEntryView complete(FileAccess access, String sessionId) {
        UploadTicket ticket = sessions.require(access.serviceId(), sessionId);
        dispatch.call(access, FileRequest.newBuilder()
                        .setComplete(CompleteUpload.newBuilder()
                                .setSessionId(ticket.nodeSessionId())))
                .done();
        sessions.forget(access.serviceId(), sessionId);

        audit.record(AuditEntry.succeeded(access.actor(), "files.upload", access.target(),
                access.organizationId(),
                "uploaded " + ticket.path().value() + " (" + ticket.totalBytes() + " bytes) to "
                        + access.root().label()
                        + (ticket.overwrite() ? ", replacing what was there" : "")));
        return stat.at(access, ticket.path());
    }
}
