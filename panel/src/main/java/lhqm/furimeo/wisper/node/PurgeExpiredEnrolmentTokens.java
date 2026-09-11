package lhqm.furimeo.wisper.node;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes bootstrap tokens nobody used and nobody can use any more.
 *
 * <p>A token has a fifteen-minute life and there is one live at a time per node, so this
 * is never large. It exists because {@code node_enrollment_token_expiry_idx} was created
 * for it and because an unbounded table of dead hashes is the kind of thing that is fine
 * for two years and then is not.
 *
 * <h2>Spent tokens are kept</h2>
 *
 * <p>Only rows with a null {@code used_at} go. A spent token's row is the evidence that
 * this node enrolled at this moment from this address, and that is the answer to the only
 * question anybody ever asks about an enrolment - "was that us?". Deleting it to save a
 * hundred bytes would remove the record on exactly the occasion somebody comes looking.
 *
 * <p>The grace period is deliberate. A token that expired thirty seconds ago is still on
 * the screen an operator is staring at, and a row vanishing underneath a page is a page
 * that starts producing "no such token" for a button somebody can still see.
 */
@Component
public class PurgeExpiredEnrolmentTokens {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredEnrolmentTokens.class);

    /** How long an expired, unused token stays visible before it is swept. */
    private static final Duration GRACE = Duration.ofHours(24);

    private final NodeEnrollmentTokenRepository tokens;

    public PurgeExpiredEnrolmentTokens(NodeEnrollmentTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** @return how many rows went, for the job's log line */
    @Transactional
    public int sweep() {
        int deleted = tokens.deleteUnusedExpiredBefore(Instant.now().minus(GRACE));
        if (deleted > 0) {
            log.debug("Deleted {} expired bootstrap tokens that were never used", deleted);
        }
        return deleted;
    }
}
