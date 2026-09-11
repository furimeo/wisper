package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code api_token}.
 *
 * <p>{@link #findByTokenHash} is the lookup on every {@code /api/v1} request and is what
 * {@code api_token_hash_key} exists for. Nothing looks a token up by its prefix: the
 * prefix is for a human matching a leak in a log to a row, not for authentication, and
 * treating it as an identifier would turn sixteen readable characters into a credential.
 */
public interface ApiTokenRepository extends ListCrudRepository<ApiToken, UUID> {

    /** The authentication lookup: SHA-256 of the whole presented token. */
    Optional<ApiToken> findByTokenHash(String tokenHash);

    /** The token list in /settings/tokens, newest first, revoked ones included. */
    List<ApiToken> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /** The unique index on {@code (account_id, name)}, checked before the insert. */
    boolean existsByAccountIdAndName(UUID accountId, String name);

    /**
     * Records that a token was used, without touching anything else on the row.
     *
     * <p>A narrow UPDATE because it happens on every API call. It deliberately does not
     * go through the aggregate: a read-modify-save here would make two concurrent API
     * calls with the same token fail one of them on the optimistic version check, which
     * is a strange way for a working token to stop working.
     */
    @Modifying
    @Query("""
            UPDATE api_token
               SET last_used_at = :when, last_used_address = :address, version = version + 1
             WHERE id = :id
            """)
    int recordUse(@Param("id") UUID id,
                  @Param("when") Instant when,
                  @Param("address") String address);

    /**
     * Revokes tokens whose own expiry has passed.
     *
     * <p>{@link ApiToken#isLiveAt} already refuses an expired token at authentication
     * time, so this is not what makes expiry safe - it is what makes the list the
     * customer looks at agree with what the API does.
     */
    @Modifying
    @Query("""
            UPDATE api_token
               SET revoked_at = :now, revoked_reason = 'EXPIRED', version = version + 1
             WHERE revoked_at IS NULL
               AND expires_at IS NOT NULL
               AND expires_at <= :now
            """)
    int revokeExpired(@Param("now") Instant now);
}
