package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Stops a token working, immediately and for good.
 *
 * <p>The row stays. Deleting it would take the audit trail's foreign key with it - the
 * {@code api_token_id} on {@code audit_log} is {@code SET NULL}, so a deleted token turns
 * every entry it produced into an anonymous one - and it would remove the only record
 * that the leaked prefix somebody found in a log belonged to this account at all.
 *
 * <p>There is no un-revoke. A token whose value may have been seen is not made safe by
 * changing its state back, and offering the button would suggest otherwise.
 */
@Component
public class RevokeApiToken {

    private final ApiTokenRepository tokens;
    private final AuditTrail auditTrail;

    public RevokeApiToken(ApiTokenRepository tokens, AuditTrail auditTrail) {
        this.tokens = tokens;
        this.auditTrail = auditTrail;
    }

    /**
     * @param ownerAccountId the account the caller may act for; a token belonging to
     *                       anybody else answers {@link NotFoundException}, the same as
     *                       one that does not exist
     * @param reason         short, free text - "revoked by owner", "found in a public
     *                       repository". Shown in the token list
     */
    @Transactional
    public void run(UUID tokenId, UUID ownerAccountId, String reason, AuditActor actor) {
        ApiToken token = tokens.findById(tokenId)
                .filter(row -> row.accountId().equals(ownerAccountId))
                .orElseThrow(() -> NotFoundException.of("api token", tokenId));

        if (token.revokedAt() != null) {
            return;
        }
        String why = reason == null || reason.isBlank() ? "Revoked by its owner." : reason.strip();
        tokens.save(token.revoked(Instant.now(), why));

        auditTrail.record(AuditEntry.succeeded(actor, "api_token.revoke",
                AuditTarget.of("api_token", token.id(), token.name()), token.organizationId(),
                why));
    }
}
