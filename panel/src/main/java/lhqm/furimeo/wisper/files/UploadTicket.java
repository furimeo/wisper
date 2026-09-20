package lhqm.furimeo.wisper.files;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One upload the panel is tracking, from the first chunk to the last.
 *
 * <p>The session id is minted by the <em>browser</em> ({@code files.proto}), which is what
 * makes a resume survive a page reload: the client stores it next to the file it picked and
 * carries on with the same name. The panel writes it down so the chunk requests that follow
 * can be four fields instead of eight, and so the sweep knows which uploads have been
 * abandoned.
 *
 * <h2>The id the node sees is not the id the browser chose</h2>
 *
 * <p>{@link #nodeSessionId()} prefixes the service id. One node holds many tenants' roots
 * and the session id ends up naming the partial file on disk; two customers picking the same
 * id - by collision or on purpose - must not be able to reach each other's parts. The
 * browser never sees the prefix and does not need to.
 *
 * @param path          where the finished file goes, relative to the root
 * @param contentSha256 hex SHA-256 of the whole file, verified by the node at completion, or
 *                      empty when the client could not compute it
 * @param lastActivityAt when a chunk last arrived. The sweep's input, and the reason this is
 *                      a record with a wither rather than a mutable holder
 */
public record UploadTicket(
        String sessionId,
        UUID serviceId,
        UUID nodeId,
        UUID organizationId,
        String rootId,
        RelativePath path,
        long totalBytes,
        String contentSha256,
        long chunkSize,
        boolean overwrite,
        String actorLabel,
        Instant startedAt,
        Instant lastActivityAt) {

    /**
     * What a session id may look like.
     *
     * <p>It becomes part of a filename on the node, so it is restricted to characters that
     * cannot mean anything to a path: no slash, no dot, no space. A UUID from the browser
     * fits, which is what the client sends.
     */
    private static final String SESSION_ID_SHAPE = "[A-Za-z0-9_-]{8,64}";

    /** Hex SHA-256, or nothing at all. */
    private static final String SHA256_SHAPE = "[0-9a-f]{64}";

    /** A new session, as {@code BeginUpload} registers it. */
    public static UploadTicket opened(String sessionId, FileAccess access, RelativePath path,
                                      long totalBytes, String contentSha256, long chunkSize,
                                      boolean overwrite, Instant at) {
        return new UploadTicket(
                requireSessionId(sessionId),
                access.serviceId(),
                access.requireNodeId(),
                access.organizationId(),
                access.rootId(),
                path,
                totalBytes,
                normaliseChecksum(contentSha256),
                chunkSize,
                overwrite,
                access.actor().label(),
                at,
                at);
    }

    /** The same session, with the clock moved on. */
    public UploadTicket touched(Instant at) {
        return new UploadTicket(sessionId, serviceId, nodeId, organizationId, rootId, path,
                totalBytes, contentSha256, chunkSize, overwrite, actorLabel, startedAt, at);
    }

    /**
     * The id the node knows this upload by, namespaced so two tenants cannot collide.
     *
     * <p>Uses the first 8 hex chars of the service UUID as a prefix, joined with
     * {@code _}: {@code 03e93925_6afe0024-...}. This stays inside the node's
     * {@code checkIdentifier} rules (letters, digits, {@code -}, {@code _}, {@code .},
     * max 64 chars) — the previous {@code serviceId + ":" + sessionId} was 73 chars
     * and contained a colon, both of which the node rejected on every chunk.
     */
    public String nodeSessionId() {
        return serviceId.toString().substring(0, 8) + "_" + sessionId;
    }

    /** How many chunks the whole file is, given the size the client was told. */
    public long chunkCount() {
        if (chunkSize <= 0) {
            return 0;
        }
        return (totalBytes + chunkSize - 1) / chunkSize;
    }

    /** Where a chunk belongs in the file. The node cross-checks this against what it is sent. */
    public long offsetOf(long chunkIndex) {
        return chunkIndex * chunkSize;
    }

    /** Whether this chunk is the last one, which the node treats as a hint. */
    public boolean isLastChunk(long chunkIndex) {
        return chunkIndex >= chunkCount() - 1;
    }

    /** Whether the sweep should abandon this upload. */
    public boolean isIdleSince(Instant cutoff) {
        return lastActivityAt.isBefore(cutoff);
    }

    /**
     * A session id from the browser, checked.
     *
     * @throws PathRejected if it could not be one, because it would become a filename
     */
    public static String requireSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches(SESSION_ID_SHAPE)) {
            throw PathRejected.malformed(String.valueOf(sessionId),
                    "An upload session id is 8 to 64 letters, digits, dashes or underscores.");
        }
        return sessionId;
    }

    /**
     * A whole-file checksum, lower-cased, or empty.
     *
     * <p>Empty is allowed: a client that cannot hash a 3 GB file on a phone before it starts
     * still gets per-chunk checksums, which catch the corruption mobile networks actually
     * produce. What it loses is the check that a resumed upload picked up the same file, and
     * that is the client's trade to make rather than a reason to refuse the upload.
     */
    private static String normaliseChecksum(String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return "";
        }
        String hex = contentSha256.strip().toLowerCase(Locale.ROOT);
        if (!hex.matches(SHA256_SHAPE)) {
            throw PathRejected.malformed("",
                    "The file checksum has to be 64 hexadecimal characters, or left out.");
        }
        return hex;
    }
}
