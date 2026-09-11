package lhqm.furimeo.wisper.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Everything under {@code wisper.files}: the file manager and the web terminal, which are
 * one package because they are one authorization surface (panel-http.md).
 *
 * <p>{@code upload-chunk-size} and {@code orphan-chunk-ttl} are already in
 * {@code application.yml} and are part of the wire contract with the browser: the chunk
 * size is what the client is told to cut the file into, and the TTL is how long a
 * half-finished upload survives on the node before it is swept (design §8.2, §11.7). The
 * rest are panel-side limits with defaults.
 *
 * @param uploadChunkSize      bytes per chunk. Eight megabytes is a compromise between a
 *                             4G connection that drops - where a lost chunk is work to
 *                             redo - and a phone that has to hold one in memory while it
 *                             is acknowledged
 * @param orphanChunkTtl       how long the node keeps the parts of an upload nobody has
 *                             continued. Echoed to the node in {@code RetentionPolicy} by
 *                             {@code placement}; the panel uses it to decide when a session
 *                             it is tracking has been abandoned
 * @param listPageSize         directory entries per page. A customer with a
 *                             {@code node_modules} has a hundred thousand, and a phone sent
 *                             all of them renders none
 * @param maxListPageSize      ceiling on a client-supplied page size
 * @param maxEditableBytes     largest file the inline editor will open. Past this the
 *                             browser is offered the first part read-only, because loading
 *                             a 400 MB log into CodeMirror hangs the tab
 * @param operationTimeout     how long any single file operation may take. The node holds
 *                             one {@code FileOp} stream per machine, so an operation that
 *                             never answers would hold up every other one on it
 * @param uploadIdleTimeout    how long a tracked upload session may go without a chunk
 *                             before the sweep abandons it on the node
 * @param sweepInterval        how often that sweep runs
 * @param terminalAttachTimeout how long to wait for a node to dial back with the PTY. A
 *                             node pulling an image can take seconds; one whose stream has
 *                             dropped will never answer
 * @param terminalIdleTimeout  how long a shell may sit with no input before the node kills
 *                             it. A forgotten tab is otherwise a shell running as the
 *                             customer's application, for weeks
 * @param terminalMaxDuration  hard ceiling on a session regardless of activity, so a
 *                             wedged {@code top} cannot hold a slot forever
 * @param terminalKeepAlive    how often an idle terminal stream sends a comment frame, so
 *                             a tunnel does not close a shell somebody is reading
 */
@ConfigurationProperties("wisper.files")
public record FilesSettings(
        @DefaultValue("8MB") DataSize uploadChunkSize,
        @DefaultValue("24h") Duration orphanChunkTtl,
        @DefaultValue("200") int listPageSize,
        @DefaultValue("1000") int maxListPageSize,
        @DefaultValue("2MB") DataSize maxEditableBytes,
        @DefaultValue("2m") Duration operationTimeout,
        @DefaultValue("30m") Duration uploadIdleTimeout,
        @DefaultValue("15m") Duration sweepInterval,
        @DefaultValue("20s") Duration terminalAttachTimeout,
        @DefaultValue("15m") Duration terminalIdleTimeout,
        @DefaultValue("4h") Duration terminalMaxDuration,
        @DefaultValue("20s") Duration terminalKeepAlive) {

    public FilesSettings {
        requirePositive("upload-chunk-size", uploadChunkSize.toBytes());
        requirePositive("list-page-size", listPageSize);
        requirePositive("max-list-page-size", maxListPageSize);
        requirePositive("max-editable-bytes", maxEditableBytes.toBytes());
        if (uploadChunkSize.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("wisper.files.upload-chunk-size has to fit in an "
                    + "int: a chunk is held in memory while it is forwarded, and one larger than "
                    + "2 GB could not be anyway.");
        }
        if (listPageSize > maxListPageSize) {
            throw new IllegalArgumentException("wisper.files.list-page-size cannot be larger than "
                    + "max-list-page-size, or the default would be refused as too large.");
        }
        requirePositive("orphan-chunk-ttl", orphanChunkTtl);
        requirePositive("operation-timeout", operationTimeout);
        requirePositive("upload-idle-timeout", uploadIdleTimeout);
        if (uploadIdleTimeout.compareTo(orphanChunkTtl) >= 0) {
            // The panel's sweep exists to free a customer's quota sooner than the node's own
            // TTL would. Set the other way round it never fires first, the sweep does nothing
            // and the setting silently means nothing.
            throw new IllegalArgumentException("wisper.files.upload-idle-timeout must be shorter "
                    + "than orphan-chunk-ttl, which is when the node sweeps the parts itself. "
                    + "It was " + uploadIdleTimeout + " against " + orphanChunkTtl + ".");
        }
        requirePositive("sweep-interval", sweepInterval);
        requirePositive("terminal-attach-timeout", terminalAttachTimeout);
        requirePositive("terminal-idle-timeout", terminalIdleTimeout);
        requirePositive("terminal-max-duration", terminalMaxDuration);
        requirePositive("terminal-keep-alive", terminalKeepAlive);
    }

    /** The chunk size as the int a buffer needs. */
    public int chunkSizeBytes() {
        return (int) uploadChunkSize.toBytes();
    }

    /** A client-supplied page size, clamped, or the default when nothing was asked for. */
    public int pageSize(int requested) {
        if (requested <= 0) {
            return listPageSize;
        }
        return Math.min(requested, maxListPageSize);
    }

    private static void requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "wisper.files." + key + " must be greater than zero, not " + value);
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "wisper.files." + key + " must be a positive duration, not " + value);
        }
    }
}
