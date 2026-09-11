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

/**
 * One machine's charts, under {@code /admin/metrics/nodes/{nodeId}/**}.
 *
 * <p>The other half of what nodes push. {@code PushStats} carries a reading for each
 * workload <em>and</em> one for the machine itself, and without this the machine-level
 * samples would be stored, rolled up, retained and never looked at - which is a table
 * growing for nobody.
 *
 * <p>Its own prefix rather than a route under {@code /admin/nodes/{id}}: that screen
 * belongs to the {@code node} package (panel-http.md gives {@code /admin/**} to whoever
 * owns the screen), and two packages mapping paths under one id is how a startup
 * conflict happens. The node detail page links here.
 *
 * <p>{@code ROLE_ADMIN}, enforced by {@code SecurityConfig}'s {@code /admin/**} rule. A
 * node's load is platform information: it says how many other tenants are on the machine
 * and how busy they are.
 *
 * <p>Renders {@code features/stats/AdminNodeMetricsPage.tsx}. The node-level series
 * excludes the workloads the machine is carrying - it is the machine's own totals - so a
 * node is never shown using twice the CPU it has.
 */
@Controller
public class AdminNodeMetricsController {

    private final LoadMetricSeries series;
    private final MetricStream streams;
    private final StatsSettings settings;

    public AdminNodeMetricsController(LoadMetricSeries series, MetricStream streams,
                                      StatsSettings settings) {
        this.series = series;
        this.streams = streams;
        this.settings = settings;
    }

    /** Props: {@code nodeId}, {@code series}, {@code window}, {@code liveWindowSeconds}. */
    @GetMapping("/admin/metrics/nodes/{nodeId}")
    public String page(@PathVariable UUID nodeId,
                       @RequestParam(name = "window", required = false) String window,
                       @RequestParam(name = "from", required = false) String from,
                       @RequestParam(name = "to", required = false) String to,
                       Model model) {
        MetricWindow span = windowOf(window, from, to);
        model.addAttribute("nodeId", nodeId);
        model.addAttribute("series", series.forNode(nodeId, span.from(), span.to()));
        model.addAttribute("window", span);
        model.addAttribute("liveWindowSeconds", settings.liveWindow().toSeconds());
        return "stats/AdminNodeMetrics";
    }

    /** The same data for a different window, as JSON. */
    @GetMapping(path = "/admin/metrics/nodes/{nodeId}/series",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public MetricSeries data(@PathVariable UUID nodeId,
                             @RequestParam(name = "window", required = false) String window,
                             @RequestParam(name = "from", required = false) String from,
                             @RequestParam(name = "to", required = false) String to) {
        MetricWindow span = windowOf(window, from, to);
        return series.forNode(nodeId, span.from(), span.to());
    }

    /** The live tail for one machine. */
    @GetMapping(path = "/admin/metrics/nodes/{nodeId}/live",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter live(@PathVariable UUID nodeId, HttpServletResponse response) {
        Instant now = Instant.now();
        MetricSeries history = series.forNode(nodeId, now.minus(settings.liveWindow()), now);
        return streams.open(nodeId, history, response);
    }

    private MetricWindow windowOf(String window, String from, String to) {
        return MetricWindow.of(window, from, to, Instant.now(), settings.liveWindow());
    }
}
