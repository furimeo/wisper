package lhqm.furimeo.wisper.stats;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a subject id into a live Server-Sent Events stream of its readings.
 *
 * <p>Two event names and a comment frame. {@code series} carries the history the chart
 * opens with, so the graph is drawn before the first new sample arrives - a chart that
 * starts empty and fills over the next quarter of an hour is a chart nobody waits for.
 * {@code point} carries each reading as it is stored. The comments keep the tunnel from
 * closing a connection that has said nothing, which happens whenever the subject is idle.
 *
 * <h2>History first, then follow</h2>
 *
 * <p>The order is deliberate and the obvious one is wrong. Reading the history and then
 * subscribing loses everything stored in between, which is exactly the moment a customer
 * is watching. So the watcher is attached first and buffers, the history is loaded second,
 * and the buffer is flushed afterwards with anything older than the last historical point
 * dropped by timestamp.
 *
 * <p>Used by both metric controllers. The service chart and the node chart differ only in
 * which id they watch and who is allowed to watch it, and duplicating this would be two
 * SSE lifecycles with one bug each.
 */
@Component
public class MetricStream {

    private final LiveMetricFeed feed;
    private final StatsSettings settings;

    public MetricStream(LiveMetricFeed feed, StatsSettings settings) {
        this.feed = feed;
        this.settings = settings;
    }

    /**
     * Opens the stream.
     *
     * @param subjectId the service id, or the node id for a machine-level chart
     * @param history   what to draw immediately; also the cursor the follow phase
     *                  de-duplicates against
     * @param response  headers are set here because a cached event stream serves one
     *                  customer's chart to the next request for the same URL
     */
    public SseEmitter open(UUID subjectId, MetricSeries history, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // nginx and most tunnels buffer a response body by default, which turns a live
        // stream into a file that arrives when it ends.
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(settings.streamMaxDuration().toMillis());
        ChartWatcher watcher = new ChartWatcher(emitter);
        LiveMetricFeed.Subscription subscription = feed.watch(subjectId, watcher);

        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        // All three fire for a customer who closed a tab, and only one of them looks like
        // success. Registering fewer leaks the subscription and keeps the node sampling.
        emitter.onError(failure -> subscription.close());

        watcher.begin(history);
        return emitter;
    }

    /**
     * One browser's view of one subject.
     *
     * <p>Everything is synchronized: the ingest thread publishing a point, the keep-alive
     * thread and the request thread sending the history all reach it, and an
     * {@link SseEmitter} is not safe for concurrent writes.
     */
    private static final class ChartWatcher implements LiveMetricFeed.Watcher {

        private final SseEmitter emitter;
        private final List<MetricPoint> pending = new ArrayList<>();
        private Instant cursor = Instant.MIN;
        private boolean started;

        private ChartWatcher(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private synchronized void begin(MetricSeries history) {
            send("series", history);
            MetricPoint last = history.latest();
            if (last != null) {
                cursor = last.at();
            }
            started = true;
            List<MetricPoint> buffered = List.copyOf(pending);
            pending.clear();
            buffered.forEach(this::write);
        }

        @Override
        public synchronized void point(MetricPoint point) {
            if (!started) {
                pending.add(point);
                return;
            }
            write(point);
        }

        @Override
        public synchronized void keepAlive() {
            try {
                emitter.send(SseEmitter.event().comment("alive"));
            } catch (IOException | IllegalStateException gone) {
                throw closed(gone);
            }
        }

        private void write(MetricPoint point) {
            if (!point.at().isAfter(cursor)) {
                // Already in the history the chart was given, or redelivered.
                return;
            }
            send("point", point);
            cursor = point.at();
        }

        private void send(String name, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(name)
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException gone) {
                throw closed(gone);
            }
        }

        /**
         * Turns a dead connection into the unchecked throw the feed drops a watcher on. A
         * customer closing a tab is the normal end of a chart, not an incident.
         */
        private static RuntimeException closed(Exception cause) {
            return new IllegalStateException("The metric stream's browser has gone", cause);
        }
    }
}
