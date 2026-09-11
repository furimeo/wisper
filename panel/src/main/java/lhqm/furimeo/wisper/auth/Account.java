package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A person who can sign in. Maps to {@code account}.
 *
 * <p>Immutable, like every aggregate in the panel: a change is a new instance handed to
 * {@code AccountRepository.save}, and {@code version} is what makes the update
 * optimistically checked. The {@code withXxx} methods below are the only ways this record
 * changes, which is what keeps "signing in resets the failure count" from being written
 * three slightly different times.
 *
 * <p>Two fields are credentials and neither leaves this package: {@code passwordHash} is
 * BCrypt and is only ever compared, {@code totpSecret} is an AES-GCM envelope
 * ({@link lhqm.furimeo.wisper.crypto.SecretCipher}) and is only ever decrypted to check a
 * code. Screens receive {@link AccountProfile}, which has neither.
 *
 * @param email          always lower-cased; the CHECK in V2 rejects anything else, which
 *                       is what makes the plain unique index a case-insensitive one
 * @param totpSecret     the encrypted envelope, null until enrolment starts
 * @param totpConfirmedAt null until one code has been verified. A secret without this is
 *                       a half-finished enrolment and must not challenge anybody
 * @param lockedUntil    set by the sign-in throttle; the row is never deleted to lock
 *                       somebody out, because then the lock could not be lifted
 */
public record Account(
        @Id UUID id,
        String email,
        String displayName,
        String passwordHash,
        Instant passwordChangedAt,
        PlatformRole platformRole,
        AccountStatus status,
        String locale,
        String totpSecret,
        Instant totpConfirmedAt,
        Instant lastLoginAt,
        String lastLoginAddress,
        int failedLoginCount,
        Instant lockedUntil,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * What a new account reads the panel in until it says otherwise.
     *
     * <p>English rather than the browser's first choice, because an account is created by
     * an operator or by a script and the {@code Accept-Language} on that request belongs
     * to whoever pressed the button, not to the person who will use the account. The
     * browser preference is honoured for the session instead, which is the request that
     * genuinely comes from them.
     */
    public static final String DEFAULT_LOCALE = "en";

    /**
     * A brand new account, ready to insert.
     *
     * <p>A null {@code version} is what tells Spring Data JDBC this is an insert despite
     * the id already being set (docs/contracts/schema.md §1).
     */
    public static Account create(String email, String displayName, String passwordHash,
                                 PlatformRole platformRole, Instant now) {
        return new Account(UUID.randomUUID(), normaliseEmail(email), displayName.strip(),
                passwordHash, now, platformRole, AccountStatus.ACTIVE, DEFAULT_LOCALE,
                null, null, null, null, 0, null, null, null, null);
    }

    /**
     * Lower-cases and trims an address the way the column requires.
     *
     * <p>Called on every path that reads or writes an email, including the sign-in form,
     * so that {@code Alice@Example.COM} and {@code alice@example.com} are one account
     * rather than one account and one failed sign-in.
     */
    public static String normaliseEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Whether the second factor is set up and confirmed, so sign-in must challenge. */
    public boolean hasSecondFactor() {
        return totpSecret != null && totpConfirmedAt != null;
    }

    /** Whether the throttle is currently holding this account shut. */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** One more wrong password, with the lock not yet reached. */
    public Account withFailedSignIn(int count) {
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                status, locale, totpSecret, totpConfirmedAt, lastLoginAt, lastLoginAddress,
                count, lockedUntil, createdAt, updatedAt, version);
    }

    /**
     * The throttle bites: shut until {@code until}, counter back to zero.
     *
     * <p>Zeroing the counter is deliberate. Keeping it would mean the first wrong guess
     * after a lock expires locks the account again immediately, and an attacker who has
     * given up would still be holding the real owner out.
     */
    public Account lockedUntil(Instant until) {
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                status, locale, totpSecret, totpConfirmedAt, lastLoginAt, lastLoginAddress,
                0, until, createdAt, updatedAt, version);
    }

    /** A successful sign-in: remember where from, forget the failures, lift any lock. */
    public Account signedInAt(Instant when, String address) {
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                status, locale, totpSecret, totpConfirmedAt, when, address,
                0, null, createdAt, updatedAt, version);
    }

    /** A new BCrypt hash, with the moment it happened - sessions older than it are void. */
    public Account withPassword(String newHash, Instant changedAt) {
        return new Account(id, email, displayName, newHash, changedAt, platformRole,
                status, locale, totpSecret, totpConfirmedAt, lastLoginAt, lastLoginAddress,
                failedLoginCount, lockedUntil, createdAt, updatedAt, version);
    }

    /**
     * Sets or clears the second factor.
     *
     * <p>Both halves move together so the {@code account_totp_confirmed_needs_secret}
     * CHECK cannot be reached from Java: clearing the secret clears the confirmation, and
     * an enrolment that has not been confirmed passes null for {@code confirmedAt}.
     */
    public Account withTotp(String encryptedSecret, Instant confirmedAt) {
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                status, locale, encryptedSecret, encryptedSecret == null ? null : confirmedAt,
                lastLoginAt, lastLoginAddress, failedLoginCount, lockedUntil,
                createdAt, updatedAt, version);
    }

    /** Suspended or reactivated by an operator. */
    public Account withStatus(AccountStatus newStatus) {
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                newStatus, locale, totpSecret, totpConfirmedAt, lastLoginAt, lastLoginAddress,
                failedLoginCount, newStatus == AccountStatus.ACTIVE ? null : lockedUntil,
                createdAt, updatedAt, version);
    }

    /**
     * The language this person reads the panel in.
     *
     * <p>Refused rather than coerced when it is not one the panel has: the column's CHECK
     * would reject it a moment later anyway, and a message naming the field beats a
     * constraint violation naming the constraint.
     */
    public Account withLocale(String newLocale) {
        if (!SupportedLocale.isSupported(newLocale)) {
            throw new IllegalArgumentException("wisper is not translated into " + newLocale);
        }
        return new Account(id, email, displayName, passwordHash, passwordChangedAt, platformRole,
                status, newLocale, totpSecret, totpConfirmedAt, lastLoginAt, lastLoginAddress,
                failedLoginCount, lockedUntil, createdAt, updatedAt, version);
    }

    /** The name shown in the header and written into audit entries. */
    public Account withDisplayName(String newDisplayName) {
        return new Account(id, email, newDisplayName.strip(),
                passwordHash, passwordChangedAt, platformRole, status, locale, totpSecret,
                totpConfirmedAt,
                lastLoginAt, lastLoginAddress, failedLoginCount, lockedUntil,
                createdAt, updatedAt, version);
    }
}
