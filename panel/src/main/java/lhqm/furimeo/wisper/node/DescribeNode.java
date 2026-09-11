package lhqm.furimeo.wisper.node;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Assembles one node's page.
 *
 * <p>Everything the screen needs, resolved server-side, because every field on it is a
 * comparison between two tables or between the fleet and what has been published - and a
 * comparison re-implemented in TypeScript is a second definition of "up to date" that
 * drifts from this one.
 *
 * <p>The install command is here rather than in the controller for the same reason: the
 * exact three lines an operator pastes as root are part of the security story (design
 * §7.1), and there should be one place they are written.
 */
@Component
public class DescribeNode {

    private final NodeRepository nodes;
    private final NodeStatusRepository statuses;
    private final NodeEnrollmentTokenRepository tokens;
    private final ReadAgentReleases releases;
    private final ReadStoredDoctorReport doctorReports;
    private final NodeSettings settings;

    public DescribeNode(NodeRepository nodes, NodeStatusRepository statuses,
                        NodeEnrollmentTokenRepository tokens, ReadAgentReleases releases,
                        ReadStoredDoctorReport doctorReports, NodeSettings settings) {
        this.nodes = nodes;
        this.statuses = statuses;
        this.tokens = tokens;
        this.releases = releases;
        this.doctorReports = doctorReports;
        this.settings = settings;
    }

    /**
     * @param panelBaseUrl the panel as the operator's browser reached it, which is the
     *                     only reliable source: behind a tunnel the panel cannot work out
     *                     its own public URL
     * @throws NotFoundException if there is no such node
     */
    @Transactional(readOnly = true)
    public NodeDetail of(UUID nodeId, String panelBaseUrl) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        NodeStatus status = statuses.findRow(nodeId).orElse(null);
        String newestVersion = releases.newestVersion().orElse(null);
        NodeSummary summary = NodeSummary.of(node, status, newestVersion);

        Instant now = Instant.now();
        List<EnrolmentTokenView> history = tokens.findHistory(nodeId).stream()
                .map(token -> EnrolmentTokenView.of(token, now))
                .toList();
        EnrolmentTokenView live = history.stream()
                .filter(token -> token.state() == EnrolmentTokenView.State.LIVE)
                .findFirst()
                .orElse(null);

        String dialEndpoint = node.dialledEndpoint() == null || node.dialledEndpoint().isBlank()
                ? settings.dialEndpointFor(hostOf(panelBaseUrl))
                : node.dialledEndpoint();
        InstallScript script = InstallScript.render(panelBaseUrl, dialEndpoint, releases.all());

        return new NodeDetail(
                summary,
                status == null ? null : doctorReports.of(status.doctorReport()).orElse(null),
                history,
                live,
                live == null ? null : installCommand(panelBaseUrl, script.sha256()),
                script.sha256(),
                dialEndpoint,
                summary.needsUpgrade() ? newestVersion : null,
                status == null ? null : status.lastConnectedAt(),
                status == null ? null : status.lastDisconnectedAt(),
                status == null ? null : status.clockSkewMillis(),
                status == null ? null : status.volumeFilesystem(),
                status == null ? null : status.kernelVersion(),
                status == null ? null : status.osDescription(),
                status == null ? null : status.dockerVersion());
    }

    /**
     * The three lines from design §7.1, in order and with nothing left implicit.
     *
     * <p>The token is not in them. It is shown exactly once, in the message that appears
     * when it is issued, and it goes into a file rather than onto a command line because
     * {@code ps} is readable by every user on the machine.
     */
    private static String installCommand(String panelBaseUrl, String scriptSha256) {
        return "curl -fsSL " + panelBaseUrl + "/install.sh -o install.sh\n"
                + "echo \"" + scriptSha256 + "  install.sh\" | sha256sum -c -\n"
                + "sudo sh ./install.sh --token-file token.txt";
    }

    /** The host out of a base URL, for building a dial endpoint when none is configured. */
    private static String hostOf(String panelBaseUrl) {
        try {
            String host = URI.create(panelBaseUrl).getHost();
            return host == null || host.isBlank() ? "localhost" : host;
        } catch (IllegalArgumentException notAUrl) {
            return "localhost";
        }
    }
}
