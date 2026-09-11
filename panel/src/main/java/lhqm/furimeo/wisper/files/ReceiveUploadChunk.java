package lhqm.furimeo.wisper.files;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.protobuf.ByteString;

import lhqm.furimeo.wisper.proto.v1.FileChunk;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.UploadSession;
import lhqm.furimeo.wisper.proto.v1.WriteChunk;

/**
 * Forwards one chunk of an upload to the node.
 *
 * <p>The hot path of the file manager, and the one that has to survive a 4G connection in a
 * lift. Everything about it is arranged so that a chunk which does not arrive costs one
 * chunk: the client retries it, the node writes it at an offset derived from its index, and
 * a chunk that arrives twice overwrites itself with identical bytes.
 *
 * <h2>The offset is derived, never trusted</h2>
 *
 * <p>The client sends an index; the offset is {@code index * chunk_size} computed from the
 * session the panel registered. {@code files.proto} says why: an upload that believes a
 * client-supplied offset can be made to write anywhere in the file, which for a resumed
 * upload into an existing path is an arbitrary-write primitive. The node cross-checks the
 * two independently, so both sides have to be wrong for it to matter.
 *
 * <h2>The chunk checksum is the client's, not the panel's</h2>
 *
 * <p>When the client sends one it is forwarded verbatim, so the node is checking the bytes
 * the <em>browser</em> hashed and the check covers the whole path including this hop.
 * Recomputing it here would make it cover only the panel-to-node hop and quietly hide
 * corruption on the hop that actually corrupts things. The panel computes one only when the
 * client sent none, which is better than the node having nothing to check at all.
 */
@Component
public class ReceiveUploadChunk {

    private static final String SHA256_SHAPE = "[0-9a-f]{64}";

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;
    private final Clock clock;

    @Autowired
    public ReceiveUploadChunk(DispatchFileRequest dispatch, UploadSessions sessions) {
        this(dispatch, sessions, Clock.systemUTC());
    }

    ReceiveUploadChunk(DispatchFileRequest dispatch, UploadSessions sessions, Clock clock) {
        this.dispatch = dispatch;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Writes one chunk.
     *
     * @param chunkIndex     which chunk of the file this is
     * @param clientChecksum the browser's hex SHA-256 of these bytes, or null
     * @param data           the bytes, at most one chunk's worth
     * @throws UploadSessionUnknown if this panel is not tracking the session
     * @throws PathRejected         if the index or the length cannot belong to this upload
     * @throws FileOperationFailed  with {@code FILE_ERROR_CODE_CHECKSUM_MISMATCH} when the
     *         node disagrees about the bytes, which the client answers by resending
     */
    public ChunkReceipt accept(FileAccess access, String sessionId, long chunkIndex,
                               String clientChecksum, byte[] data) {
        UploadTicket ticket = sessions.require(access.serviceId(), sessionId);
        byte[] bytes = data == null ? new byte[0] : data;
        long offset = validate(ticket, chunkIndex, bytes.length);

        FileChunk chunk = FileChunk.newBuilder()
                .setOffset(offset)
                .setChunkIndex(chunkIndex)
                .setData(ByteString.copyFrom(bytes))
                .setLast(ticket.isLastChunk(chunkIndex))
                .setSha256(checksum(clientChecksum, bytes))
                .build();

        ChunkReceipt receipt = ChunkReceipt.of(sessionId, dispatch.call(access,
                FileRequest.newBuilder().setWrite(WriteChunk.newBuilder()
                        .setSession(sessionOf(ticket))
                        .setChunk(chunk))).ack(), ticket);
        sessions.touch(ticket, Instant.now(clock));
        return receipt;
    }

    /**
     * The session, repeated on every chunk.
     *
     * <p>{@code files.proto} carries it on each write rather than opening it once, because
     * the node may have restarted between chunks and has to be able to rebuild the session
     * from what is on disk plus this message.
     */
    private static UploadSession sessionOf(UploadTicket ticket) {
        return UploadSession.newBuilder()
                .setSessionId(ticket.nodeSessionId())
                .setPath(ticket.path().value())
                .setTotalBytes(ticket.totalBytes())
                .setContentSha256(ticket.contentSha256())
                .setChunkSize(ticket.chunkSize())
                .setOverwrite(ticket.overwrite())
                .build();
    }

    /**
     * Checks the chunk against the session and returns the offset it belongs at.
     *
     * <p>Three things a client can get wrong and one it can do on purpose. An index past the
     * end of the file, a chunk longer than the agreed size, and a chunk that would write past
     * the declared total are all refused here rather than forwarded - the node refuses them
     * too, but a round trip to be told what the panel already knew is a round trip on a
     * connection that is by assumption bad.
     */
    private static long validate(UploadTicket ticket, long chunkIndex, int length) {
        if (chunkIndex < 0 || (ticket.chunkCount() > 0 && chunkIndex >= ticket.chunkCount())) {
            throw PathRejected.malformed(ticket.path().value(), "Chunk " + chunkIndex
                    + " is not part of this upload, which has " + ticket.chunkCount()
                    + " chunk(s).");
        }
        if (length > ticket.chunkSize()) {
            throw PathRejected.malformed(ticket.path().value(), "That chunk is larger than the "
                    + ticket.chunkSize() + " bytes this upload agreed on.");
        }
        long offset = ticket.offsetOf(chunkIndex);
        if (offset + length > ticket.totalBytes()) {
            throw PathRejected.malformed(ticket.path().value(),
                    "That chunk would write past the end of the file the upload declared.");
        }
        return offset;
    }

    /** The client's checksum when there is one, otherwise the panel's over the same bytes. */
    private static String checksum(String clientChecksum, byte[] bytes) {
        if (clientChecksum != null && !clientChecksum.isBlank()) {
            String hex = clientChecksum.strip().toLowerCase(Locale.ROOT);
            if (!hex.matches(SHA256_SHAPE)) {
                throw PathRejected.malformed("",
                        "A chunk checksum has to be 64 hexadecimal characters, or left out.");
            }
            return hex;
        }
        return sha256(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
