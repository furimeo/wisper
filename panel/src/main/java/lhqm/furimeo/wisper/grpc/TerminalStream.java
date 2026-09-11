package lhqm.furimeo.wisper.grpc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.TerminalSession;
import lhqm.furimeo.wisper.proto.v1.TerminalAttached;
import lhqm.furimeo.wisper.proto.v1.TerminalExit;
import lhqm.furimeo.wisper.proto.v1.TerminalFrame;
import lhqm.furimeo.wisper.proto.v1.TerminalResize;

/**
 * One open PTY, from the panel's side.
 *
 * <p>Implements {@link TerminalSession}, so every method on it is one of the frame types
 * in {@code terminal.proto} and there is no way to put an unframed byte slice on the wire.
 * That absence is the whole point: the predecessor pumped a PTY through a socket with an
 * unframed copy, so a resize was indistinguishable from keystrokes and the exit code was
 * lost - the terminal simply stopped (design §11.5).
 *
 * <h2>It exists before the node has attached</h2>
 *
 * <p>The panel cannot dial a node, so opening a terminal is two steps: a
 * {@code StartTerminal} command down the control stream, and a {@code Terminal} call the
 * node dials back whose first frame is {@code TerminalAttached}. This object is created
 * for the first step and completed by the second, and {@link #awaitAttach} is what joins
 * them. Nothing may be written before that: the observer is not there yet.
 */
final class TerminalStream implements TerminalSession {

    private static final Logger log = LoggerFactory.getLogger(TerminalStream.class);

    private final String sessionId;
    private final UUID nodeId;
    private final String workloadId;
    private final Consumer<byte[]> fromNode;
    private final Runnable release;
    private final CountDownLatch attached = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile StreamObserver<TerminalFrame> toNode;
    private volatile String containerId = "";
    private volatile int columns;
    private volatile int rows;
    private volatile boolean finished;
    private volatile int exitCode;
    private volatile String exitReason = "";

    /**
     * @param release removes this session from the registry; run once, whichever way the
     *                session ends, so an abandoned tab does not leave an entry behind
     */
    TerminalStream(String sessionId, UUID nodeId, String workloadId, Consumer<byte[]> fromNode,
                   Runnable release) {
        this.sessionId = sessionId;
        this.nodeId = nodeId;
        this.workloadId = workloadId;
        this.fromNode = fromNode;
        this.release = release;
    }

    UUID nodeId() {
        return nodeId;
    }

    String workloadId() {
        return workloadId;
    }

    /** Waits for the node to dial back. False means it did not, and the caller gives up. */
    boolean awaitAttach(Duration limit) {
        try {
            return attached.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The node's first frame: the PTY is open and bytes are about to flow. */
    void bind(StreamObserver<TerminalFrame> stream, TerminalAttached frame) {
        this.toNode = stream;
        this.containerId = frame.getContainerId();
        // Echoed back after clamping: a browser can ask for a size the PTY will not take,
        // and the client has to be told what it actually got.
        this.columns = frame.getCols();
        this.rows = frame.getRows();
        attached.countDown();
    }

    /** PTY output on its way to the browser. */
    void output(ByteString data) {
        if (data.isEmpty()) {
            return;
        }
        try {
            fromNode.accept(data.toByteArray());
        } catch (RuntimeException browserGone) {
            // The sink is a browser connection that has closed. Ending the session is the
            // right answer: nobody is reading the shell any more.
            log.debug("Terminal {} lost its reader: {}", sessionId, browserGone.toString());
            close();
        }
    }

    /** The node's last frame: the process ended and this says how. */
    void exited(TerminalExit exit) {
        exitCode = exit.getCode();
        exitReason = exit.getReason();
        finished = true;
        release.run();
    }

    /**
     * The stream ended without a {@code TerminalExit}.
     *
     * <p>The tunnel dropped it, or the daemon restarted. 137 is what a shell reports for a
     * process killed by SIGKILL, and it is the closest true thing the panel can say: the
     * PTY is gone and it did not exit on its own.
     */
    void ended(String reason) {
        if (finished) {
            return;
        }
        exitCode = 137;
        exitReason = reason;
        finished = true;
        release.run();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public String containerId() {
        return containerId;
    }

    @Override
    public int columns() {
        return columns;
    }

    @Override
    public int rows() {
        return rows;
    }

    @Override
    public void send(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        write(frame().setData(ByteString.copyFrom(data)).build());
    }

    @Override
    public void resize(int newColumns, int newRows) {
        if (newColumns <= 0 || newRows <= 0) {
            return;
        }
        columns = newColumns;
        rows = newRows;
        write(frame().setResize(TerminalResize.newBuilder()
                .setCols(newColumns)
                .setRows(newRows)).build());
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public int exitCode() {
        return exitCode;
    }

    @Override
    public String exitReason() {
        return exitReason;
    }

    /**
     * Ends the response side of the call, which kills the PTY.
     *
     * <p>Idempotent and silent about everything, because this is called from a
     * {@code finally} and from a servlet completion callback and neither has anywhere to
     * put a failure.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        StreamObserver<TerminalFrame> stream = toNode;
        if (stream != null) {
            try {
                synchronized (this) {
                    stream.onCompleted();
                }
            } catch (RuntimeException alreadyGone) {
                log.debug("Terminal {} was already closed by the node", sessionId);
            }
        }
        if (!finished) {
            exitReason = "the panel closed the session";
            finished = true;
        }
        release.run();
    }

    /**
     * The write, serialised.
     *
     * <p>Keystrokes arrive on the request thread and a resize on another; a
     * {@link StreamObserver} is not safe for either to use while the other does. A write
     * to a session that is over is dropped rather than thrown: the browser is allowed to
     * send one more keystroke while the shell is exiting, and that is not an error anyone
     * can act on.
     */
    private synchronized void write(TerminalFrame message) {
        StreamObserver<TerminalFrame> stream = toNode;
        if (stream == null || closed.get() || finished) {
            log.debug("Dropping a {} frame for terminal {}, which is over",
                    message.getPayloadCase(), sessionId);
            return;
        }
        try {
            stream.onNext(message);
        } catch (RuntimeException streamGone) {
            ended("the terminal stream ended: " + streamGone.getMessage());
        }
    }

    private TerminalFrame.Builder frame() {
        // Every frame carries the session id, including the ones where the stream already
        // implies it. It costs a few bytes and turns a routing mistake into a dropped
        // frame instead of input typed into somebody else's shell.
        return TerminalFrame.newBuilder().setSessionId(sessionId);
    }
}
