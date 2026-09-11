package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies retention server-side: decides which snapshots a policy still keeps and marks the
 * rest {@code EXPIRED}.
 *
 * <h2>Why the panel does this at all, when the node also prunes</h2>
 *
 * <p>The node deletes the objects, because the node is the side holding the destination's
 * credentials, and it does so with the {@code RetentionRule} the panel put in the very
 * command that produced the archive. This class applies the <em>same</em> rule to the
 * panel's own rows, from the same {@link RetentionPolicy} derivation, so the list a
 * customer is shown and the objects that actually exist say the same thing.
 *
 * <p>They have to be two passes because they answer at different times. A node prunes only
 * when it runs a backup; a policy that has been disabled for a month, or whose target has
 * been deleted, never runs again - and its snapshots would otherwise stay listed as
 * restorable for ever.
 *
 * <h2>Expiring is not deleting</h2>
 *
 * <p>The row stays. "This snapshot existed and aged out on the third" is an answer; a
 * missing row is not, and the difference matters on the day somebody asks why the thing
 * they wanted to restore is not there.
 */
@Component
public class PruneByRetention {

    private static final Logger log = LoggerFactory.getLogger(PruneByRetention.class);

    private final TransactionTemplate transactions;
    private final BackupRepository backups;
    private final RestorePointRepository points;
    private final BackupSettings settings;

    public PruneByRetention(PlatformTransactionManager transactionManager,
                            BackupRepository backups, RestorePointRepository points,
                            BackupSettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.backups = backups;
        this.points = points;
        this.settings = settings;
    }

    /**
     * The whole platform, one policy at a time plus the snapshots that have no policy left.
     *
     * @return how many snapshots were expired
     */
    public int sweep() {
        int expired = 0;
        for (Backup policy : backups.findAllForRetention()) {
            Integer count = transactions.execute(status -> forPolicy(policy));
            expired += count == null ? 0 : count;
        }
        Integer orphans = transactions.execute(status -> expireOrphans(Instant.now()));
        return expired + (orphans == null ? 0 : orphans);
    }

    /**
     * One policy's snapshots.
     *
     * <p>Called by the sweep and, immediately, by {@link CompleteBackup} when a new
     * snapshot lands - so the list is right the moment the customer looks at it rather than
     * up to an hour later.
     *
     * @return how many were expired
     */
    @Transactional
    public int forPolicy(Backup policy) {
        List<RestorePoint> available = points.findAvailableForBackup(policy.id());
        if (available.isEmpty()) {
            return 0;
        }
        RetentionDecision decision = RetentionPolicy.of(policy, settings)
                .select(RetainedSnapshot.of(available));
        if (!decision.changesAnything()) {
            return 0;
        }
        Map<UUID, RestorePoint> byId = available.stream()
                .collect(Collectors.toMap(RestorePoint::id, Function.identity()));
        for (UUID id : decision.expired()) {
            RestorePoint point = byId.get(id);
            if (point != null) {
                points.save(point.expired());
            }
        }
        log.debug("Retention for policy {}: {} kept, {} expired", policy.id(),
                decision.keptCount(), decision.expiredCount());
        return decision.expiredCount();
    }

    /**
     * Snapshots that no longer belong to a policy: safety snapshots on their own clock, and
     * the leftovers of a policy somebody deleted.
     *
     * <p>These are the only rows an age rule may expire on its own. A snapshot that still
     * has a policy is decided by the tiering above, whose {@code keepLast} outranks age
     * deliberately.
     */
    @Transactional
    public int expireOrphans(Instant now) {
        List<RestorePoint> stale = points.findOrphansPastExpiry(now, settings.sweepBatch());
        for (RestorePoint point : stale) {
            points.save(point.expired());
        }
        return stale.size();
    }
}
