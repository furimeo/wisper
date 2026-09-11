package lhqm.furimeo.wisper.node;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the credential in a call's metadata into the node that presented it.
 *
 * <p>Called by {@code grpc} on every RPC except {@code Enroll}, which is the one call that
 * has no credential yet (panel-ports.md §3). It is therefore the single busiest read in
 * the panel, and it is one indexed lookup by design: the credential is hashed with a plain
 * SHA-256 precisely so this can be an index probe rather than a scan.
 *
 * <p>It does not touch {@code node_status}. Recording that a node is connected belongs to
 * the handshake, which happens once per stream; doing it here would write a row on every
 * heartbeat, every status batch and every log chunk.
 *
 * @see NodeCredentialRejected for why all four refusals look identical from the outside
 */
@Component
public class AuthenticateNode {

    private static final Logger log = LoggerFactory.getLogger(AuthenticateNode.class);

    private final NodeRepository nodes;

    public AuthenticateNode(NodeRepository nodes) {
        this.nodes = nodes;
    }

    /**
     * Resolves a credential, or refuses.
     *
     * @param credential    the {@code wisper-node-token} metadata value
     * @param remoteAddress where the call came from, for the log line a refusal writes.
     *                      A refused call is not audited: an unauthenticated caller can
     *                      produce them faster than the table can absorb them, and the
     *                      audit trail is for actions somebody was entitled to attempt.
     * @throws NodeCredentialRejected always, when the answer is no
     */
    @Transactional(readOnly = true)
    public AuthenticatedNode byCredential(String credential, String remoteAddress) {
        if (credential == null || credential.isBlank()) {
            throw refuse(NodeCredentialRejected.Reason.MISSING, remoteAddress);
        }
        if (!NodeSecret.looksLikeCredential(credential)) {
            throw refuse(NodeCredentialRejected.Reason.MALFORMED, remoteAddress);
        }
        Optional<Node> found = nodes.findByCredentialHash(NodeSecret.hashOf(credential));
        Node node = found.orElseThrow(
                () -> refuse(NodeCredentialRejected.Reason.UNKNOWN, remoteAddress));
        if (!node.lifecycle().acceptsControl()) {
            log.warn("Refusing {} from {}: node is {}", node.name(), remoteAddress,
                    node.lifecycle());
            throw new NodeCredentialRejected(
                    NodeCredentialRejected.Reason.NOT_ACCEPTING_CONTROL);
        }
        return new AuthenticatedNode(node.id(), node.name(), node.lifecycle().name(),
                node.desiredGeneration());
    }

    private static NodeCredentialRejected refuse(NodeCredentialRejected.Reason reason,
                                                 String remoteAddress) {
        // At debug: a node reconnecting through a tunnel that is rewriting metadata will
        // produce these in bursts, and a warning per frame buries everything else.
        log.debug("Refusing a node call from {}: {}", remoteAddress, reason.detail());
        return new NodeCredentialRejected(reason);
    }
}
