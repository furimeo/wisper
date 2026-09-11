package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * An account as a page is allowed to see it.
 *
 * <p>{@link Account} carries a BCrypt hash and an encrypted TOTP secret, and a page prop
 * is JSON in the HTML source of the response. Serialising the aggregate directly would
 * put both in front of the browser, so the conversion is a separate type rather than a
 * list of fields each controller has to remember to leave out.
 *
 * <p>Used by three screens: the profile page, the security page and the admin account
 * list. One record for all three, because they show the same facts about a person and
 * three near-identical records would drift.
 *
 * @param twoFactorEnabled true only when the enrolment was confirmed; a secret that was
 *                         generated and never verified is not two-factor authentication
 * @param lockedUntil      when the sign-in throttle is holding the account shut, so an
 *                         operator can see why somebody cannot get in
 */
public record AccountProfile(
        UUID id,
        String email,
        String displayName,
        PlatformRole platformRole,
        AccountStatus status,
        boolean twoFactorEnabled,
        String locale,
        Instant lastLoginAt,
        String lastLoginAddress,
        Instant passwordChangedAt,
        Instant lockedUntil,
        Instant createdAt) {

    public static AccountProfile of(Account account) {
        return new AccountProfile(account.id(), account.email(), account.displayName(),
                account.platformRole(), account.status(), account.hasSecondFactor(),
                account.locale(),
                account.lastLoginAt(), account.lastLoginAddress(), account.passwordChangedAt(),
                account.lockedUntil(), account.createdAt());
    }
}
