package lhqm.furimeo.wisper.node;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code wisper.node}.
 *
 * <p>Declared as a record in the package that reads it, which is how a setting gets added
 * without touching {@code application.yml} or anyone else's file
 * (panel-configuration.md). Every component carries {@link DefaultValue}: record binding
 * has no constructor fallback, so a key nobody set binds to {@code null} or {@code 0} and
 * the failure surfaces much later, inside a scheduled job, as a
 * {@code NullPointerException}.
 *
 * <p>The headroom percentages live here rather than in {@code placement} because they are
 * a property of a node, they were already written into {@code application.yml} under this
 * prefix, and a second prefix for three integers would mean two files to change when a
 * fleet turns out to need more slack.
 *
 * @param heartbeatTimeout     no heartbeat inside this window and the node is shown as
 *                             lost. Its containers keep running: the panel being blind is
 *                             not a reason to touch a customer's workload.
 * @param heartbeatInterval    what the node is told to send at, in {@code PanelHello}.
 *                             Comfortably inside {@code heartbeatTimeout} so one lost
 *                             frame does not mark a healthy node down.
 * @param enrollmentTokenTtl   how long a bootstrap token is worth anything (design §7.1)
 * @param reconcileInterval    the cadence the node reconciles at, event or no event
 * @param cpuHeadroomPercent   refuse new placements past this much CPU committed
 * @param memoryHeadroomPercent same for memory
 * @param diskHeadroomPercent  same for disk
 * @param dialEndpoint         {@code host:port} the installer tells a node to dial, and
 *                             the value recorded in {@code node.dialled_endpoint}. Empty
 *                             means "the host this request arrived on, port 9090", which
 *                             is right for a single-host development machine and wrong
 *                             for anything behind a tunnel that terminates gRPC
 *                             elsewhere.
 * @param releaseDirectory     where published {@code sasayaki} binaries sit. Files are
 *                             named {@code sasayaki-<version>-linux-<arch>}; the
 *                             directory not existing yet is not an error, it means
 *                             nothing has been published.
 * @param upgradeDownloadBase  the base URL a node fetches an upgrade binary from. Empty
 *                             means "the panel's own {@code /dist}", derived from the
 *                             request that asked for the install script.
 */
@ConfigurationProperties("wisper.node")
public record NodeSettings(
        @DefaultValue("60s") Duration heartbeatTimeout,
        @DefaultValue("20s") Duration heartbeatInterval,
        @DefaultValue("15m") Duration enrollmentTokenTtl,
        @DefaultValue("15s") Duration reconcileInterval,
        @DefaultValue("15") int cpuHeadroomPercent,
        @DefaultValue("15") int memoryHeadroomPercent,
        @DefaultValue("20") int diskHeadroomPercent,
        @DefaultValue("") String dialEndpoint,
        @DefaultValue("./var/dist") Path releaseDirectory,
        @DefaultValue("") String upgradeDownloadBase) {

    /**
     * The gRPC port from panel-configuration.md, used only to build a sensible
     * {@code host:port} when {@link #dialEndpoint} was left empty. It is a literal rather
     * than a read of {@code wisper.grpc.port} because {@code node} must not depend on
     * {@code grpc} (panel-ports.md §6), and one documented literal is a smaller price
     * than that arrow.
     */
    public static final int DEFAULT_GRPC_PORT = 9090;

    public NodeSettings {
        requirePercent("cpu-headroom-percent", cpuHeadroomPercent);
        requirePercent("memory-headroom-percent", memoryHeadroomPercent);
        requirePercent("disk-headroom-percent", diskHeadroomPercent);
        if (heartbeatInterval.compareTo(heartbeatTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "wisper.node.heartbeat-interval (" + heartbeatInterval + ") must be shorter "
                            + "than wisper.node.heartbeat-timeout (" + heartbeatTimeout
                            + "), or every healthy node is marked lost between two beats");
        }
    }

    /** What a node should be told to dial, given the host the installer reached us on. */
    public String dialEndpointFor(String requestHost) {
        if (!dialEndpoint.isBlank()) {
            return dialEndpoint;
        }
        return requestHost + ":" + DEFAULT_GRPC_PORT;
    }

    /** Where a node fetches an upgrade, given the panel base URL the request came in on. */
    public String downloadBaseFor(String panelBaseUrl) {
        String base = upgradeDownloadBase.isBlank() ? panelBaseUrl + "/dist" : upgradeDownloadBase;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static void requirePercent(String key, int value) {
        if (value < 0 || value > 90) {
            throw new IllegalArgumentException("wisper.node." + key + " is a percentage of a node "
                    + "to keep free and must be between 0 and 90, not " + value);
        }
    }
}
