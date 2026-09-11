package lhqm.furimeo.wisper.node;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;

/**
 * Finds the two shapes a cloned machine takes, and stops both by suspending everything
 * involved.
 *
 * <p>Cloning a running node is easy and, from the panel's side, invisible: the copy boots
 * with the same {@code /etc/wisper/node.json}, dials in with the same credential and
 * reports the same node id. If nothing notices, the panel has two machines converging to
 * one spec - two containers bound to one domain, two writers on one volume's worth of
 * customer data, two cron entries firing every schedule twice. Splitting the workload
 * between them quietly is strictly worse than stopping, so this suspends and tells an
 * operator (design §7.3).
 *
 * <h2>Both records, never one</h2>
 *
 * <p>When two node records collide on a fingerprint, the panel cannot know which machine
 * is the original: a hardware serial says nothing about which copy was made from which.
 * Picking one to keep would be a coin toss whose losing side is a customer's data. So both
 * are suspended, and the operator - who can look at the two addresses and knows which one
 * they built - decides.
 *
 * <h2>Why there are two entry points</h2>
 *
 * <p>A clone made <em>before</em> enrolment shows up as two machines presenting the same
 * hardware fingerprint to {@code Enroll}, which is what the unique index on
 * {@code node.fingerprint} catches. A clone made <em>after</em> enrolment never enrols at
 * all - it already has a credential - and shows up as a second control stream for one node
 * id arriving from a different address while the first is still open, which is what
 * {@code node_status.remote_address} is recorded for.
 */
@Component
public class DetectClonedNode {

    private static final Logger log = LoggerFactory.getLogger(DetectClonedNode.class);

    private final NodeRepository nodes;
    private final NodeStatusRepository statuses;
    private final SuspendNode suspendNode;

    public DetectClonedNode(NodeRepository nodes, NodeStatusRepository statuses,
                            SuspendNode suspendNode) {
        this.nodes = nodes;
        this.statuses = statuses;
        this.suspendNode = suspendNode;
    }

    /**
     * Checks a fingerprint arriving at {@code Enroll} against the fleet.
     *
     * <p>Does nothing when the fingerprint is new, which is the ordinary case. When it
     * belongs to another record, both that record and the one being enrolled against are
     * suspended before the refusal is thrown - so the enrolling machine cannot simply try
     * again against the same record, and the original is taken out of the scheduler until
     * somebody has looked.
     *
     * @param enrollingNodeId the record the bootstrap token belongs to
     * @param fingerprint     hex SHA-256 over machine-id and hardware serials
     * @param remoteAddress   where the enrolment came from, for the audit line
     * @throws EnrolmentRefused with {@link EnrolmentRefused.Reason#FINGERPRINT_TAKEN}
     */
    @Transactional
    public void atEnrolment(UUID enrollingNodeId, String fingerprint, String remoteAddress) {
        Optional<Node> holder = nodes.findByFingerprint(fingerprint);
        if (holder.isEmpty() || holder.get().id().equals(enrollingNodeId)) {
            return;
        }
        Node original = holder.get();
        String detail = "Machine fingerprint " + shorten(fingerprint) + " arrived from "
                + remoteAddress + " for node record " + enrollingNodeId
                + ", but node " + original.name() + " already reports it. "
                + "Both are suspended; neither has had a container touched.";

        AuditActor actor = AuditActor.system("node-clone-detector");
        suspendNode.suspend(actor, original.id(), NodeSuspensionReason.DUPLICATE_FINGERPRINT,
                detail);
        suspendNode.suspend(actor, enrollingNodeId, NodeSuspensionReason.DUPLICATE_FINGERPRINT,
                detail);
        throw new EnrolmentRefused(EnrolmentRefused.Reason.FINGERPRINT_TAKEN, detail);
    }

    /**
     * Checks a control stream opening against the stream the panel thinks is already
     * there.
     *
     * <p>A node reconnecting through a tunnel routinely arrives from a new address, and
     * that on its own is not a clone - the old stream is gone. The signal is a second
     * stream from a <em>different</em> address while the panel still believes the first
     * one is open, which one machine cannot produce.
     *
     * @return true when this looked like a clone and the node was suspended, in which case
     *         the caller must refuse the stream
     */
    @Transactional
    public boolean atHandshake(UUID nodeId, String nodeName, String remoteAddress) {
        Optional<NodeStatus> current = statuses.findRow(nodeId);
        if (current.isEmpty() || !current.get().isConnected()) {
            return false;
        }
        String establishedAddress = current.get().remoteAddress();
        if (establishedAddress == null || establishedAddress.equals(remoteAddress)) {
            // Same address: an old stream the panel has not noticed dying yet. Normal
            // through a tunnel, and the new stream replaces the old one.
            return false;
        }
        String detail = "Node " + nodeName + " opened a second control stream from "
                + remoteAddress + " while one from " + establishedAddress
                + " was still open. Two machines are holding one credential.";
        log.error(detail);
        suspendNode.suspend(AuditActor.node(nodeId, nodeName, remoteAddress), nodeId,
                NodeSuspensionReason.DUPLICATE_FINGERPRINT, detail);
        return true;
    }

    /** Enough of a hash to recognise in a message, without pasting sixty-four characters. */
    private static String shorten(String fingerprint) {
        return fingerprint.length() <= 16 ? fingerprint : fingerprint.substring(0, 16) + "...";
    }
}
