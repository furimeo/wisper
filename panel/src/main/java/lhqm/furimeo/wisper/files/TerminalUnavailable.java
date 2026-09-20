package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A terminal could not be opened.
 *
 * <p>Separate from {@link lhqm.furimeo.wisper.node.NodeOffline} because the two need
 * different words in front of a customer. "That node is unreachable" is about the
 * platform; "the container is not running, start it first" is about their own service,
 * and telling them the wrong one sends them to the wrong place.
 *
 * <p>Reached when the node accepted the command and did not attach in time, when the
 * workload has no running container to attach to, or when the node refused the session
 * outright.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class TerminalUnavailable extends RuntimeException {

    private final UUID nodeId;
    private final String workloadId;

    public TerminalUnavailable(UUID nodeId, String workloadId, String reason) {
        super("No terminal for workload " + workloadId + " on node " + nodeId + ": " + reason);
        this.nodeId = nodeId;
        this.workloadId = workloadId;
    }

    public UUID nodeId() {
        return nodeId;
    }

    public String workloadId() {
        return workloadId;
    }
}
