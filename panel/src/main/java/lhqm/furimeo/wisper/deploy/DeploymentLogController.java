package lhqm.furimeo.wisper.deploy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The live build log:
 * {@code GET /services/{serviceId}/deployments/{deploymentId}/log}, as Server-Sent Events.
 *
 * <p>Three event names, and a browser needs all three: {@code line} for output,
 * {@code end} when the build is over so the page can stop showing a spinner, and comment
 * frames on a timer so a tunnel does not close a connection that has said nothing while
 * {@code npm ci} thinks for four minutes.
 *
 * <p>Every {@code line} event carries the row's sequence as its SSE id. That is not
 * decoration: the browser echoes the last one back in {@code Last-Event-ID} when it
 * reconnects, and the stream resumes from the table at exactly that point. A customer on a
 * train whose connection drops twice reads one continuous log.
 *
 * <h2>Subscribe first, then replay</h2>
 *
 * <p>The order matters and the obvious order is wrong. Reading the table and then
 * subscribing loses every line written in between - which, during a build, is the part
 * the customer is watching. So the watcher is attached first and buffers what arrives, the
 * table is read second, and the buffer is then flushed with anything the replay already
 * covered dropped by sequence number.
 */
@Controller
public class DeploymentLogController {

    private static final Logger log = LoggerFactory.getLogger(DeploymentLogController.class);

    /**
     * How long one SSE connection lives before the browser is asked to reconnect.
     *
     * <p>Finite on purpose. An immortal stream through a tunnel that has silently gone
     * away holds a servlet async context and a watcher until the process restarts;
     * reconnecting costs one request and resumes exactly where it left off.
     */
    private static final long CONNECTION_MILLIS = 30 * 60 * 1000L;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final DeploymentRepository deployments;
    private final DeploymentLogRepository lines;
    private final DeploymentLogFeed feed;
    private final DeploySettings settings;

    public DeploymentLogController(ResolveCurrentAccount currentAccount,
                                   ResolveMembership memberships,
                                   DeploymentRepository deployments,
                                   DeploymentLogRepository lines, DeploymentLogFeed feed,
                                   DeploySettings settings) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.deployments = deployments;
        this.lines = lines;
        this.feed = feed;
        this.settings = settings;
    }

    /**
     * Opens the stream.
     *
     * @param after       resume cursor, from the detail page's {@code logCursor} prop
     * @param lastEventId the same cursor as the browser's own {@code EventSource} sends it
     *                    on an automatic reconnect. It wins, because it is what the client
     *                    actually last rendered
     */
    @GetMapping(path = "/services/{serviceId}/deployments/{deploymentId}/log",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@PathVariable UUID serviceId, @PathVariable UUID deploymentId,
                             @RequestParam(name = "after", defaultValue = "0") long after,
                             @RequestHeader(name = "Last-Event-ID", required = false)
                             String lastEventId,
                             HttpServletResponse response) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Deployment deployment = deployments.findById(deploymentId)
                .filter(candidate -> candidate.serviceId().equals(serviceId))
                .orElseThrow(() -> NotFoundException.of("deployment", deploymentId));

        // A tunnel or a proxy that caches an event stream serves one customer's build log
        // to the next request for the same URL.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(CONNECTION_MILLIS);
        LineWatcher watcher = new LineWatcher(emitter, cursorFrom(lastEventId, after));
        DeploymentLogFeed.Subscription subscription = feed.watch(deploymentId, watcher);

        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(failure -> subscription.close());

        watcher.replay(lines.findAfter(deploymentId, watcher.cursor(),
                settings.logReplayLimit()));
        if (deployment.status().isTerminal()) {
            // Nothing more will ever be written. Saying so lets the page take the spinner
            // down without waiting for a timeout it would read as a failure.
            watcher.finished();
            subscription.close();
        }
        // The membership is resolved above and not used again: the check is the point.
        log.debug("{} is watching deployment {}", membership.accountId(), deploymentId);
        return emitter;
    }

    /** {@code Last-Event-ID} if the browser sent a usable one, otherwise the query cursor. */
    private static long cursorFrom(String lastEventId, long after) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Math.max(after, 0);
        }
        try {
            return Math.max(Long.parseLong(lastEventId.strip()), 0);
        } catch (NumberFormatException notACursor) {
            return Math.max(after, 0);
        }
    }

    /**
     * One browser's view of one build.
     *
     * <p>Buffers until the replay has run, then writes straight through. Everything is
     * synchronized on the watcher because the feed's publishing thread, the keep-alive
     * thread and the request thread doing the replay all reach it, and an
     * {@code SseEmitter} is not safe for concurrent writes.
     */
    private static final class LineWatcher implements DeploymentLogFeed.Watcher {

        private final SseEmitter emitter;
        private final List<DeploymentLog> pending = new ArrayList<>();
        private long cursor;
        private boolean replayed;
        private boolean ended;

        private LineWatcher(SseEmitter emitter, long cursor) {
            this.emitter = emitter;
            this.cursor = cursor;
        }

        private long cursor() {
            return cursor;
        }

        private synchronized void replay(List<DeploymentLog> stored) {
            stored.forEach(this::write);
            replayed = true;
            List<DeploymentLog> buffered = List.copyOf(pending);
            pending.clear();
            // Anything the replay already covered is dropped by sequence inside write.
            buffered.forEach(this::write);
        }

        @Override
        public synchronized void line(DeploymentLog line) {
            if (!replayed) {
                pending.add(line);
                return;
            }
            write(line);
        }

        @Override
        public synchronized void keepAlive() {
            try {
                emitter.send(SseEmitter.event().comment("alive"));
            } catch (IOException | IllegalStateException gone) {
                throw closed(gone);
            }
        }

        @Override
        public synchronized void finished() {
            if (ended) {
                return;
            }
            ended = true;
            try {
                emitter.send(SseEmitter.event().name("end").data(String.valueOf(cursor)));
                emitter.complete();
            } catch (IOException | IllegalStateException gone) {
                // The browser left before the build did. Completing an emitter whose
                // connection is already dead is not something to report.
                emitter.complete();
            }
        }

        private void write(DeploymentLog line) {
            if (line.sequence() <= cursor) {
                // Already replayed, or redelivered. The sequence is what makes that
                // decidable rather than a guess.
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(line.sequence()))
                        .name("line")
                        .data(line, MediaType.APPLICATION_JSON));
                cursor = line.sequence();
            } catch (IOException | IllegalStateException gone) {
                throw closed(gone);
            }
        }

        /**
         * Turns a dead connection into the unchecked throw the feed drops a watcher on.
         * A customer closing a tab is the normal end of a log stream, not an incident.
         */
        private static RuntimeException closed(Exception cause) {
            return new IllegalStateException("The log stream's browser has gone", cause);
        }
    }
}
