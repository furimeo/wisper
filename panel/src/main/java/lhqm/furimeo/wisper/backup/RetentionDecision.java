package lhqm.furimeo.wisper.backup;

import java.util.List;
import java.util.UUID;

/**
 * What retention decided about one policy's snapshots.
 *
 * <p>Two disjoint lists rather than a predicate, because both halves are used: the expired
 * ids are marked in the database, and the kept count is what the screen shows so a
 * customer can see the rule working rather than only noticing when something they wanted
 * is gone.
 *
 * @param kept    ids to leave alone, newest first
 * @param expired ids retention no longer keeps, newest first
 */
public record RetentionDecision(List<UUID> kept, List<UUID> expired) {

    private static final RetentionDecision NOTHING = new RetentionDecision(List.of(), List.of());

    public RetentionDecision {
        kept = List.copyOf(kept);
        expired = List.copyOf(expired);
    }

    /** No snapshots, so nothing to decide. */
    public static RetentionDecision nothing() {
        return NOTHING;
    }

    /** Whether anything has to be written. */
    public boolean changesAnything() {
        return !expired.isEmpty();
    }

    public int keptCount() {
        return kept.size();
    }

    public int expiredCount() {
        return expired.size();
    }
}
