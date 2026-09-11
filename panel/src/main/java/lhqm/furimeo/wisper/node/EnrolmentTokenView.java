package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

/**
 * One bootstrap token as the node's page shows it.
 *
 * <p>{@link NodeEnrollmentToken} is not sent to the browser, for one reason:
 * {@code token_hash}. It is not the token and it is not usefully attackable - the token is
 * 256 random bits - but a page that ships the contents of a column the panel is only
 * allowed to compare has stopped treating that column as a comparison value, and the next
 * person to add a field will not know the difference mattered.
 *
 * <p>{@code state} is computed here rather than in TypeScript because "usable" is four
 * conditions - not spent, not revoked, not expired, and an unenrolled node - and a second
 * implementation of it on the client is a second definition that drifts.
 *
 * @param usedFromAddress where it was spent. This is the field an operator looks at when
 *                        they are asking whether the enrolment they are seeing was them.
 */
public record EnrolmentTokenView(UUID id, State state, Instant createdAt, Instant expiresAt,
                                 Instant usedAt, String usedFromAddress, Instant revokedAt) {

    /** What has become of a token. */
    public enum State {

        /** Still good, and still the one thing that can enrol this node. */
        LIVE,

        /** Spent. The enrolment it performed is on the same row. */
        USED,

        /** Withdrawn before anybody used it. */
        REVOKED,

        /** Its fifteen minutes ran out. Issue another; that is one click. */
        EXPIRED
    }

    public static EnrolmentTokenView of(NodeEnrollmentToken token, Instant now) {
        return new EnrolmentTokenView(token.id(), stateOf(token, now), token.createdAt(),
                token.expiresAt(), token.usedAt(), token.usedFromAddress(), token.revokedAt());
    }

    private static State stateOf(NodeEnrollmentToken token, Instant now) {
        if (token.isSpent()) {
            return State.USED;
        }
        if (token.isRevoked()) {
            return State.REVOKED;
        }
        return token.hasExpired(now) ? State.EXPIRED : State.LIVE;
    }
}
