package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Turns the value of an {@code Authorization: Bearer} header into a principal, or into
 * nothing.
 *
 * <p>Four things have to hold, and all four are checked here rather than being spread
 * between this and the filter: the value is shaped like one of ours, its hash matches a
 * row, that row is neither revoked nor expired, and the account behind it is still
 * active. The last one is the one that is easy to leave out - a suspended person's tokens
 * have to stop working, or suspension is only a lock on the sign-in form.
 *
 * <h2>Why this is not transactional</h2>
 *
 * <p>Like {@link AuthenticateUser}: the refusal paths write an audit entry and then
 * return empty, and a rollback would erase the record of the refusal, which is the entry
 * worth having. The successful path does one narrow UPDATE of {@code last_used_at} that
 * has nothing to be atomic with.
 */
@Component
public class AuthenticateApiToken {

    private final ApiTokenRepository tokens;
    private final AccountRepository accounts;
    private final AuditTrail auditTrail;

    public AuthenticateApiToken(ApiTokenRepository tokens, AccountRepository accounts,
                                AuditTrail auditTrail) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.auditTrail = auditTrail;
    }

    /**
     * @param presented     the raw header value, with the {@code Bearer } prefix removed
     * @param remoteAddress the caller's address, recorded on the row and in any refusal
     * @return the principal, or empty for anything that does not authenticate
     */
    public Optional<ApiTokenPrincipal> run(String presented, String remoteAddress) {
        Optional<ApiTokenSecret> parsed = ApiTokenSecret.parse(presented);
        if (parsed.isEmpty()) {
            // No audit entry and no query: an unauthenticated endpoint that wrote a row
            // for every malformed header would be a way to fill the audit log from
            // outside.
            return Optional.empty();
        }

        Optional<ApiToken> found = tokens.findByTokenHash(parsed.get().hash());
        if (found.isEmpty()) {
            refuse(parsed.get().prefix(), remoteAddress, "No token with that value.");
            return Optional.empty();
        }

        ApiToken token = found.get();
        Instant now = Instant.now();
        if (!token.isLiveAt(now)) {
            refuse(token.tokenPrefix(), remoteAddress, token.hasExpiredAt(now)
                    ? "The token expired on " + token.expiresAt() + "."
                    : "The token was revoked on " + token.revokedAt() + ".");
            return Optional.empty();
        }

        Optional<Account> owner = accounts.findById(token.accountId());
        if (owner.isEmpty() || owner.get().status() != AccountStatus.ACTIVE) {
            refuse(token.tokenPrefix(), remoteAddress,
                    "The account the token belongs to cannot sign in.");
            return Optional.empty();
        }

        tokens.recordUse(token.id(), now, remoteAddress);
        return Optional.of(ApiTokenPrincipal.of(token, owner.get()));
    }

    /**
     * Records a refusal against the prefix.
     *
     * <p>The actor is {@code SYSTEM}: nothing has authenticated, so there is no account
     * and no token to attribute it to, and {@code API_TOKEN} would need an id the row
     * does not have when the value matched nothing at all. The prefix in the label is
     * what makes the entry actionable - it is the same eight characters the owner sees in
     * their token list.
     */
    private void refuse(String prefix, String remoteAddress, String detail) {
        AuditActor actor = new AuditActor(AuditActorKind.SYSTEM, null, null, null,
                "api token " + ApiTokenSecret.SCHEME + "_" + prefix, remoteAddress, null, null);
        auditTrail.record(AuditEntry.denied(actor, "api_token.authenticate",
                AuditTarget.unidentified("api_token", prefix), null, detail));
    }
}
