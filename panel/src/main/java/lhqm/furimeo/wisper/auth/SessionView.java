package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the "where you are signed in" list.
 *
 * <p>{@link Session} holds {@code sessionIdHash}, and a hash of a live session id in a
 * page's props is a credential in the page's props. This record is what the screen gets
 * instead.
 *
 * @param current whether this is the browser asking. The list needs it to label one entry
 *                "this device" and to not offer a revoke button that signs the reader out
 *                of the page they are looking at
 */
public record SessionView(
        UUID id,
        String remoteAddress,
        String userAgent,
        Instant lastSeenAt,
        Instant createdAt,
        Instant expiresAt,
        boolean secondFactorSatisfied,
        boolean current) {

    public static SessionView of(Session session, boolean current) {
        return new SessionView(session.id(), session.remoteAddress(), session.userAgent(),
                session.lastSeenAt(), session.createdAt(), session.expiresAt(),
                session.isSecondFactorSatisfied(), current);
    }
}
