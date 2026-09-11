package lhqm.furimeo.wisper.deploy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.LogStreamKind;

/**
 * Turns the byte runs a node pushes into numbered lines in {@code deployment_log}.
 *
 * <p>Called by {@code grpc} for every {@code LogChunk} whose source is
 * {@code LOG_SOURCE_BUILD} (panel-ports.md §3), and by this package for the lines the
 * panel writes itself.
 *
 * <h2>Chunks are not lines</h2>
 *
 * <p>A build tool writes half a line and then thinks for ten seconds, and a chunk
 * boundary can fall inside a multi-byte character. So bytes are accumulated per
 * deployment and split on newlines, and only complete lines are decoded - which is what
 * makes a UTF-8 character split across two chunks come out whole instead of as two
 * replacement marks.
 *
 * <h2>Numbering, and why it is held in memory</h2>
 *
 * <p>{@code (deployment_id, sequence)} is unique, and it is simultaneously the ordering,
 * the SSE resume cursor and the thing that rejects a chunk a node redelivers after a
 * reconnect. Asking the database for {@code max(sequence)} per line would be a second
 * round trip for every line of every build, so the counter is kept here and seeded from
 * the table the first time a deployment is seen - which is also what makes numbering
 * continue correctly when the panel restarts in the middle of somebody's build.
 *
 * <p>If a duplicate does land, the insert is refused by the index, the counter is
 * re-seeded from the table and the line is dropped. That is the correct outcome: the line
 * is already stored.
 */
@Component
public class AppendDeploymentLog {

    private static final Logger log = LoggerFactory.getLogger(AppendDeploymentLog.class);

    /** How much of an unterminated line is held before it is stored as a line anyway. */
    private static final int PARTIAL_LIMIT = DeploymentLog.MESSAGE_LIMIT;

    private final DeploymentLogRepository lines;
    private final DeploymentLogFeed feed;

    /**
     * One assembler per deployment currently producing output. Entries are dropped when
     * the build ends, so this holds only the builds in flight - a handful, not a history.
     */
    private final ConcurrentHashMap<UUID, LineAssembler> assemblers = new ConcurrentHashMap<>();

    public AppendDeploymentLog(DeploymentLogRepository lines, DeploymentLogFeed feed) {
        this.lines = lines;
        this.feed = feed;
    }

    /**
     * Stores whatever complete lines this chunk finishes, and remembers the rest.
     *
     * <p>{@code dropped_bytes} becomes a line of its own before the data does. A customer
     * told that output is missing can act on it; a customer shown a silent gap cannot
     * (design §7.6).
     */
    public void append(UUID deploymentId, LogChunk chunk) {
        if (deploymentId == null || chunk == null) {
            return;
        }
        LineAssembler assembler = assemblerFor(deploymentId);
        Instant at = timestampOf(chunk.getAt());
        synchronized (assembler) {
            if (chunk.getDroppedBytes() > 0) {
                store(deploymentId, assembler, DeploymentLogStream.SYSTEM,
                        chunk.getDroppedBytes() + " bytes of output were dropped by the node to "
                                + "keep the build running.", at);
            }
            DeploymentLogStream stream = chunk.getKind() == LogStreamKind.LOG_STREAM_KIND_STDERR
                    ? DeploymentLogStream.STDERR
                    : DeploymentLogStream.STDOUT;
            for (byte b : chunk.getData().toByteArray()) {
                if (b == '\n') {
                    store(deploymentId, assembler, stream, assembler.take(), at);
                } else {
                    assembler.push(b);
                    if (assembler.length() >= PARTIAL_LIMIT) {
                        store(deploymentId, assembler, stream, assembler.take(), at);
                    }
                }
            }
            if (chunk.getEnd()) {
                if (assembler.length() > 0) {
                    store(deploymentId, assembler, stream, assembler.take(), at);
                }
                close(deploymentId);
            }
        }
    }

    /**
     * Writes one line the panel is responsible for: which node was chosen, why a
     * deployment was refused, that a build was cancelled.
     *
     * <p>These are what makes a log readable when the build never printed anything of its
     * own. A deployment that failed at "no node has room" should say so in the pane the
     * customer is already looking at.
     */
    public void note(UUID deploymentId, String message) {
        if (deploymentId == null || message == null || message.isBlank()) {
            return;
        }
        LineAssembler assembler = assemblerFor(deploymentId);
        synchronized (assembler) {
            store(deploymentId, assembler, DeploymentLogStream.SYSTEM, message, Instant.now());
        }
    }

    /**
     * Releases the assembler for a deployment that has ended, and tells every browser
     * watching that there will be no more lines.
     *
     * <p>Idempotent: a build that ends with {@code end} on its last chunk and is then
     * completed by its {@code BuildCompleted} calls this twice, and the second call has
     * nothing to do.
     */
    public void close(UUID deploymentId) {
        assemblers.remove(deploymentId);
        feed.finish(deploymentId);
    }

    private LineAssembler assemblerFor(UUID deploymentId) {
        return assemblers.computeIfAbsent(deploymentId,
                id -> new LineAssembler(lines.lastSequence(id)));
    }

    private void store(UUID deploymentId, LineAssembler assembler, DeploymentLogStream stream,
                       String message, Instant at) {
        DeploymentLog line = DeploymentLog.of(UUID.randomUUID(), deploymentId,
                assembler.nextSequence(), stream, message, at);
        try {
            feed.publish(lines.save(line));
        } catch (DuplicateKeyException alreadyStored) {
            // The node redelivered a chunk after a reconnect, which is normal on a
            // tunnel. The index refused the duplicate; re-seed so the next line does not
            // collide too, and drop this one - it is already in the table.
            assembler.resetTo(lines.lastSequence(deploymentId));
            log.debug("Duplicate log line {} for deployment {} rejected by the cursor index",
                    line.sequence(), deploymentId);
        }
    }

    private static Instant timestampOf(Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return Instant.now();
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * The unterminated tail of one deployment's output, plus its line counter.
     *
     * <p>Bytes rather than characters: a line is decoded only once it is complete, so a
     * multi-byte character split across two chunks survives.
     */
    private static final class LineAssembler {

        private final ByteArrayOutputStream partial = new ByteArrayOutputStream(256);
        private long sequence;

        private LineAssembler(long lastStored) {
            this.sequence = lastStored;
        }

        private void push(byte b) {
            // A carriage return before a newline is Windows line ending, not content.
            // Progress bars that use \r alone are left as-is: cutting on it would turn
            // one animated line into a thousand rows.
            partial.write(b);
        }

        private int length() {
            return partial.size();
        }

        private String take() {
            byte[] bytes = partial.toByteArray();
            partial.reset();
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] == '\r') {
                end--;
            }
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }

        private long nextSequence() {
            return ++sequence;
        }

        private void resetTo(long lastStored) {
            sequence = lastStored;
        }
    }
}
