package lhqm.furimeo.wisper.files;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * The shells this panel process currently holds open.
 *
 * <p>A terminal is opened by one request and read by another: the browser posts for a
 * session, gets an id back, and then opens a WebSocket against it. Between those two the
 * PTY is already running and already producing output - a shell prints its prompt
 * immediately - so the bytes have to go somewhere. This is that somewhere, plus the map
 * that lets the socket find the session the post made.
 *
 * <h2>The buffer is bounded and drops the oldest</h2>
 *
 * <p>The window it covers is short - the milliseconds between the post returning and the
 * socket connecting - but a shell can print a lot in it, and output for a browser that is
 * not there yet would otherwise accumulate for as long as the shell runs. Dropping the
 * oldest keeps the newest, which for a terminal is the right end to keep: what the
 * customer wants to see is the last screen, not the first. Once a browser is attached the
 * backpressure moves to the socket, where {@link TerminalSocketSink} bounds it.
 *
 * <h2>Everything here dies with the process, and that is correct</h2>
 *
 * <p>A PTY is a live stream to a container, not a document. There is nothing to persist
 * and nothing to reconnect to after a panel restart - the node ends the session when its
 * stream goes - so the browser opens a new shell, which is what a customer expects from a
 * terminal that lost its connection.
 */
@Component
public class LiveTerminals implements AutoCloseable {

    /** Output held for a browser that has not attached yet. One screen's worth, generously. */
    private static final int MAX_BUFFERED_BYTES = 256 * 1024;

    /**
     * How often a session that is over is noticed.
     *
     * <p>Not a setting, and much shorter than the keep-alive: this is the latency between
     * a customer typing {@code exit} and being told the code it exited with. The node's
     * end of the session is a gRPC callback with nowhere to push to - {@code NodeTerminals}
     * hands the panel a byte sink and nothing else - so the panel learns about it by
     * looking. A second of lag on the last line of a session is invisible; twenty, which
     * is what sharing the keep-alive's period would cost, reads as a hung terminal.
     */
    private static final long REAP_INTERVAL_SECONDS = 1L;

    private final Map<String, Held> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers;
    private final long attachDeadlineNanos;

