package lhqm.furimeo.wisper.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Who is calling {@code /api/v1/**}, as it sits in the {@code SecurityContext}.
 *
 * <p>Separate from {@link SignedInAccount} on purpose. A token call and a browser session
 * are not the same caller even when they belong to the same person: the token carries a
 * narrower set of permissions, may be confined to one organization, and must appear in
 * the audit trail as {@code API_TOKEN} rather than as the person. A single principal type
 * with a nullable token id would make every one of those distinctions an {@code if}
 * somebody eventually forgets.
 *
 * @param organizationId the organization the token is confined to, or null when it acts
 *                       across every organization its owner belongs to
 */
public record ApiTokenPrincipal(
        UUID tokenId,
        UUID accountId,
        UUID organizationId,
        String tokenName,
        String accountEmail,
        PlatformRole role,
        Set<ApiScope> scopes) {

    public ApiTokenPrincipal {
        if (tokenId == null || accountId == null || role == null) {
            throw new IllegalArgumentException("An API token principal needs a token, an account "
                    + "and the platform role of that account");
        }
        scopes = Set.copyOf(scopes);
    }

    /** Builds the principal from the row and the account that owns it. */
    public static ApiTokenPrincipal of(ApiToken token, Account owner) {
        return new ApiTokenPrincipal(token.id(), token.accountId(), token.organizationId(),
                token.name(), owner.email(), owner.platformRole(), token.scopeSet());
    }

    /**
     * {@code ROLE_*} for the request map in {@code SecurityConfig}, plus one
     * {@code SCOPE_*} per scope for the endpoints that check a specific permission.
     */
    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> authorities = new ArrayList<>(scopes.size() + 2);
        authorities.add(new SimpleGrantedAuthority(SignedInAccount.USER_AUTHORITY));
        if (role == PlatformRole.ADMIN) {
            authorities.add(new SimpleGrantedAuthority(role.authority()));
        }
        scopes.forEach(scope -> authorities.add(new SimpleGrantedAuthority(scope.authority())));
        return List.copyOf(authorities);
    }

    /** Whether this token carries a particular permission. */
    public boolean hasScope(ApiScope scope) {
        return scopes.contains(scope);
    }

    /** What the audit trail records as the actor label for a token call. */
    public String auditLabel() {
        return tokenName;
    }
}
