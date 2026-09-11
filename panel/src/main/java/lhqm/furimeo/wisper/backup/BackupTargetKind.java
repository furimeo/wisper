package lhqm.furimeo.wisper.backup;

/**
 * What a snapshot copies. Mirrors the {@code backup_target_kind_known} and
 * {@code restore_point_target_kind_known} CHECK constraints, value for value.
 *
 * <p>Explicit rather than inferred from which id is set, because the two need completely
 * different techniques - a filesystem copy taken while writes are paused, against a
 * logical dump run through the engine - and nothing about one generalises to the other.
 * The same distinction is {@code BackupTargetKind} on the wire.
 */
public enum BackupTargetKind {

    /** A directory on a node: {@code /var/lib/wisper/volumes/<service-id>/<volume-id>}. */
    VOLUME,

    /** One customer database on a shared or dedicated engine, dumped logically. */
    DATABASE;

    /** What a screen calls it. */
    public String label() {
        return this == VOLUME ? "Volume" : "Database";
    }
}
