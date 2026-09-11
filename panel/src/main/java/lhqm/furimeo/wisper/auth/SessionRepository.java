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
 * Reads and writes {@code session}.
 *
 * <p>{@link #findBySessionIdHash} runs on every authenticated request, which is why the
 * unique index on that column exists and why {@link SessionGateFilter} reads the whole
 * row once and answers three questions from it rather than asking three times.
 *
 * <p>The bulk revocations are written as SQL rather than as a read-modify-save loop
 * because "sign out everywhere" has to be one statement: a loop would leave the person
 * signed out of some browsers if it failed halfway, and would race with a session being
 * touched at the same moment. They bump {@code version} by hand for the same reason the
 * mapper does - a row whose version stops moving would let a stale in-memory copy
 * overwrite the revocation.
 */
public interface SessionRepository extends ListCrudRepository<Session, UUID> {

    /** The lookup on every request, keyed by SHA-256 of the container's session id. */
    Optional<Session> findBySessionIdHash(String sessionIdHash);

    /** The "active sessions" list in /settings/security, most recently seen first. */
    List<Session> findByAccountIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID accountId);

    /**
     * Ends every live session for an account.
     *
     * <p>Used by "sign out everywhere", by a password change and by a suspension. The
     * caller passes the reason so the person is told which of the three it was.
     */
    @Modifying
    @Query("""
            UPDATE session
               SET revoked_at = :when, revoked_reason = :reason, version = version + 1
             WHERE account_id = :accountId
               AND revoked_at IS NULL
            """)
    int revokeAllFor(@Param("accountId") UUID accountId,
                     @Param("when") Instant when,
                     @Param("reason") String reason);

    /**
     * Ends every live session for an account except the one making the request.
     *
     * <p>The shape "sign out everywhere else" needs: a person changing their password
     * because a laptop was stolen should not be logged out of the browser they are
     * currently typing in.
     */
    @Modifying
    @Query("""
            UPDATE session
               SET revoked_at = :when, revoked_reason = :reason, version = version + 1
             WHERE account_id = :accountId
               AND id <> :keep
               AND revoked_at IS NULL
            """)
    int revokeAllForExcept(@Param("accountId") UUID accountId,
                           @Param("keep") UUID keep,
                           @Param("when") Instant when,
                           @Param("reason") String reason);

    /**
     * Moves {@code last_seen_at} forward.
     *
     * <p>A single narrow UPDATE rather than a save of the whole aggregate: this runs on
     * requests that are otherwise reads, and rewriting eleven columns to advance one
     * timestamp would make every page load a bigger write than it needs to be.
     */
    @Modifying
    @Query("UPDATE session SET last_seen_at = :when, version = version + 1 WHERE id = :id")
    int touch(@Param("id") UUID id, @Param("when") Instant when);

    /**
     * Deletes rows that can no longer authorise anything and are old enough to be of no
     * interest to the sign-in history.
     *
     * <p>Deleted rather than left revoked: the "where you signed in from" list is only
     * useful for recent history, and an unbounded table makes the per-request lookup pay
     * for years of dead rows.
     */
    @Modifying
    @Query("""
            DELETE FROM session
             WHERE expires_at < :cutoff
                OR (revoked_at IS NOT NULL AND revoked_at < :cutoff)
            """)
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Marks live rows whose {@code expires_at} has passed, so the customer's session list
     * shows the truth between sweeps of {@link #deleteFinishedBefore}.
     */
    @Modifying
    @Query("""
            UPDATE session
               SET revoked_at = :now, revoked_reason = 'EXPIRED', version = version + 1
             WHERE revoked_at IS NULL
               AND expires_at <= :now
            """)
    int revokeExpired(@Param("now") Instant now);
}
