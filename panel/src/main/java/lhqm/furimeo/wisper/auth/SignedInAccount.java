package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Who is signed in, as it sits in the {@code SecurityContext}.
 *
 * <p>Controllers take it with {@code @AuthenticationPrincipal SignedInAccount account}.
 * It is a record and not a {@code UserDetails}: {@code UserDetails} exists to carry a
 * password hash into the authentication decision, and by the time this object exists the
 * decision is made. Holding the hash afterwards would mean every request kept a
 * credential in memory that no request needs.
 *
 * <p>Nothing here is a secret, which is also what makes it safe to hand straight to the
 * page as a shared prop.
 */
public record SignedInAccount(UUID id, String email, String displayName, PlatformRole role) {

    /** Granted to everyone who is signed in, so a page can require it without naming roles. */
    public static final String USER_AUTHORITY = "ROLE_USER";

    public SignedInAccount {
        if (id == null || email == null || role == null) {
            throw new IllegalArgumentException("A signed-in account needs an id, email and role");
        }
    }

    /** Reads the principal off a row, dropping the two credential columns. */
    public static SignedInAccount of(Account account) {
        return new SignedInAccount(account.id(), account.email(), account.displayName(),
                account.platformRole());
    }

    /**
     * What {@code SecurityConfig} matches on.
     *
     * <p>{@code ROLE_USER} for everybody and {@code ROLE_ADMIN} on top for an operator.
     * Two authorities rather than one because {@code hasRole("ADMIN")} in the request map
     * has to fail for a customer, and an authority list that varies in length is easier
     * to read in a log than one that varies in content.
     */
    public List<GrantedAuthority> authorities() {
        if (role == PlatformRole.ADMIN) {
            return List.of(new SimpleGrantedAuthority(USER_AUTHORITY),
                    new SimpleGrantedAuthority(role.authority()));
        }
        return List.of(new SimpleGrantedAuthority(USER_AUTHORITY));
    }

    /** Whether this person may reach {@code /admin/**}. */
    public boolean isPlatformAdmin() {
        return role == PlatformRole.ADMIN;
    }

    /** What the audit trail records as the actor label. */
    public String auditLabel() {
        return email;
    }
}
