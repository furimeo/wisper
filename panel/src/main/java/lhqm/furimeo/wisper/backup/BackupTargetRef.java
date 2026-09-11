package lhqm.furimeo.wisper.backup;

import java.util.UUID;

/**
 * Everything a backup or a restore needs to know about the thing it is copying, resolved
 * at the moment it is needed.
 *
 * <p>Nothing here is stored. That is the point: a snapshot records where it was
 * <em>taken</em>, and a restore has to go wherever the target lives <em>now</em>. A
 * service can be migrated between the two, and following the snapshot's own
 * {@code node_id} would restore a customer's data onto a machine that no longer runs their
 * application - or onto one that has been retired, which fails in a way nobody can debug
 * from the message.
 *
 * @param nodeId       where the target is placed right now, or null when nothing is
 *                     holding it. Null is a real state - a service whose node was drained
 *                     and not yet replaced - and it is refused with a sentence rather than
 *                     a null pointer
 * @param workloadId   the id the node knows the owning workload by, which is the service
 *                     id. Empty for a database: a dump goes through the engine and has no
 *                     workload to pause
 * @param label        "project / service / volume" as it reads today. Copied onto the
 *                     snapshot at creation and never recomputed, because the row has to
 *                     stay readable after the target is deleted
 * @param engine       {@code POSTGRES} or {@code MYSQL}, null for a volume
 * @param databaseName the name on the engine, null for a volume
 * @param ready        whether the target is in a state worth copying. A volume the node
 *                     has never reported, or a database still provisioning, has nothing to
 *                     back up yet
 */
public record BackupTargetRef(
        BackupTargetKind targetKind,
        UUID subjectId,
        UUID organizationId,
        UUID projectId,
        UUID serviceId,
        UUID nodeId,
        String workloadId,
        String label,
        String engine,
        String databaseName,
        boolean backupEnabled,
        boolean ready) {

    public BackupTargetRef {
        workloadId = workloadId == null ? "" : workloadId;
        label = label == null ? "" : label;
    }

    /** Whether a node is currently holding this target. */
    public boolean isPlaced() {
        return nodeId != null;
    }

    /** The volume id, or null when this is a database. */
    public UUID volumeId() {
        return targetKind == BackupTargetKind.VOLUME ? subjectId : null;
    }

    /** The managed database id, or null when this is a volume. */
    public UUID managedDatabaseId() {
        return targetKind == BackupTargetKind.DATABASE ? subjectId : null;
    }

    /** One sentence saying why this target cannot be copied, or null when it can. */
    public String refusalReason() {
        if (!isPlaced()) {
            return "Nothing is running " + label + " at the moment, so there is no node to "
                    + "take a snapshot on. Wait for it to be placed and try again.";
        }
        if (!backupEnabled) {
            return "Backups are switched off for " + label + ".";
        }
        if (!ready) {
            return label + " is not ready yet. A node has to report it before it can be "
                    + "copied.";
        }
        return null;
    }
}
