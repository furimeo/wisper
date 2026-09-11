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
 * Mints a bootstrap token for one node record.
 *
 * <p>Four properties make the installer safe rather than merely convenient, and three of
 * them are decided here (design §7.1):
 *
 * <ul>
 * <li><strong>Single use.</strong> {@code used_at} is written in the same transaction as
 *     the credential, so a replay finds it spent.</li>
 * <li><strong>Fifteen minutes.</strong> Short enough that a token left in a terminal
 *     scrollback is worthless by the time anybody finds it, and issuing another is one
 *     click.</li>
 * <li><strong>Bound to one record.</strong> A leaked token can enrol the machine an
 *     operator already decided to have, and nothing else.</li>
 * </ul>
 *
 * <p>The fourth - never in {@code argv}, because {@code ps} is world-readable - belongs to
 * the installer, which accepts only {@code --token-file} and stdin.
 *
 * <p>Issuing a second token revokes any live one. Two valid tokens for one record would
 * mean two machines could win the race to fill it, and the operator who reissued because
 * the first attempt failed would have no way of knowing which one enrolled.
 */
@Component
public class IssueEnrolmentToken {

    private final NodeRepository nodes;
    private final NodeEnrollmentTokenRepository tokens;
    private final AuditTrail audit;
    private final NodeSettings settings;

    public IssueEnrolmentToken(NodeRepository nodes, NodeEnrollmentTokenRepository tokens,
                               AuditTrail audit, NodeSettings settings) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * @param issuedBy the operator's account id, kept so the trail survives them being
     *                 deleted through {@code SET NULL} plus the label on the audit row
     * @return the token text and its row. <strong>The text exists only in this return
     *         value.</strong> Show it once and forget it; nothing can produce it again.
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   if a machine has already enrolled against this record
     */
    @Transactional
    public IssuedToken issue(AuditActor actor, UUID nodeId, UUID issuedBy) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (node.isEnrolled()) {
            throw new RequestRejected(null, node.name() + " has already been enrolled. A "
                    + "bootstrap token fills an empty record; to replace the machine, delete "
                    + "this node and create another.");
        }

        Instant now = Instant.now();
        for (NodeEnrollmentToken live : tokens.findLive(nodeId, now)) {
            tokens.save(live.revoked(now));
        }

        NodeSecret secret = NodeSecret.bootstrapToken();
        NodeEnrollmentToken row = tokens.save(NodeEnrollmentToken.issued(UUID.randomUUID(),
                nodeId, secret.hash(), issuedBy, now.plus(settings.enrollmentTokenTtl())));

        audit.record(AuditEntry.succeeded(actor, "node.token_issue",
                AuditTarget.of("node", nodeId, node.name()), null,
                "Bootstrap token valid until " + row.expiresAt()));
        return new IssuedToken(row, secret.text());
    }

    /**
     * A token row and the one and only copy of its text.
     *
     * @param row  the persisted row, for the expiry the screen shows next to the command
     * @param text what the operator pastes into {@code token.txt}. Never stored, never
     *             logged, never recoverable.
     */
    public record IssuedToken(NodeEnrollmentToken row, String text) {
    }
}
