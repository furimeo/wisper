package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the fleet screen: the panel's intent and the node's report, side by side.
 *
 * <p>They are separate tables for a reason (AGENTS.md §4.2) and a separate reason to put
 * them together here: the questions an operator actually asks are all comparisons between
 * the two halves. Is this machine doing what we asked? Is it running the version we
 * publish? Is it connected but refusing work? None of those can be answered from either
 * table on its own, and none of them should be re-derived in TypeScript.
 *
 * @param connected      whether a stream is open at all. False is not an alarm - the
 *                       node's containers are still running (design §5.2).
 * @param converged      {@code applied_generation} equals {@code desired_generation}
 * @param needsUpgrade   the node is older than the newest published binary
 * @param lessIsolated   the node has no {@code runsc}, so everything on it runs under
 *                       {@code runc}. Shown, never silently tolerated (design §7.2).
 * @param quotaAdvisory  the volume filesystem has no project quota, so a volume's size
 *                       limit is a number the panel displays and nothing enforces
 */
public record NodeSummary(
        UUID id,
        String name,
        String description,
        NodeLifecycle lifecycle,
        NodeSuspensionReason suspensionReason,
        String suspensionExplanation,
        boolean schedulable,
        List<String> tags,
        String publicAddress,
        NodeConnectionState connectionState,
        boolean connected,
        Instant lastHeartbeatAt,
        String agentVersion,
        Integer protocolVersion,
        boolean needsUpgrade,
        long desiredGeneration,
        long appliedGeneration,
        boolean converged,
        int workloadCount,
        int runningWorkloadCount,
        Long cpuMillicoresCapacity,
        Long cpuMillicoresUsed,
        Long memoryBytesCapacity,
        Long memoryBytesUsed,
        Long diskBytesCapacity,
        Long diskBytesUsed,
        boolean lessIsolated,
        boolean quotaAdvisory,
        Boolean dockerHealthy,
        String reconcileError) {

    /**
     * Builds a row.
     *
     * @param status         the node's own report, or null for a record no machine has
     *                       ever reported against
     * @param newestVersion  the newest published agent version, or null when nothing has
     *                       been published - in which case nothing needs upgrading,
     *                       because there is nothing to upgrade to
     */
    public static NodeSummary of(Node node, NodeStatus status, String newestVersion) {
        NodeConnectionState state = status == null
                ? NodeConnectionState.DISCONNECTED : status.connectionState();
        long applied = status == null ? NodeStatus.NEVER_REPORTED : status.appliedGeneration();
        String agentVersion = status == null ? null : status.agentVersion();
        return new NodeSummary(
                node.id(),
                node.name(),
                node.description(),
                node.lifecycle(),
                node.suspensionReason(),
                node.suspensionReason() == null ? null : node.suspensionReason().explanation(),
                node.schedulable(),
                node.tagList(),
                node.publicAddress(),
                state,
                state != NodeConnectionState.DISCONNECTED,
                status == null ? null : status.lastHeartbeatAt(),
                agentVersion,
                status == null ? null : status.protocolVersion(),
                needsUpgrade(agentVersion, newestVersion),
                node.desiredGeneration(),
                applied,
                applied == node.desiredGeneration(),
                status == null ? 0 : status.workloadCount(),
                status == null ? 0 : status.runningWorkloadCount(),
                status == null ? null : status.cpuMillicoresCapacity(),
                status == null ? null : status.cpuMillicoresUsed(),
                status == null ? null : status.memoryBytesCapacity(),
                status == null ? null : status.memoryBytesUsed(),
                status == null ? null : status.diskBytesCapacity(),
                status == null ? null : status.diskBytesUsed(),
                status != null && Boolean.FALSE.equals(status.runscAvailable()),
                status != null && Boolean.FALSE.equals(status.quotaEnforceable()),
                status == null ? null : status.dockerHealthy(),
                status == null ? null : status.reconcileError());
    }

    /**
     * A node that has never reported is not out of date; it has not arrived. Comparing
     * versions numerically rather than by string keeps {@code 0.10.0} newer than
     * {@code 0.9.0}.
     */
    private static boolean needsUpgrade(String agentVersion, String newestVersion) {
        if (agentVersion == null || agentVersion.isBlank()
                || newestVersion == null || newestVersion.isBlank()) {
            return false;
        }
        try {
            return AgentRelease.compareVersions(agentVersion, newestVersion) < 0;
        } catch (NumberFormatException notAVersion) {
            // A development build calls itself "dev". That is not out of date, it is
            // somebody's laptop, and flagging it every time would train operators to
            // ignore the flag.
            return false;
        }
    }
}