    public LiveTerminals(FilesSettings settings) {
        this.timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "terminal-timers");
            thread.setDaemon(true);
            return thread;
        });
        this.attachDeadlineNanos = settings.terminalAttachTimeout().toNanos();
        long keepAlive = Math.max(1L, settings.terminalKeepAlive().toSeconds());
        this.timers.scheduleWithFixedDelay(this::reap, REAP_INTERVAL_SECONDS,
                REAP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.timers.scheduleWithFixedDelay(this::ping, keepAlive, keepAlive, TimeUnit.SECONDS);
    }

    /**
     * Takes a place for a session that is about to be opened.
     *
     * <p>Before the node is asked, not after. The node writes the shell's prompt the
     * instant it attaches, and a sink that does not exist yet is output that is lost - so
     * the sink is created first and {@link Held#bind} supplies the session once the
     * handshake has finished. Anything that arrives in between is buffered like any other
     * output.
     */
    public Held reserve(String sessionId, UUID serviceId, UUID accountId) {
        Held held = new Held(sessionId, serviceId, accountId);
        sessions.put(sessionId, held);
        return held;
    }

    /**
     * A session, if this panel holds it and it belongs to this service.
     *
     * <p>The service is part of the lookup, not checked afterwards: a session id in a URL
     * is a guess anybody can make, and finding somebody else's shell because the id
     * matched is the failure this prevents.
     */
    public Optional<Held> find(UUID serviceId, String sessionId) {
        Held held = sessions.get(sessionId);
        if (held == null || !held.serviceId().equals(serviceId)) {
            return Optional.empty();
        }
        return Optional.of(held);
    }

    /** Ends a session and stops holding it. */
    public void release(String sessionId) {
        Held held = sessions.remove(sessionId);
        if (held != null) {
            held.finish();
        }
    }

    /** How many shells are open. For the reaper's arithmetic and for tests. */
    public int open() {
        return sessions.size();
    }

    @Override
    public void close() {
        timers.shutdownNow();
        sessions.keySet().forEach(this::release);
    }

    /**
     * Ends the sessions that are over.
     *
     * <p>Two ways to be over. The shell exited, was killed by the node's idle timeout or
     * lost its container - all of which arrive as a finished {@link TerminalSession} and
     * have to reach the browser as an exit frame. Or a browser minted a session and never
     * brought a socket: a tab closed in the second between the post and the upgrade would
     * otherwise leave a shell running as the customer's own application until the node's
     * idle timeout, fifteen minutes later.
     */
    private void reap() {
        for (Held held : sessions.values()) {
            if (held.isFinished() || held.isAbandoned(attachDeadlineNanos)) {
                release(held.sessionId());
            }
        }
    }

    /** Keeps attached sockets alive through a tunnel that closes quiet connections. */
    private void ping() {
        for (Held held : sessions.values()) {
            held.keepAlive();
        }
    }

    /** One open shell, and the browser reading it. */
    public static final class Held {

        private final String sessionId;
        private final UUID serviceId;
        private final UUID accountId;
        private final Deque<byte[]> pending = new ArrayDeque<>();
        private TerminalSession session;
        private TerminalSink sink;
        private long boundAt;
        private boolean everAttached;
        private int pendingBytes;
        private boolean ended;

        private Held(String sessionId, UUID serviceId, UUID accountId) {
            this.sessionId = sessionId;
            this.serviceId = serviceId;
            this.accountId = accountId;
        }

        public String sessionId() {
            return sessionId;
        }

        public UUID serviceId() {
            return serviceId;
        }

        /** Who opened it. A shell is not shared: only its owner may type into it. */
        public UUID accountId() {
            return accountId;
        }

        /**
         * The live session, once the node has attached.
         *
         * @throws TerminalUnavailable if the handshake has not finished, which a caller
         *         can only see by racing the request that opened it
         */
        public synchronized TerminalSession session() {
            if (session == null) {
                throw new TerminalUnavailable(null, sessionId, "the node has not attached yet");
            }
            return session;
        }

        /** Supplies the session once {@code NodeTerminals.open} has returned it. */
        synchronized void bind(TerminalSession attached) {
            this.session = attached;
            this.boundAt = System.nanoTime();
        }

        /** Whether the shell has ended, or never started. */
        synchronized boolean isFinished() {
            return ended || (session != null && session.isFinished());
        }

        /**
         * Whether a shell was opened for a browser that never connected.
         *
         * <p>Measured from the node attaching rather than from the reservation, because
         * everything before that is the node's own budget for dialling back and the
         * browser has not been told the session id yet.
         */
        synchronized boolean isAbandoned(long deadlineNanos) {
            return !everAttached && session != null
                    && System.nanoTime() - boundAt > deadlineNanos;
        }

        /** Output from the PTY. Called on a thread the gRPC implementation owns. */
        public synchronized void output(byte[] data) {
            if (data == null || data.length == 0 || ended) {
                return;
            }
            if (sink == null) {
                buffer(data);
                return;
            }
            if (!sink.output(data)) {
                detach("the connection went away");
                buffer(data);
            }
        }

        /**
         * Attaches a browser and flushes whatever the shell said while it was connecting.
         *
         * <p>One reader at a time. A second socket against the same session - a reopened
         * tab, or a reconnect the panel has not yet noticed the first half of - replaces
         * the first rather than splitting the output between two screens, each showing
         * half a command.
         *
         * <p>The size goes first. xterm.js sizes itself from it, and output written into a
         * terminal that still believes it is 80 columns wide wraps in the wrong place.
         */
        public synchronized void attach(TerminalSink next) {
            if (sink != null) {
                sink.close("another connection took this shell over");
            }
            sink = next;
            everAttached = true;
            if (session != null
                    && !next.ready(session.columns(), session.rows(), session.containerId())) {
                detach("the connection went away");
                return;
            }
            while (!pending.isEmpty()) {
                byte[] data = pending.peek();
                if (!next.output(data)) {
                    detach("the connection went away");
                    return;
                }
                pending.poll();
                pendingBytes -= data.length;
            }
        }

        /** Whether this is the connection currently reading the shell. */
        public synchronized boolean isAttachedTo(TerminalSink candidate) {
            return sink != null && sink == candidate;
        }

        /** Detaches the browser without ending the shell. */
        private synchronized void detach(String reason) {
            if (sink != null) {
                sink.close(reason);
                sink = null;
            }
        }

        /**
         * Keeps the attached socket open, and lets go of one that has died quietly.
         *
         * <p>Nobody reading is not a problem to solve here: the reaper ends a session no
         * browser ever attached to, and a socket that dropped mid-session releases its
         * shell from the handler's close callback.
         */
        private synchronized void keepAlive() {
            if (sink != null && !sink.keepAlive()) {
                detach("the connection went away");
            }
        }

        /**
         * Ends the session: closes the stream to the node, tells the browser why, and
         * lets go of the connection.
         *
         * <p>The node is closed first so the exit frame carries something true. A session
         * the customer ended themselves has no exit code of its own until the stream is
         * closed, and reading it beforehand reported zero for every one of them.
         */
        synchronized void finish() {
            if (ended) {
                return;
            }
            ended = true;
            if (session == null) {
                // The node never attached. There is nothing to close and no exit code to
                // report; the caller that reserved this place has already been told why.
                detach("the shell could not be opened");
                return;
            }
            session.close();
            if (sink != null) {
                sink.exit(session.exitCode(), session.exitReason());
            }
            detach("the shell ended");
        }

        private void buffer(byte[] data) {
            pending.add(data);
            pendingBytes += data.length;
            while (pendingBytes > MAX_BUFFERED_BYTES && !pending.isEmpty()) {
                // Keep the newest. What a customer wants when their connection recovers is
                // the last screen, not the first.
                pendingBytes -= pending.poll().length;
            }
        }
    }
}
