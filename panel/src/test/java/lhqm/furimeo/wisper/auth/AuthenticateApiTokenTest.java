package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Authenticating a bearer token, and the property that makes storing it safe: the row
 * holds a digest, so the lookup is by digest and the value never has to be written down.
 *
 * <p>Four things have to hold for a token to work and each is tested separately, because
 * the interesting failure is the one that is forgotten - and the one that is usually
 * forgotten is the last: a suspended person's tokens have to stop working, or suspension
 * is only a lock on the sign-in form.
 */
class AuthenticateApiTokenTest {

    private final ApiTokenRepository tokens = mock(ApiTokenRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final AuthenticateApiToken authenticateApiToken =
            new AuthenticateApiToken(tokens, accounts, auditTrail);

    private Account owner;
    private ApiTokenSecret secret;
    private ApiToken token;

    @BeforeEach
    void setUp() {
        owner = Account.create("someone@example.com", "Someone", "$2a$04$notusedhere",
                PlatformRole.CUSTOMER, Instant.now());
        secret = ApiTokenSecret.generate();
        token = ApiToken.issued(owner.id(), UUID.randomUUID(), "ci", secret,
                Set.of(ApiScope.DEPLOYMENTS_WRITE, ApiScope.SERVICES_READ), null);

        when(tokens.findByTokenHash(secret.hash())).thenReturn(Optional.of(token));
        when(accounts.findById(owner.id())).thenReturn(Optional.of(owner));
    }

    @Test
    @DisplayName("the value is found by its digest, not by anything stored in the clear")
    void looksTheTokenUpByItsHash() {
        assertThat(authenticateApiToken.run(secret.value(), "203.0.113.7")).isPresent();

        verify(tokens).findByTokenHash(secret.hash());
        // The prefix is in the clear for humans matching a leak to a row. Nothing
        // authenticates with it.
        verify(tokens, never()).findByTokenHash(token.tokenPrefix());
    }

    @Test
    @DisplayName("an accepted token becomes a principal carrying exactly its scopes")
    void producesAPrincipal() {
        ApiTokenPrincipal principal = authenticateApiToken.run(secret.value(), null).orElseThrow();

        assertThat(principal.tokenId()).isEqualTo(token.id());
        assertThat(principal.accountId()).isEqualTo(owner.id());
        assertThat(principal.accountEmail()).isEqualTo(owner.email());
        assertThat(principal.organizationId()).isEqualTo(token.organizationId());
        assertThat(principal.scopes())
                .containsExactlyInAnyOrder(ApiScope.DEPLOYMENTS_WRITE, ApiScope.SERVICES_READ);
        assertThat(principal.hasScope(ApiScope.NODES_WRITE)).isFalse();
        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER", "SCOPE_deployments:write", "SCOPE_services:read")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a use is recorded against the row, with where it came from")
    void recordsTheUse() {
        authenticateApiToken.run(secret.value(), "198.51.100.4");

        verify(tokens).recordUse(eq(token.id()), any(Instant.class), eq("198.51.100.4"));
    }

    @Test
    @DisplayName("a value that is not one of ours is refused without touching the database")
    void refusesForeignValuesWithoutAQuery() {
        assertThat(authenticateApiToken.run("not-a-token", null)).isEmpty();
        assertThat(authenticateApiToken.run("", null)).isEmpty();
        assertThat(authenticateApiToken.run(null, null)).isEmpty();

        verify(tokens, never()).findByTokenHash(anyString());
    }

    @Test
    @DisplayName("a well-formed value that matches no row is refused")
    void refusesUnknownTokens() {
        ApiTokenSecret other = ApiTokenSecret.generate();
        when(tokens.findByTokenHash(other.hash())).thenReturn(Optional.empty());

        assertThat(authenticateApiToken.run(other.value(), null)).isEmpty();
    }

    @Test
    @DisplayName("a revoked token stops working immediately")
    void refusesRevokedTokens() {
        when(tokens.findByTokenHash(secret.hash()))
                .thenReturn(Optional.of(token.revoked(Instant.now().minusSeconds(1), "leaked")));

        assertThat(authenticateApiToken.run(secret.value(), null)).isEmpty();
        verify(tokens, never()).recordUse(any(), any(), any());
    }

    @Test
    @DisplayName("an expired token is refused before any sweep has revoked it")
    void refusesExpiredTokens() {
        ApiToken expired = ApiToken.issued(owner.id(), null, "old", secret,
                Set.of(ApiScope.PROJECTS_READ), Instant.now().minusSeconds(60));
        when(tokens.findByTokenHash(secret.hash())).thenReturn(Optional.of(expired));

        assertThat(authenticateApiToken.run(secret.value(), null)).isEmpty();
    }

    @Test
    @DisplayName("a token whose owner is suspended stops working too")
    void refusesTokensOfSuspendedAccounts() {
        when(accounts.findById(owner.id()))
                .thenReturn(Optional.of(owner.withStatus(AccountStatus.SUSPENDED)));

        assertThat(authenticateApiToken.run(secret.value(), null)).isEmpty();
    }

    @Test
    @DisplayName("a token whose owner has been deleted stops working")
    void refusesOrphanedTokens() {
        when(accounts.findById(owner.id())).thenReturn(Optional.empty());

        assertThat(authenticateApiToken.run(secret.value(), null)).isEmpty();
    }

    @Test
    @DisplayName("an operator's token carries ROLE_ADMIN, a customer's does not")
    void reflectsThePlatformRole() {
        Account operator = Account.create("op@example.com", "Operator", "$2a$04$notusedhere",
                PlatformRole.ADMIN, Instant.now());
        ApiToken platformToken = ApiToken.issued(operator.id(), null, "fleet", secret,
                Set.of(ApiScope.NODES_READ), null);
        when(tokens.findByTokenHash(secret.hash())).thenReturn(Optional.of(platformToken));
        when(accounts.findById(operator.id())).thenReturn(Optional.of(operator));

        ApiTokenPrincipal principal = authenticateApiToken.run(secret.value(), null).orElseThrow();

        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN", "SCOPE_nodes:read");
    }
}
