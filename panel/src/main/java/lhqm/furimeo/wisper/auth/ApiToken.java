package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A scoped bearer token for {@code /api/v1/**}. Maps to {@code api_token}.
 *
 * <p>{@code tokenHash} is SHA-256 of the whole token and is the only copy the panel
 * keeps; the value itself is shown once, at creation, and cannot be recovered afterwards.
 * {@code tokenPrefix} is deliberately in the clear so a token found in a log can be
 * matched to a row and revoked without anybody having to know the rest of it.
 *
 * <p>{@code scopes} is a {@code text[]}, which Spring Data JDBC maps to {@code String[]}
 * with no converter. It is exposed through {@link #scopeSet()} rather than being read
 * directly, so nothing outside this record deals with the array.
 *
 * @param organizationId null means the token acts across every organization its owner
 *                       belongs to, which {@link IssueApiToken} only allows for a
 *                       platform operator
 */
public record ApiToken(
        @Id UUID id,
        UUID accountId,
        UUID organizationId,
        String name,
        String tokenPrefix,
        String tokenHash,
        String[] scopes,
        Instant expiresAt,
        Instant lastUsedAt,
        String lastUsedAddress,
        Instant revokedAt,
        String revokedReason,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A token that has just been created and handed to its owner exactly once. */
    public static ApiToken issued(UUID accountId, UUID organizationId, String name,
                                  ApiTokenSecret secret, Collection<ApiScope> scopes,
                                  Instant expiresAt) {
        return new ApiToken(UUID.randomUUID(), accountId, organizationId, name.strip(),
                secret.prefix(), secret.hash(), ApiScope.toWireNames(scopes), expiresAt,
                null, null, null, null, null, null, null);
    }

    /** The scopes as an enum set, which is what every permission check reads. */
    public Set<ApiScope> scopeSet() {
        return ApiScope.ofWireNames(scopes);
    }

    /** Whether this token still authorises anything at {@code now}. */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Whether it stopped working because its own expiry passed rather than by a revocation. */
    public boolean hasExpiredAt(Instant now) {
        return revokedAt == null && expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Dead from now on. Revocation is final; a replacement is a new row. */
    public ApiToken revoked(Instant when, String reason) {
        return new ApiToken(id, accountId, organizationId, name, tokenPrefix, tokenHash, scopes,
                expiresAt, lastUsedAt, lastUsedAddress, when, reason, createdAt, updatedAt, version);
    }

    /**
     * What the owner sees in the list, and what the leaked-token search matches on.
     *
     * <p>The trailing ellipsis is part of the value rather than styling, so a token that
     * is copied out of the page cannot be mistaken for a working one.
     */
    public String maskedValue() {
        return ApiTokenSecret.SCHEME + "_" + tokenPrefix + "_...";
    }
}
