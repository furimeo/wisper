package lhqm.furimeo.wisper.files;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.protobuf.ByteString;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.CompleteUpload;
import lhqm.furimeo.wisper.proto.v1.FileChunk;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.UploadSession;
import lhqm.furimeo.wisper.proto.v1.WriteChunk;

/**
 * Writes what the inline editor has in it back to the file.
 *
 * <p>A one-chunk upload, and deliberately the same path as any other upload rather than a
 * second write mechanism. {@code files.proto} says so out loud: saving an edited file is a
 * one-chunk session, so there is no second code path that has to stay consistent with the
 * first. The node assembles it, verifies the checksum and moves it into place atomically -
 * which is what stops a save that failed half way leaving a truncated configuration file
 * behind for an application that is reading it.
 *
 * <p>Refused above the chunk size. The editor does not open a file larger than
 * {@code wisper.files.max-editable-bytes} in the first place, and that is well under one
 * chunk; a save larger than a chunk means the client sent something the editor never loaded.
 */
@Component
public class SaveFileContent {

    private final DispatchFileRequest dispatch;
    private final StatFile stat;
    private final AuditTrail audit;
    private final FilesSettings settings;
    private final Clock clock;

    @Autowired
    public SaveFileContent(DispatchFileRequest dispatch, StatFile stat, AuditTrail audit,
                           FilesSettings settings) {
        this(dispatch, stat, audit, settings, Clock.systemUTC());
    }

    SaveFileContent(DispatchFileRequest dispatch, StatFile stat, AuditTrail audit,
                    FilesSettings settings, Clock clock) {
        this.dispatch = dispatch;
        this.stat = stat;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Replaces the file's contents.
     *
     * @param text what the editor holds, written as UTF-8
     * @return the file as it now is, so the editor can show its new size and timestamp
     * @throws PathRejected if the path names no file, or the text is larger than one chunk
     */
    public FileEntryView save(FileAccess access, RelativePath path, String text) {
        if (path.isRoot()) {
            throw PathRejected.malformed("", "Choose a file to save.");
        }
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > settings.chunkSizeBytes()) {
            throw PathRejected.malformed(path.value(), "That is larger than the "
                    + settings.chunkSizeBytes() + " bytes one save may write. Upload it as a file "
                    + "instead.");
        }
        // A fresh id per save: an editor save is never resumed, and reusing one would let a
        // half-written previous attempt be mistaken for part of this one.
        String sessionId = "editor-" + UUID.randomUUID();
        String checksum = sha256(bytes);

        UploadSession session = UploadSession.newBuilder()
                .setSessionId(sessionId)
                .setPath(path.value())
                .setTotalBytes(bytes.length)
                .setContentSha256(checksum)
                .setChunkSize(settings.chunkSizeBytes())
                // The file is being edited, so it exists and replacing it is the point.
                .setOverwrite(true)
                .build();

        dispatch.call(access, FileRequest.newBuilder()
                .setWrite(WriteChunk.newBuilder()
                        .setSession(session)
                        .setChunk(FileChunk.newBuilder()
                                .setOffset(0)
                                .setChunkIndex(0)
                                .setData(ByteString.copyFrom(bytes))
                                .setLast(true)
                                .setSha256(checksum))))
                .ack();
        dispatch.call(access, FileRequest.newBuilder()
                        .setComplete(CompleteUpload.newBuilder().setSessionId(sessionId)))
                .done();

        audit.record(AuditEntry.succeeded(access.actor(), "files.upload", access.target(),
                access.organizationId(),
                "edited " + path.value() + " (" + bytes.length + " bytes) in "
                        + access.root().label() + " at " + Instant.now(clock)));
        return stat.at(access, path);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
