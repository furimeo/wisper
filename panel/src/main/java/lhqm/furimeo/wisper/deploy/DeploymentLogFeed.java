package lhqm.furimeo.wisper.deploy;

import java.util.List;
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
 * Hands each stored log line to the browsers currently watching that deployment.
 *
 * <p>The line is written to {@code deployment_log} first and published here second, so
 * what a watcher receives is exactly what a reload would fetch, with the same sequence
 * number. The alternative - publishing the chunk and storing it separately - gives two
 * renderings of one build that disagree the first time an insert is refused.
 *
 * <p>In memory and per panel process, deliberately. A customer watching a build is
 * connected to one panel, and the panel is one process by design (design §5.6). A watcher
 * that misses a line because it connected a millisecond too late is not relying on this:
 * {@link DeploymentLogController} subscribes first, replays from the table second, and
 * drops anything the replay already covered.
 *
 * <h2>Keep-alive</h2>
 *
 * <p>One scheduled thread for the whole feature ticks every
 * {@code wisper.deploy.log-keep-alive} and asks every watcher to send a comment. A tunnel
 * closes a connection that has said nothing for a while, and a build that spends four
 * minutes in {@code npm ci} says nothing - so without this the customer's log goes dead
 * exactly when the build is doing the slow part.
 */
@Component
public class DeploymentLogFeed implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeploymentLogFeed.class);

    /**
     * What a watcher can be asked to do. Implemented by the SSE controller; every method
     * may throw, and a throw means the browser has gone and the watcher is dropped.
     */
    public interface Watcher {

        /** One line, in sequence order for as long as this watcher has been attached. */
        void line(DeploymentLog line);

        /** Send something down an idle connection so the tunnel does not close it. */
        void keepAlive();

        /** There will be no more lines: the build ended. */
        void finished();
    }

    /** Detaches a watcher. Idempotent, and safe to call from an SSE callback. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<UUID, CopyOnWriteArrayList<Watcher>> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlives;

    public DeploymentLogFeed(DeploySettings settings) {
        this.keepAlives = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deployment-log-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1L, settings.logKeepAlive().toSeconds());
        this.keepAlives.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.SECONDS);
    }

    /** Attaches a watcher to one deployment's output. */
    public Subscription watch(UUID deploymentId, Watcher watcher) {
        CopyOnWriteArrayList<Watcher> list =
                watchers.computeIfAbsent(deploymentId, id -> new CopyOnWriteArrayList<>());
        list.add(watcher);
        return () -> detach(deploymentId, watcher);
    }

    /** Sends one stored line to everyone watching that deployment. */
    public void publish(DeploymentLog line) {
        if (line == null) {
            return;
        }
        forEach(line.deploymentId(), watcher -> watcher.line(line));
    }

    /**
     * Tells everyone watching that the build is over, then detaches them.
     *
     * <p>The watchers close their own connections; this only stops holding on to them.
     * A browser that stays open sees the stream complete rather than hang.
     */
    public void finish(UUID deploymentId) {
        List<Watcher> ending = watchers.remove(deploymentId);
        if (ending == null) {
            return;
        }
        for (Watcher watcher : ending) {
            try {
                watcher.finished();
            } catch (RuntimeException gone) {
                log.debug("Watcher of deployment {} was already gone at finish", deploymentId);
            }
        }
    }

    /** How many browsers are watching. For the deployment page and for tests. */
    public int watcherCount(UUID deploymentId) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(deploymentId);
        return list == null ? 0 : list.size();
    }

    @Override
    public void close() {
        keepAlives.shutdownNow();
        watchers.keySet().forEach(this::finish);
    }

    private void tick() {
        for (UUID deploymentId : watchers.keySet()) {
            forEach(deploymentId, Watcher::keepAlive);
        }
    }

    private void forEach(UUID deploymentId, Consumer<Watcher> action) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(deploymentId);
        if (list == null) {
            return;
        }
        for (Watcher watcher : list) {
            try {
                action.accept(watcher);
            } catch (RuntimeException gone) {
                // The browser closed the tab, or the tunnel dropped. Not an incident:
                // drop the watcher and carry on delivering to the others.
                detach(deploymentId, watcher);
            }
        }
    }

    private void detach(UUID deploymentId, Watcher watcher) {
        CopyOnWriteArrayList<Watcher> list = watchers.get(deploymentId);
        if (list == null) {
            return;
        }
        list.remove(watcher);
        if (list.isEmpty()) {
            watchers.remove(deploymentId, list);
        }
    }
}
