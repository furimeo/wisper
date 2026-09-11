package lhqm.furimeo.wisper.files;

/**
 * How much a directory tree holds.
 *
 * <p>Carries the root's quota alongside the measurement, because the number a customer
 * actually wants is the ratio. "4.2 GB" answers nothing on its own; "4.2 of 5 GB" is why
 * they opened the folder.
 *
 * @param approximate the node stopped at its walk budget. Shown, not hidden: a figure
 *                    labelled "at least" is usable and a wrong one presented as exact is
 *                    not
 * @param quotaBytes  the root's ceiling, or zero when it has none
 */
public record DirectorySizeView(String path, long bytes, long fileCount, long directoryCount,
                                boolean approximate, long quotaBytes) {

    /** How full the root is, in percent, or -1 when there is no quota to be full of. */
    public int percentOfQuota() {
        if (quotaBytes <= 0) {
            return -1;
        }
        return (int) Math.min(100, bytes * 100 / quotaBytes);
    }
}
