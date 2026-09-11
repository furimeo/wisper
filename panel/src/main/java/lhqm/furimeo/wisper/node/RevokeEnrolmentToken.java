package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Withdraws a bootstrap token before anybody uses it.
 *
 * <p>The button an operator reaches for the moment they realise the token went into a
 * chat message, a ticket or a screen share. Fifteen minutes is short, but it is not zero,
 * and a token is enough to put a machine of somebody's choosing into the fleet.
 *
 * <p>A spent token is not revocable, and refusing rather than pretending is the point: the
 * enrolment already happened, and what the operator actually needs is the node's page,
 * where the address it was enrolled from is recorded.
 */
@Component
public class RevokeEnrolmentToken {

    private final NodeEnrollmentTokenRepository tokens;
    private final NodeRepository nodes;
    private final AuditTrail audit;

    public RevokeEnrolmentToken(NodeEnrollmentTokenRepository tokens, NodeRepository nodes,
                                AuditTrail audit) {
        this.tokens = tokens;
        this.nodes = nodes;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such token
     * @throws RequestRejected   if it was already used, or already revoked
     */
    @Transactional
    public void revoke(AuditActor actor, UUID tokenId) {
        NodeEnrollmentToken token = tokens.findById(tokenId)
                .orElseThrow(() -> NotFoundException.of("enrolment token", tokenId));
        if (token.isSpent()) {
            throw new RequestRejected(null, "That token was already used at " + token.usedAt()
                    + " from " + token.usedFromAddress() + ". Revoking it would change nothing; "
                    + "suspend the node instead.");
        }
        if (token.isRevoked()) {
            throw new RequestRejected(null, "That token was already revoked.");
        }

        tokens.save(token.revoked(Instant.now()));
        String nodeName = nodes.findById(token.nodeId()).map(Node::name)
                .orElse(token.nodeId().toString());
        audit.record(AuditEntry.succeeded(actor, "node.token_revoke",
                AuditTarget.of("node", token.nodeId(), nodeName), null,
                "Bootstrap token revoked before use"));
    }
}
