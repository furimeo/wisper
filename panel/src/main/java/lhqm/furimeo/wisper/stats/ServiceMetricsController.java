package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * One service's charts, under {@code /services/{id}/metrics/**}.
 *
 * <p>Three endpoints for one feature, because a chart needs three different things: a page
 * to live on, a way to change its window without reloading, and a live tail.
 *
 * <ul>
 * <li>{@code GET /services/{id}/metrics} renders
 *     {@code features/stats/ServiceMetricsPage.tsx} with the first window already drawn.
 *     A page that arrives empty and then fetches is a page that flashes.</li>
 * <li>{@code GET /services/{id}/metrics/series} answers JSON for a different window. Not
 *     an Inertia visit: changing a chart's range must not push a history entry or re-render
 *     the page around it.</li>
 * <li>{@code GET /services/{id}/metrics/live} is the Server-Sent Events tail.</li>
 * </ul>
 *
 * <p>Every one of them resolves membership first. {@code ResolveMembership.forService}
 * throws {@code NotFoundException} for a service the caller is not a member of, so "not
 * yours" and "no such thing" produce the same answer and the id space cannot be enumerated
 * (panel-http.md).
 *
 * <p>Metrics are readable by any member, including a {@code VIEWER}: looking at a graph
 * changes nothing, and a read-only role that cannot see whether the thing it is watching is
 * healthy is not a useful role.
 */
@Controller
public class ServiceMetricsController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final LocateService services;
    private final LoadMetricSeries series;
    private final MetricStream streams;
    private final StatsSettings settings;

    public ServiceMetricsController(ResolveCurrentAccount currentAccount,
                                    ResolveMembership memberships, LocateService services,
                                    LoadMetricSeries series, MetricStream streams,
                                    StatsSettings settings) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.series = series;
        this.streams = streams;
        this.settings = settings;
    }

    /** Props: {@code service}, {@code series}, {@code window}, {@code liveWindowSeconds}. */
    @GetMapping("/services/{serviceId}/metrics")
    public String page(@PathVariable UUID serviceId,
                       @RequestParam(name = "window", required = false) String window,
                       @RequestParam(name = "from", required = false) String from,
                       @RequestParam(name = "to", required = false) String to,
                       Model model) {
        ServiceLocation service = authorised(serviceId);
        MetricWindow span = windowOf(window, from, to);

        model.addAttribute("service", service);
        model.addAttribute("series", series.forService(serviceId, span.from(), span.to()));
        model.addAttribute("window", span);
        model.addAttribute("liveWindowSeconds", settings.liveWindow().toSeconds());
        return "stats/ServiceMetrics";
    }

    /** The same data for a different window, as JSON, for a chart that is being zoomed. */
    @GetMapping(path = "/services/{serviceId}/metrics/series",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public MetricSeries data(@PathVariable UUID serviceId,
                             @RequestParam(name = "window", required = false) String window,
                             @RequestParam(name = "from", required = false) String from,
                             @RequestParam(name = "to", required = false) String to) {
        authorised(serviceId);
        MetricWindow span = windowOf(window, from, to);
        return series.forService(serviceId, span.from(), span.to());
    }

    /**
     * The live tail: the recent window as one {@code series} event, then a {@code point}
     * event per reading as the node pushes it.
     */
    @GetMapping(path = "/services/{serviceId}/metrics/live",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter live(@PathVariable UUID serviceId, HttpServletResponse response) {
        authorised(serviceId);
        Instant now = Instant.now();
        MetricSeries history = series.forService(serviceId, now.minus(settings.liveWindow()), now);
        return streams.open(serviceId, history, response);
    }

    private MetricWindow windowOf(String window, String from, String to) {
        return MetricWindow.of(window, from, to, Instant.now(), settings.liveWindow());
    }

    /**
     * The service, having checked the caller may see it.
     *
     * @throws lhqm.furimeo.wisper.web.NotFoundException if there is no such service, or it
     *         belongs to an organization the caller is not a member of
     */
    private ServiceLocation authorised(UUID serviceId) {
        AccountRef account = currentAccount.require();
        memberships.forService(account.id(), serviceId);
        return services.byId(serviceId);
    }
}
