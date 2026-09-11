package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * One signed-in browser. Maps to {@code session}.
 *
 * <p>This is not Spring Session JDBC - that dependency is not on the classpath and the
 * servlet container still holds the {@code HttpSession}. What this row adds is the part
 * the container cannot: a list the customer can look at, a "sign out everywhere" that
 * takes effect on the next request wherever it is made, and a record of where each
 * sign-in came from.
 *
 * @param sessionIdHash   SHA-256 of the container's session id. The raw id is a bearer
 *                        credential, so storing it would turn read access to this table
 *                        into session hijacking
 * @param secondFactorAt  null while the TOTP challenge is outstanding. Such a session may
 *                        reach {@code /login/**} and {@code /logout} and nothing else -
 *                        {@link SessionGateFilter} is what enforces that
 * @param expiresAt       the absolute ceiling, independent of the container's idle
 *                        timeout: a tab left open for a month is still signed out
 */
public record Session(
        @Id UUID id,
        UUID accountId,
        String sessionIdHash,
        String remoteAddress,
        String userAgent,
        Instant secondFactorAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        SessionRevocationReason revokedReason,
        @CreatedDate Instant createdAt,
        @Version Long version) {

    /** How much of a user-agent string is worth keeping to tell two browsers apart. */
    private static final int USER_AGENT_LIMIT = 400;

    /** A row for a browser that has just authenticated. */
    public static Session opened(UUID accountId, String sessionIdHash, String remoteAddress,
                                 String userAgent, Instant secondFactorAt, Instant now,
                                 Instant expiresAt) {
        return new Session(UUID.randomUUID(), accountId, sessionIdHash, remoteAddress,
                trim(userAgent), secondFactorAt, now, expiresAt, null, null, null, null);
    }

    /** Whether this row still authorises anything at {@code now}. */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** Whether the TOTP challenge has been answered, or was never needed. */
    public boolean isSecondFactorSatisfied() {
        return secondFactorAt != null;
    }

    /** The challenge has just been answered correctly. */
    public Session withSecondFactorAt(Instant when) {
        return new Session(id, accountId, sessionIdHash, remoteAddress, userAgent, when,
                lastSeenAt, expiresAt, revokedAt, revokedReason, createdAt, version);
    }

    /** Ends the session. Nothing un-revokes one; a new sign-in writes a new row. */
    public Session revoked(Instant when, SessionRevocationReason reason) {
        return new Session(id, accountId, sessionIdHash, remoteAddress, userAgent, secondFactorAt,
                lastSeenAt, expiresAt, when, reason, createdAt, version);
    }

    private static String trim(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= USER_AGENT_LIMIT
                ? userAgent
                : userAgent.substring(0, USER_AGENT_LIMIT);
    }
}
