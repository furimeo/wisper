package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code node_enrollment_token} table. */
public interface NodeEnrollmentTokenRepository
        extends ListCrudRepository<NodeEnrollmentToken, UUID> {

    /**
     * The enrolment lookup.
     *
     * <p>By hash alone, not by {@code (node, hash)}: a token names its own node, and
     * asking the caller which node it belongs to would let a machine present a valid token
     * against a different record.
     */
    Optional<NodeEnrollmentToken> findByTokenHash(String tokenHash);

    /**
     * Live tokens for one node - the predicate {@code node_enrollment_token_live_idx} is
     * partial for. Shown on the node's page next to the install command.
     */
    @Query("""
            SELECT * FROM node_enrollment_token
             WHERE node_id = :nodeId
               AND used_at IS NULL
               AND revoked_at IS NULL
               AND expires_at > :now
             ORDER BY expires_at DESC
            """)
    List<NodeEnrollmentToken> findLive(@Param("nodeId") UUID nodeId, @Param("now") Instant now);

    /** Every token ever issued for one node, newest first: the history half. */
    @Query("SELECT * FROM node_enrollment_token WHERE node_id = :nodeId ORDER BY created_at DESC")
    List<NodeEnrollmentToken> findHistory(@Param("nodeId") UUID nodeId);

    /**
     * Deletes tokens nobody used and nobody can use.
     *
     * <p>Only the ones that were never spent: a spent token's row is the evidence that
     * this node enrolled at this moment from this address, and deleting it would remove
     * the answer to the only interesting question about an enrolment. The grace period is
     * the caller's, so a token that expired thirty seconds ago is still visible on the
     * page that just showed it.
     */
    @Modifying
    @Query("""
            DELETE FROM node_enrollment_token
             WHERE used_at IS NULL
               AND expires_at < :before
            """)
    int deleteUnusedExpiredBefore(@Param("before") Instant before);
}
