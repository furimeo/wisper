package lhqm.furimeo.wisper.org;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Turns the signed-in principal into the account row this package can join against.
 *
 * <p>{@code org} cannot import {@code auth} - the dependency runs the other way
 * (panel-ports.md §6) - so the seam between them is Spring Security's own
 * {@link Authentication}, whose {@code getName()} is whatever {@code auth} authenticated
 * the visitor as. Both plausible values are handled and both are a real lookup: a UUID
 * resolves by primary key, anything else is treated as the email address the
 * {@code account_email_key} index exists for.
 *
 * <p>Anonymous is not an error here, only an absence. Every path that needs a person
 * calls {@link #require()}, and the security chain has already refused anyone who should
 * not have reached it.
 */
@Component
public class ResolveCurrentAccount {

    private final FindAccount accounts;

    public ResolveCurrentAccount(FindAccount accounts) {
        this.accounts = accounts;
    }

    /** The signed-in account, or empty for an anonymous request. */
    public Optional<AccountRef> current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /** The same, from an {@link Authentication} a controller already has in hand. */
    public Optional<AccountRef> of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return Optional.empty();
        }
        return asUuid(name)
                .map(accounts::byId)
                .orElseGet(() -> accounts.byEmail(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * The signed-in account, insisting there is one.
     *
     * @throws NotFoundException when the request is anonymous, or when the principal
     *                           names an account that has since been deleted - a session
     *                           outliving its row is the same "no such thing" every other
     *                           dangling reference produces
     */
    public AccountRef require() {
        return current().orElseThrow(() -> new NotFoundException(
                "No signed-in account for this request"));
    }

    /** As above, from an {@link Authentication} the controller was handed. */
    public AccountRef require(Authentication authentication) {
        return of(authentication).orElseThrow(() -> new NotFoundException(
                "No signed-in account for this request"));
    }

    private static Optional<UUID> asUuid(String candidate) {
        // A UUID is 36 characters with dashes; checking the length first keeps
        // fromString's exception off the hot path for the common case, an email.
        if (candidate.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(candidate));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
