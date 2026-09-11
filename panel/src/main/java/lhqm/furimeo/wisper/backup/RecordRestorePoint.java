package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;

/**
 * Writes the {@code restore_point} row that a snapshot is about to fill in.
 *
 * <p>The row exists <strong>before</strong> the node is asked, and its id is what the node
 * is given as {@code RunBackup.backup_id}. Three things follow from that, and all three are
 * why it is done this way round:
 *
 * <ul>
 * <li>The result arrives keyed to a row that already exists, so {@link CompleteBackup} has
 *     somewhere to write it without inventing a row from a frame.</li>
 * <li>A command redelivered after a reconnect carries the same id and is therefore the same
 *     backup, not a second one.</li>
 * <li>A run that never comes back leaves a {@code RUNNING} row rather than nothing, which
 *     is the difference between "this failed" and a snapshot the customer thinks they have
 *     and does not.</li>
 * </ul>
 *
 * <p>The target label is copied from the target as it reads today and never recomputed. The
 * row has to stay readable after the volume, the service and the project it names have all
 * been deleted, because that is the situation a restore is usually needed in.
 */
@Component
public class RecordRestorePoint {

    private final RestorePointRepository points;
    private final QuotaGuard quotas;

    public RecordRestorePoint(RestorePointRepository points, QuotaGuard quotas) {
        this.points = points;
        this.quotas = quotas;
    }

    /**
     * @param policy          the policy this snapshot belongs to, or null when there is
     *                        none - a safety snapshot taken before restoring a snapshot
     *                        whose policy has since been deleted. A safety snapshot of a
     *                        live policy <em>is</em> attached to it, because the node
     *                        counts every archive of that subject together when it prunes
     *                        and a panel that counted them differently would list archives
     *                        the node had removed
     * @param encryptedAtRest whether the destination was asked to encrypt what it stores
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded if the organization is out of restore
     *         points, or is suspended. Not thrown for a {@code PRE_RESTORE} snapshot: see
     *         below
     */
    @Transactional
    public RestorePoint of(Backup policy, BackupTargetRef target, BackupDestination destination,
                           RestorePointTrigger trigger, boolean encryptedAtRest,
                           Instant startedAt) {
        UUID organizationId = target.organizationId();
        if (trigger != RestorePointTrigger.PRE_RESTORE) {
            quotas.require(organizationId, QuotaResource.RESTORE_POINT, 1);
        }
        // A safety snapshot is not optional. Refusing one on a count limit would mean
        // overwriting a customer's live data with no way back - which is a far worse
        // outcome than one snapshot over the line, and it expires on its own clock anyway.

        return points.save(RestorePoint.running(
                UUID.randomUUID(),
                organizationId,
                policy == null ? null : policy.id(),
                destination.id(),
                target.nodeId(),
                target.targetKind(),
                target.volumeId(),
                target.managedDatabaseId(),
                target.label(),
                trigger,
                encryptedAtRest,
                startedAt));
    }
}
