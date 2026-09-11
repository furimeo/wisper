package lhqm.furimeo.wisper.files;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.StartTerminal;

/**
 * Opens a shell inside a customer's container.
 *
 * <p>The session id is minted here, before the browser connects, so the {@code EventSource},
 * the gRPC stream and the audit entry all carry the same name and any one of them can be
 * found from the others.
 *
 * <h2>The shell is chosen by the panel</h2>
 *
 * <p>{@code terminal.proto}: an empty command means the node guesses, and a node guessing
 * means the same image gives a customer {@code bash} on one machine and {@code sh} on
 * another. {@code /bin/sh} is the one binary a container image can be relied on to have -
 * even a distroless image that has anything at all has this - and it is a fixed argv, never
 * a string that anything from a request is concatenated into (AGENTS.md §5).
 *
 * <p>No {@code user} is set, so the PTY runs as whatever the workload runs as. A terminal
 * that is root inside a container whose application is not lets a customer create files their
 * own application cannot then read, which is a support ticket a week later.
 *
 * <h2>Both timeouts are mandatory</h2>
 *
 * <p>A forgotten tab is a shell running as the customer's application for as long as the tab
 * is open. The idle timeout ends one nobody is typing into; the maximum duration ends one
 * that is busy doing nothing, which is what a wedged {@code top} looks like from here.
 */
@Component
public class OpenTerminal {

    /** The one shell a container image can be relied on to have. Fixed argv, never a string. */
    private static final List<String> SHELL = List.of("/bin/sh");

    private final NodeTerminals terminals;
    private final LiveTerminals live;
    private final AuditTrail audit;
    private final FilesSettings settings;

    public OpenTerminal(NodeTerminals terminals, LiveTerminals live, AuditTrail audit,
                        FilesSettings settings) {
        this.terminals = terminals;
        this.live = live;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * Starts a PTY and waits for the node to attach to it.
     *
     * @param columns the browser's size at open time, so the first prompt is not drawn at
     *                80x24 and immediately redrawn
     * @throws TerminalUnavailable if the node did not attach in time, or refused
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no control stream
     * @throws lhqm.furimeo.wisper.service.ServiceNotPlaced if nothing is running the service
     */
    public TerminalView open(TerminalAccess access, int columns, int rows) {
        UUID nodeId = access.requireNodeId();
        String sessionId = UUID.randomUUID().toString();
        int cols = clamp(columns, 20, 500, 80);
        int lines = clamp(rows, 5, 200, 24);

        StartTerminal request = StartTerminal.newBuilder()
                .setSessionId(sessionId)
                .setWorkloadId(access.workloadId())
                .addAllCommand(SHELL)
                .putAllEnv(Map.of(
                        // Without a TERM anything that draws - top, vim, an npm progress bar -
                        // falls back to dumb output and the customer sees escape codes.
                        "TERM", "xterm-256color",
                        "COLUMNS", String.valueOf(cols),
                        "LINES", String.valueOf(lines)))
                .setInitialCols(cols)
                .setInitialRows(lines)
                .setIdleTimeoutSeconds(settings.terminalIdleTimeout().toSeconds())
                .setMaxDurationSeconds(settings.terminalMaxDuration().toSeconds())
                .build();

        // The place is taken before the node is asked. A shell writes its prompt the instant
        // it attaches, and a sink that does not exist yet is a first screen the customer
        // never sees; buffering it costs nothing and losing it is unexplainable.
        LiveTerminals.Held held = live.reserve(sessionId, access.serviceId(),
                access.membership().accountId());
        TerminalSession session;
        try {
            session = terminals.open(nodeId, request, held::output,
                    settings.terminalAttachTimeout());
        } catch (RuntimeException refused) {
            live.release(sessionId);
            throw refused;
        }
        held.bind(session);

        audit.record(AuditEntry.succeeded(access.actor(), "terminal.open", access.target(),
                access.organizationId(),
                "opened a shell in container " + session.containerId() + " (session " + sessionId
                        + ")"));
        return new TerminalView(sessionId, session.containerId(), session.columns(),
                session.rows());
    }

    /** A size the browser asked for, kept inside what a PTY will accept. */
    private static int clamp(int requested, int least, int most, int fallback) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.max(least, Math.min(most, requested));
    }
}
