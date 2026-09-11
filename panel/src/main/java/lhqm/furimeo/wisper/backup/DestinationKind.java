package lhqm.furimeo.wisper.backup;

/**
 * Where snapshots are pushed. Mirrors {@code backup_destination_kind_known}.
 *
 * <p>The two are not variations on a theme. {@link #S3} is offsite and survives the node
 * burning down; {@link #LOCAL} is a directory on the very machine whose disk is the thing
 * most likely to fail, and is worth having only as a quick rollback point before a risky
 * change. The panel says which is which rather than letting a customer discover the
 * difference at the worst possible moment.
 */
public enum DestinationKind {

    /** Any S3-compatible object store: MinIO, Backblaze B2, Wasabi, AWS. */
    S3,

    /** A directory on the node that holds the data. Fast, and gone with the machine. */
    LOCAL;

    /** Whether losing the node loses the snapshots too. */
    public boolean isOnTheNode() {
        return this == LOCAL;
    }
}
