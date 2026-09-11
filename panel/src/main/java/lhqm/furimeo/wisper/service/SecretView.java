package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

/**
 * What a screen is allowed to know about a secret.
 *
 * <p>There is no value component, and that absence is the feature. A record with a value
 * field and a rule about never serialising it is a rule; a record with no value field is a
 * compiler error for anyone who tries. {@link ListSecrets} builds these from a query that
 * does not select the column, so a secret cannot reach a browser even by accident - not
 * through a model attribute, not through a log line, not through a debugger sitting on a
 * controller.
 *
 * <p>What a customer gets instead is when it was last set. That is the question behind
 * "what is my secret" nine times out of ten - has the deploy key I rotated on Tuesday
 * actually landed - and the tenth time the honest answer is to set a new one.
 *
 * @param lastChangedAt when the value was last written, whether at creation or on a
 *                      rotation. One column on the screen rather than two dates the
 *                      customer has to reconcile.
 */
public record SecretView(
        UUID id,
        String name,
        boolean buildTime,
        Instant lastChangedAt,
        Instant createdAt) {
}
