package lhqm.furimeo.wisper.files;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The shells this panel process currently holds open.
 *
 * <p>A terminal is opened by one request and read by another: the browser asks for a
 * session, gets an id back, and then opens an {@code EventSource} against it. Between those
 * two the PTY is already running and already producing output - a shell prints its prompt
 * immediately - so the bytes have to go somewhere. This is that somewhere, plus the map that
 * lets the second request find the session the first one made.
 *
 * <h2>The buffer is bounded and drops the oldest</h2>
 *
 * <p>If the browser never comes back, output would otherwise accumulate for as long as the
 * shell runs. Dropping the oldest keeps the newest, which for a terminal is the right end to
 * keep: what the customer wants to see when their connection recovers is the last screen, not
 * the first.
 *
 * <h2>Everything here dies with the process, and that is correct</h2>
 *
 * <p>A PTY is a live stream to a container, not a document. There is nothing to persist and
 * nothing to reconnect to after a panel restart - the node ends the session when its stream
 * goes - so the browser opens a new shell, which is what a customer expects from a terminal
 * that lost its connection.
 */
@Component
public class LiveTerminals implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveTerminals.class);

    /** Output held for a browser that has not attached yet. One screen's worth, generously. */
    private static final int MAX_BUFFERED_BYTES = 256 * 1024;

    private final Map<String, Held> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlives;

    public LiveTerminals(FilesSettings settings) {
        this.keepAlives = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "terminal-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, settings.terminalKeepAlive().toSeconds());
        this.keepAlives.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.SECONDS);
    }

    /**
     * Takes a place for a session that is about to be opened.
     *
     * <p>Before the node is asked, not after. The node writes the shell's prompt the instant
     * it attaches, and a sink that does not exist yet is output that is lost - so the sink is
     * created first and {@link Held#bind} supplies the session once the handshake has
     * finished. Anything that arrives in between is buffered like any other output.
     */
    public Held reserve(String sessionId, UUID serviceId, UUID accountId) {
        Held held = new Held(sessionId, serviceId, accountId);
        sessions.put(sessionId, held);
        return held;
    }

    /**
     * A session, if this panel holds it and it belongs to this service.
     *
     * <p>The service is part of the lookup, not checked afterwards: a session id in a URL is
     * a guess anybody can make, and finding somebody else's shell because the id matched is
     * the failure this prevents.
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

    /** How many shells are open. For the sweep's log line and for tests. */
    public int open() {
        return sessions.size();
    }

    @Override
    public void close() {
        keepAlives.shutdownNow();
        sessions.keySet().forEach(this::release);
    }

    /**
     * Keeps attached streams alive and reaps the ones that are over.
     *
     * <p>Two jobs on one thread because they are the same tick: a session whose shell exited
     * has to be released whether or not a browser is still attached, and a browser that is
     * attached to a live shell has to hear something so the tunnel does not close it.
     */
    private void tick() {
        for (Held held : sessions.values()) {
            if (held.isFinished() || !held.keepAlive()) {
                release(held.sessionId());
            }
        }
    }

    /** One open shell, and the browser reading it. */
    public static final class Held {

        private final String sessionId;
        private final UUID serviceId;
        private final UUID accountId;
        private final Deque<byte[]> pending = new ArrayDeque<>();
        private TerminalSession session;
        private SseEmitter emitter;
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
         * @throws TerminalUnavailable if the handshake has not finished, which a caller can
         *         only see by racing the request that opened it
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
        }

        /** Whether the shell has ended, or never started. */
        synchronized boolean isFinished() {
            return ended || (session != null && session.isFinished());
        }

        /** Output from the PTY. Called on a thread the gRPC implementation owns. */
        public synchronized void output(byte[] data) {
            if (data == null || data.length == 0 || ended) {
                return;
            }
            if (emitter == null) {
                buffer(data);
                return;
            }
            if (!send("out", TerminalFrameCodec.encode(data))) {
                detach();
                buffer(data);
            }
        }

        /**
         * Attaches a browser and flushes whatever the shell said while it was connecting.
         *
         * <p>One reader at a time. A second {@code EventSource} against the same session -
         * a reopened tab, a duplicated request - replaces the first rather than splitting the
         * output between two screens, each showing half a command.
         */
        public synchronized void attach(SseEmitter next) {
            if (emitter != null) {
                emitter.complete();
            }
            emitter = next;
            while (!pending.isEmpty()) {
                byte[] data = pending.poll();
                pendingBytes -= data.length;
                if (!send("out", TerminalFrameCodec.encode(data))) {
                    detach();
                    return;
                }
            }
            if (session != null) {
                send("ready", "{\"columns\":" + session.columns() + ",\"rows\":" + session.rows()
                        + ",\"containerId\":\"" + escape(session.containerId()) + "\"}");
            }
        }

        /** Detaches the browser without ending the shell. */
        public synchronized void detach() {
            if (emitter != null) {
                emitter.complete();
                emitter = null;
            }
        }

        /** @return false when the attached browser has gone */
        private synchronized boolean keepAlive() {
            if (emitter == null) {
                // Nobody is reading. That is fine while a browser reconnects; the node's own
                // idle timeout ends a shell nobody ever comes back to.
                return true;
            }
            try {
                emitter.send(SseEmitter.event().comment("alive"));
                return true;
            } catch (IOException | IllegalStateException gone) {
                detach();
                return true;
            }
        }

        /** Ends the session: tells the browser why, then closes the stream to the node. */
        synchronized void finish() {
            if (ended) {
                return;
            }
            ended = true;
            if (session == null) {
                // The node never attached. There is nothing to close and no exit code to
                // report; the caller that reserved this place has already been told why.
                detach();
                return;
            }
            send("exit", "{\"code\":" + session.exitCode() + ",\"reason\":\""
                    + escape(session.exitReason()) + "\"}");
            detach();
            session.close();
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

        private boolean send(String name, String payload) {
            if (emitter == null) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
                return true;
            } catch (IOException | IllegalStateException gone) {
                log.debug("Terminal {} lost its browser: {}", sessionId, gone.toString());
                return false;
            }
        }

        private static String escape(String text) {
            return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
