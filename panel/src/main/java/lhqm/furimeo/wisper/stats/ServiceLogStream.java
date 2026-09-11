package lhqm.furimeo.wisper.stats;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.google.protobuf.Timestamp;

import jakarta.servlet.http.HttpServletResponse;
import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.LogRequest;
import lhqm.furimeo.wisper.proto.v1.LogStreamKind;

/**
 * Joins one node log subscription to one browser's Server-Sent Events connection.
 *
 * <p>{@code NodeLogs.follow} hands back chunks on a thread the gRPC implementation owns;
 * an {@link SseEmitter} writes to a servlet response. This is the piece in between, and
 * everything awkward about that boundary lives here so neither the controller nor the
 * transport has to know about it:
 *
 * <ul>
 * <li>Bytes become text without splitting a character ({@link LogTextDecoder}).</li>
 * <li>The subscription is closed on completion, timeout <em>and</em> error. All three fire
 *     for a customer who closed a tab, only one of them looks like success, and a
 *     subscription nobody closed leaves the node tailing a container for nobody.</li>
 * <li>An idle connection gets a comment frame every
 *     {@code wisper.stats.stream-keep-alive}, because a tunnel closes a stream that has
 *     said nothing and a healthy application says nothing for hours.</li>
 * </ul>
 *
 * <p>One scheduled thread for the whole feature, holding the live writers. It is not shared
 * with {@code LiveMetricFeed}'s: metric watchers are keyed by subject and fan out, log
 * writers are one per connection and fan out to nobody, and merging them would be a
 * registry with two meanings.
 */
@Component
public class ServiceLogStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServiceLogStream.class);

    private final NodeLogs nodeLogs;
    private final StatsSettings settings;
    private final Set<Writer> live = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService keepAlives;

    public ServiceLogStream(NodeLogs nodeLogs, StatsSettings settings) {
        this.nodeLogs = nodeLogs;
        this.settings = settings;
        this.keepAlives = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "log-stream-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, settings.streamKeepAlive().toSeconds());
        this.keepAlives.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.SECONDS);
    }

    /**
     * Subscribes to a node and returns the emitter the browser reads.
     *
     * @param nodeId   which machine holds the workload
     * @param request  the subscription, with its stream id already minted by the caller so
     *                 the browser's connection and this subscription share one name
     * @param response headers are set here: a cached event stream serves one customer's
     *                 logs to the next request for the same URL
     */
    public SseEmitter open(UUID nodeId, LogRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(settings.streamMaxDuration().toMillis());
        Writer writer = new Writer(emitter);
        live.add(writer);

        LogSubscription subscription = nodeLogs.follow(nodeId, request, writer::chunk);
        Runnable release = () -> {
            live.remove(writer);
            subscription.close();
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(failure -> release.run());
        return emitter;
    }

    /** How many browsers are reading a log right now. For tests and for shutdown. */
    public int openStreams() {
        return live.size();
    }

    @Override
    public void close() {
        keepAlives.shutdownNow();
        live.forEach(Writer::complete);
        live.clear();
    }

    private void tick() {
        for (Writer writer : live) {
            if (!writer.keepAlive()) {
                live.remove(writer);
            }
        }
    }

    /**
     * One browser's connection.
     *
     * <p>Synchronized because the gRPC thread delivering chunks and the keep-alive thread
     * both write, and an {@link SseEmitter} is not safe for concurrent use.
     */
    private static final class Writer {

        private final SseEmitter emitter;
        private final LogTextDecoder decoder = new LogTextDecoder();
        private boolean finished;

        private Writer(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private synchronized void chunk(LogChunk chunk) {
            if (finished) {
                return;
            }
            Instant at = timestampOf(chunk);
            String text = decoder.decode(chunk.getData().toByteArray());
            if (chunk.getEnd()) {
                send(LogEvent.ended(text + decoder.flush(), at, chunk.getDroppedBytes()));
                complete();
                return;
            }
            if (text.isEmpty() && chunk.getDroppedBytes() == 0) {
                // The whole chunk was the first bytes of a character. Nothing to show yet,
                // and an empty event would make a viewer scroll for no reason.
                return;
            }
            send(LogEvent.output(text, at,
                    chunk.getKind() == LogStreamKind.LOG_STREAM_KIND_STDERR,
                    chunk.getDroppedBytes()));
        }

        /** @return false when the browser has gone and this writer should be dropped */
        private synchronized boolean keepAlive() {
            if (finished) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().comment("alive"));
                return true;
            } catch (IOException | IllegalStateException gone) {
                log.debug("A log stream's browser has gone: {}", gone.toString());
                complete();
                return false;
            }
        }

        private synchronized void complete() {
            if (finished) {
                return;
            }
            finished = true;
            emitter.complete();
        }

        private void send(LogEvent event) {
            try {
                emitter.send(SseEmitter.event().name(event.end() ? "end" : "log")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException gone) {
                // The customer closed the tab. Completing releases the async context; the
                // node subscription is closed by the emitter's own callback.
                complete();
            }
        }

        private static Instant timestampOf(LogChunk chunk) {
            if (!chunk.hasAt()) {
                return Instant.now();
            }
            Timestamp at = chunk.getAt();
            return Instant.ofEpochSecond(at.getSeconds(), at.getNanos());
        }
    }
}
