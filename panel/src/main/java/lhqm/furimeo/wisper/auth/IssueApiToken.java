package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Creates a scoped bearer token and returns its value exactly once.
 *
 * <h2>Every customer token is confined to one organization</h2>
 *
 * <p>{@code api_token.organization_id} is nullable, and null means "acts across every
 * organization this account belongs to". That is the right shape for a platform
 * operator's own tooling and the wrong shape for a customer, for two reasons: a token in
 * somebody's CI should stop working when they leave one organization rather than silently
 * following them into the next, and {@link QuotaResource#API_TOKEN} is counted per
 * organization, so an unscoped token would be a token nobody is charged for. A
 * {@link PlatformRole#CUSTOMER} therefore has to name an organization they are a member
 * of; only an {@link PlatformRole#ADMIN} may leave it null.
 *
 * <p>The two {@code nodes:*} scopes are refused to customers for the same reason: a token
 * that can never be used is worse than one that cannot be created, because the failure
 * arrives at three in the morning in somebody's pipeline instead of on the form.
 */
@Component
public class IssueApiToken {

    private final ApiTokenRepository tokens;
    private final AccountRepository accounts;
    private final AccountOrganizations accountOrganizations;
    private final QuotaGuard quotaGuard;
    private final AuditTrail auditTrail;

    public IssueApiToken(ApiTokenRepository tokens, AccountRepository accounts,
                         AccountOrganizations accountOrganizations, QuotaGuard quotaGuard,
                         AuditTrail auditTrail) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.accountOrganizations = accountOrganizations;
        this.quotaGuard = quotaGuard;
        this.auditTrail = auditTrail;
    }

    /**
     * @param organizationId the organization to confine the token to; null is only
     *                       accepted for a platform operator
     * @param expiresAt      when it stops working, or null for no expiry
     * @throws CredentialRejected if the name is blank or taken, the scope set is empty or
     *                            not permitted, or the expiry is already in the past
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded if the organization is at its limit
     */
    @Transactional
    public Issued run(UUID accountId, String name, UUID organizationId, Set<ApiScope> scopes,
                      Instant expiresAt, AuditActor actor) {

        Account owner = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        String tokenName = name == null ? "" : name.strip();
        if (tokenName.isEmpty()) {
            throw CredentialRejected.of("name",
                    "Give the token a name, so you can tell it from the others later.");
        }
        if (tokenName.length() > 80) {
            throw CredentialRejected.of("name", "Keep the name under 80 characters.");
        }
        if (tokens.existsByAccountIdAndName(accountId, tokenName)) {
            throw CredentialRejected.of("name", "You already have a token called that.");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw CredentialRejected.of("scopes",
                    "Choose at least one permission. A token with none can do nothing.");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw CredentialRejected.of("expiresAt", "Choose an expiry in the future.");
        }

        boolean platformOperator = owner.platformRole() == PlatformRole.ADMIN;
        if (!platformOperator && scopes.stream().anyMatch(ApiScope::isPlatformOnly)) {
            throw CredentialRejected.of("scopes",
                    "Node permissions belong to the platform operator, not to a customer token.");
        }
        if (organizationId == null) {
            if (!platformOperator) {
                throw CredentialRejected.of("organizationId",
                        "Choose which organization this token may act in.");
            }
        } else {
            if (!accountOrganizations.isMember(accountId, organizationId)) {
                throw NotFoundException.of("organization", organizationId);
            }
            quotaGuard.require(organizationId, QuotaResource.API_TOKEN, 1);
        }

        ApiTokenSecret secret = ApiTokenSecret.generate();
        ApiToken saved = tokens.save(
                ApiToken.issued(accountId, organizationId, tokenName, secret, scopes, expiresAt));

        auditTrail.record(AuditEntry.succeeded(actor, "api_token.create",
                AuditTarget.of("api_token", saved.id(), saved.name()), organizationId,
                "Issued with " + scopes.size() + " scope(s), prefix " + saved.tokenPrefix()
                        + (expiresAt == null ? ", no expiry." : ", expiring " + expiresAt + ".")));

        return new Issued(saved, secret.value());
    }

    /**
     * The row, and the token value that will never be readable again.
     *
     * <p>The value goes into a flash message and onto the page that follows the redirect.
     * It is deliberately not a prop on a page that can be reloaded: a value that survives
     * a refresh survives a browser's back button, a screenshot and a shared screen.
     */
    public record Issued(ApiToken token, String value) {
    }
}
