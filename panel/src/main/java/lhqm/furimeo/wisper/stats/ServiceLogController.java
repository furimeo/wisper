package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.google.protobuf.Timestamp;

import jakarta.servlet.http.HttpServletResponse;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.proto.v1.LogRequest;
import lhqm.furimeo.wisper.proto.v1.LogSource;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * A customer watching their application's output, under {@code /services/{id}/logs/**}.
 *
 * <p>Two endpoints: the page, and the Server-Sent Events tail it opens. There is no stored
 * log to page through - container output lives on the node, in Docker's rotated json files,
 * and copying it into PostgreSQL would make the panel the disk-space bottleneck for every
 * chatty application on the platform. {@code tail_lines} is how the customer gets the last
 * screen immediately.
 *
 * <p>Two sources are reachable from here. {@code CONTAINER} is the workload's own output
 * and is the default. {@code CRON} is one run of a scheduled task, whose id the scheduled
 * tasks page links with. {@code BUILD} deliberately is not: {@code deploy} owns build logs
 * because it also has to persist them into {@code deployment_log}, which is what makes a
 * build log survive the build (panel-ports.md §2.9).
 *
 * <p>Renders {@code features/stats/ServiceLogsPage.tsx}.
 */
@Controller
public class ServiceLogController {

    /**
     * Screenfuls, not scrollback. Enough to see why something just died; more than this is
     * a node reading megabytes off disk for a page the customer will scroll past.
     */
    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final LocateService services;
    private final ServiceLogStream streams;

    public ServiceLogController(ResolveCurrentAccount currentAccount,
                                ResolveMembership memberships, LocateService services,
                                ServiceLogStream streams) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.streams = streams;
    }

    /** Props: {@code service}, {@code source}, {@code subjectId}, {@code tailLines}. */
    @GetMapping("/services/{serviceId}/logs")
    public String page(@PathVariable UUID serviceId,
                       @RequestParam(name = "source", required = false) String source,
                       @RequestParam(name = "subjectId", required = false) String subjectId,
                       @RequestParam(name = "tail", defaultValue = "0") int tail,
                       Model model) {
        ServiceLocation service = authorised(serviceId);
        LogSource chosen = sourceOf(source);

        model.addAttribute("service", service);
        model.addAttribute("source", chosen.name());
        model.addAttribute("subjectId", subjectOf(chosen, service, subjectId));
        model.addAttribute("tailLines", tailLines(tail));
        // A service nothing is running cannot have logs, and saying so is a better page
        // than a stream that never produces a line.
        model.addAttribute("placed", service.isPlaced());
        return "stats/ServiceLogs";
    }

    /**
     * The tail.
     *
     * <p>{@code since} is what a reconnecting browser sends so the customer does not get the
     * last screen twice; {@code Last-Event-ID} is not used here because a chunk is not a
     * line and has no sequence to resume from - the timestamp is the only cursor that
     * exists.
     *
     * @throws lhqm.furimeo.wisper.service.ServiceNotPlaced if nothing is running the service
     */
    @GetMapping(path = "/services/{serviceId}/logs/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@PathVariable UUID serviceId,
                             @RequestParam(name = "source", required = false) String source,
                             @RequestParam(name = "subjectId", required = false) String subjectId,
                             @RequestParam(name = "tail", defaultValue = "0") int tail,
                             @RequestParam(name = "since", required = false) String since,
                             HttpServletResponse response) {
        authorised(serviceId);
        ServiceLocation service = services.onANode(serviceId);
        LogSource chosen = sourceOf(source);

        LogRequest.Builder request = LogRequest.newBuilder()
                // Minted here so the browser's connection, this subscription and the node's
                // chunks all carry one name.
                .setStreamId(UUID.randomUUID().toString())
                .setSource(chosen)
                .setSubjectId(subjectOf(chosen, service, subjectId))
                .setTailLines(tailLines(tail))
                .setFollow(true);
        Instant from = instant(since);
        if (from != null) {
            request.setSince(Timestamp.newBuilder()
                    .setSeconds(from.getEpochSecond())
                    .setNanos(from.getNano()));
        }
        return streams.open(service.nodeId(), request.build(), response);
    }

    /**
     * Which feed. Anything unrecognised is the container's own output, because that is what
     * a customer opening a log page means.
     */
    private static LogSource sourceOf(String source) {
        if (source == null) {
            return LogSource.LOG_SOURCE_CONTAINER;
        }
        return "cron".equalsIgnoreCase(source.strip())
                || LogSource.LOG_SOURCE_CRON.name().equalsIgnoreCase(source.strip())
                ? LogSource.LOG_SOURCE_CRON
                : LogSource.LOG_SOURCE_CONTAINER;
    }

    /**
     * What the feed is about.
     *
     * <p>For a container it is the workload, which the node knows by the service's own id -
     * and it is taken from the path rather than the query string, so a member of one
     * organization cannot read another's output by editing a parameter. For a cron run it is
     * the run id the scheduled tasks page linked with, which the node checks belongs to a
     * workload in its spec.
     */
    private static String subjectOf(LogSource source, ServiceLocation service, String requested) {
        if (source == LogSource.LOG_SOURCE_CRON && requested != null && !requested.isBlank()) {
            return requested.strip().toLowerCase(Locale.ROOT);
        }
        return service.serviceId().toString();
    }

    private static int tailLines(int requested) {
        if (requested <= 0) {
            return DEFAULT_TAIL_LINES;
        }
        return Math.min(requested, MAX_TAIL_LINES);
    }

    private static Instant instant(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(candidate.strip());
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }

    private ServiceLocation authorised(UUID serviceId) {
        AccountRef account = currentAccount.require();
        memberships.forService(account.id(), serviceId);
        return services.byId(serviceId);
    }
}
