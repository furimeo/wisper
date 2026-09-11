package lhqm.furimeo.wisper.stats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hands each stored reading to the charts currently watching that subject.
 *
 * <p>The reading is written to {@code stat_sample} first and published here second, so
 * what a watcher receives is exactly what a reload would fetch. Publishing first and
 * storing separately gives two renderings of one workload that disagree the moment an
 * insert is refused - which happens routinely here, because a resent sample is rejected by
 * the unique index on purpose.
 *
 * <p>In memory and per panel process, deliberately. A customer watching a chart is
 * connected to one panel and the panel is one process by design (design §5.6).
 *
 * <h2>Keep-alive</h2>
 *
 * <p>One scheduled thread ticks every {@code wisper.stats.stream-keep-alive} and asks every
 * watcher to send a comment frame. A tunnel closes a connection that has said nothing, and
 * a chart of a service that is idle says nothing for minutes - so without this the graph
 * goes dead exactly when the customer is watching to see whether anything is happening.
 *
 * <p>The same shape as {@code deploy.DeploymentLogFeed}, and deliberately not shared with
 * it: the two carry different payloads, are keyed by different things and have different
 * lifetimes, and the only thing they have in common is a {@code CopyOnWriteArrayList}.
 */
@Component
public class LiveMetricFeed implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricFeed.class);

    /**
     * What a chart can be asked to do. Every method may throw, and a throw means the
     * browser has gone and the watcher is dropped.
     */
    public interface Watcher {

        /** One point, as soon as it is stored. */
        void point(MetricPoint point);

        /** Send something down an idle connection so the tunnel does not close it. */
        void keepAlive();
    }

    /** Detaches a watcher. Idempotent, and safe to call from an SSE callback. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<UUID, CopyOnWriteArrayList<Watcher>> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlives;

    public LiveMetricFeed(StatsSettings settings) {
        this.keepAlives = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metric-stream-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, settings.streamKeepAlive().toSeconds());
        this.keepAlives.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.SECONDS);
    }

    /**
     * Attaches a chart to one subject's readings.
     *
     * @param subjectId the service id, or the node id for a machine-level chart
     */
    public Subscription watch(UUID subjectId, Watcher watcher) {
        watchers.computeIfAbsent(subjectId, id -> new CopyOnWriteArrayList<>()).add(watcher);
        return () -> detach(subjectId, watcher);
    }

    /** Sends one stored point to everyone watching that subject. */
    public void publish(UUID subjectId, MetricPoint point) {
        if (subjectId == null || point == null) {
            return;
        }
        forEach(subjectId, watcher -> watcher.point(point));
    }

    /** How many charts are watching. For tests, and for deciding nothing needs publishing. */
    public int watcherCount(UUID subjectId) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(subjectId);
        return list == null ? 0 : list.size();
    }

    /** Whether anybody is watching anything at all. */
    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    @Override
    public void close() {
        keepAlives.shutdownNow();
        watchers.clear();
    }

    private void tick() {
        for (UUID subjectId : watchers.keySet()) {
            forEach(subjectId, Watcher::keepAlive);
        }
    }

    private void forEach(UUID subjectId, Consumer<Watcher> action) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(subjectId);
        if (list == null) {
            return;
        }
        for (Watcher watcher : list) {
            try {
                action.accept(watcher);
            } catch (RuntimeException gone) {
                // The tab closed, or the tunnel dropped. Not an incident: drop this one
                // and carry on delivering to the others.
                log.debug("Dropping a metric watcher of {}: {}", subjectId, gone.toString());
                detach(subjectId, watcher);
            }
        }
    }

    private void detach(UUID subjectId, Watcher watcher) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(subjectId);
        if (list == null) {
            return;
        }
        list.remove(watcher);
        if (list.isEmpty()) {
            watchers.remove(subjectId, list);
        }
    }
}
