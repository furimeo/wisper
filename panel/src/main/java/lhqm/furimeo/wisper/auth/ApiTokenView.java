package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the API token list.
 *
 * <p>{@link ApiToken} holds the SHA-256 of the token; this holds the masked value the
 * owner can recognise it by. The full value appears exactly once, in the flash message
 * that follows creation, and is never a prop on a page that can be reloaded.
 *
 * @param maskedValue {@code wsp_a7Kd93Lm_...} - enough to match a leak in a log to a row
 * @param live        whether it would authenticate a request right now, which is not the
 *                    same as "not revoked": an expired token is still an unrevoked row
 *                    between one sweep and the next
 */
public record ApiTokenView(
        UUID id,
        String name,
        String maskedValue,
        UUID organizationId,
        List<String> scopes,
        Instant expiresAt,
        Instant lastUsedAt,
        String lastUsedAddress,
        Instant revokedAt,
        String revokedReason,
        Instant createdAt,
        boolean live) {

    public static ApiTokenView of(ApiToken token, Instant now) {
        return new ApiTokenView(token.id(), token.name(), token.maskedValue(),
                token.organizationId(),
                token.scopeSet().stream().map(ApiScope::wireName).toList(),
                token.expiresAt(), token.lastUsedAt(), token.lastUsedAddress(),
                token.revokedAt(), token.revokedReason(), token.createdAt(),
                token.isLiveAt(now));
    }
}
