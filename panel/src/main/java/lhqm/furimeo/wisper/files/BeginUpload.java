package lhqm.furimeo.wisper.files;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.ResumeUpload;
import lhqm.furimeo.wisper.proto.v1.UploadState;

/**
 * Opens - or reopens - a resumable upload, and says what the node already has.
 *
 * <p>One endpoint for "start" and "resume", because from the client's side they are the same
 * request and telling them apart correctly is the client's hardest problem otherwise. It
 * registers the session, asks the node what is on disk for it, and answers with the ranges.
 * A fresh upload gets nothing back and starts at zero; one the customer is continuing after
 * losing signal gets its ranges and continues at the first gap.
 *
 * <p>That is also the recovery path when the <em>panel</em> restarted and lost its registry:
 * the browser is told the session is unknown, calls this again with the metadata it still
 * has, and the node - which never forgot - supplies the ranges. The design's answer to a
 * dropped connection, reused for a dropped process (design §8.2, §11.7).
 *
 * <p>Nothing is written to a customer's disk here. The first chunk does that, so a customer
 * who opens a file picker and changes their mind leaves nothing behind.
 */
@Component
public class BeginUpload {

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;
    private final FilesSettings settings;
    private final Clock clock;

    public BeginUpload(DispatchFileRequest dispatch, UploadSessions sessions,
                       FilesSettings settings) {
        this(dispatch, sessions, settings, Clock.systemUTC());
    }

    BeginUpload(DispatchFileRequest dispatch, UploadSessions sessions, FilesSettings settings,
                Clock clock) {
        this.dispatch = dispatch;
        this.sessions = sessions;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Registers the session and reports what can be skipped.
     *
     * @param sessionId     minted by the browser and stable across reloads
     * @param path          where the finished file goes, relative to the root
     * @param totalBytes    from {@code File.size}, so the node can refuse an upload that
     *                      cannot fit in the quota before it writes the first byte
     * @param contentSha256 hex SHA-256 of the whole file, or empty
     * @param overwrite     replace an existing file. False makes an accidental re-upload an
     *                      error the customer sees rather than a file they lose
     * @throws PathRejected if the session id, the path or the checksum is not usable
     */
    public UploadProgress start(FileAccess access, String sessionId, RelativePath path,
                                long totalBytes, String contentSha256, boolean overwrite) {
        if (path.isRoot()) {
            throw PathRejected.malformed("", "An upload needs a file name.");
        }
        if (totalBytes < 0) {
            throw PathRejected.malformed(path.value(), "A file cannot have a negative size.");
        }
        UploadTicket ticket = sessions.register(UploadTicket.opened(sessionId, access, path,
                totalBytes, contentSha256, settings.chunkSizeBytes(), overwrite,
                Instant.now(clock)));

        UploadState state = dispatch.call(access, FileRequest.newBuilder()
                        .setResume(ResumeUpload.newBuilder()
                                .setSessionId(ticket.nodeSessionId())))
                .state();
        if (!state.getKnown()) {
            // Nothing on disk yet, or the node's sweeper already took it. Either way the
            // client starts at zero, and it is told the chunk size to cut the file into.
            return UploadProgress.unknown(sessionId, totalBytes, ticket.chunkSize());
        }
        return withBrowserSessionId(sessionId, UploadProgress.of(state, ticket.chunkSize()));
    }

    /**
     * The node answers in the id it was given, which carries the service prefix.
     *
     * <p>The browser only knows the id it minted, and handing it back a different one would
     * make its next request address a session that does not exist from its point of view.
     */
    private static UploadProgress withBrowserSessionId(String sessionId, UploadProgress node) {
        return new UploadProgress(sessionId, node.known(), node.totalBytes(),
                node.receivedBytes(), node.received(), node.nextOffset(), node.chunkSize(),
                node.expiresAt(), node.complete());
    }
}
