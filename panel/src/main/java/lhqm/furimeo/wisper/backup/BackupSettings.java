package lhqm.furimeo.wisper.backup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about backups an operator might reasonably want to change.
 *
 * <p>Bound by {@code @ConfigurationPropertiesScan} on {@code WisperApplication}, so
 * nothing central had to be edited to add it (panel-configuration.md). Every component
 * carries a {@code @DefaultValue}: record binding does not fall back to a constructor
 * default, and a missing key would bind to zero - which for {@link #keepDaily} means a
 * retention rule that keeps nothing.
 *
 * <h2>Why the tier counts are here and not on the row</h2>
 *
 * <p>{@code backup} has two retention columns, {@code retention_count} and
 * {@code retention_days}, because those are the two a customer can reason about. The
 * daily/weekly/monthly thinning that turns "thirty days" into a sensible set of archives
 * rather than thirty of them is a platform decision, and it is bounded by the customer's
 * own {@code retention_days} in {@link RetentionPolicy}, so raising these can never keep a
 * snapshot longer than the customer asked for.
 *
 * @param keepDaily         at most this many daily archives, one per calendar day in the
 *                          policy's timezone
 * @param keepWeekly        at most this many weekly archives, one per ISO week
 * @param keepMonthly       at most this many monthly archives, one per calendar month
 * @param backupTimeout     what the node is told to give up after. A backup that runs into
 *                          the next scheduled one is how a node ends up doing nothing but
 *                          backups
 * @param restoreTimeout    the same for a restore, which reads rather than writes and is
 *                          allowed longer
 * @param verifyUploads     ask the node to read the archive back and check its checksum
 *                          before reporting success. Costs the download; buys knowing the
 *                          backup exists somewhere other than in a log line
 * @param safetyRetention   how long a {@code PRE_RESTORE} snapshot is kept. Long enough to
 *                          notice the restore was a mistake, short enough that a customer
 *                          who restores often is not paying to store every previous state
 * @param abandonedAfter    a run still in flight after this is declared lost. Its node
 *                          dropped the stream or the panel restarted; without this the row
 *                          holds its target against a second attempt for ever
 * @param sweepBatch        how many rows one sweep pass handles. The next pass takes the
 *                          rest a few seconds later
 * @param restoreLogLimit   characters kept in {@code restore_run.log}. A node that goes
 *                          wrong noisily must not be able to grow one column without bound
 * @param destinationCheckTimeout how long {@link CheckS3Bucket} waits for the object store
 * @param s3PathStyle       put the bucket in the path rather than the hostname. True by
 *                          default because the self-hosted stores this platform is
 *                          normally pointed at require it, and it cannot be guessed
 * @param serverSideEncryption the algorithm to ask the object store for, {@code AES256} or
 *                          empty for none. Independent of the archive passphrase, which
 *                          encrypts before the bytes leave the node
 */
@ConfigurationProperties("wisper.backup")
public record BackupSettings(
        @DefaultValue("7") int keepDaily,
        @DefaultValue("4") int keepWeekly,
        @DefaultValue("12") int keepMonthly,
        @DefaultValue("2h") Duration backupTimeout,
        @DefaultValue("4h") Duration restoreTimeout,
        @DefaultValue("false") boolean verifyUploads,
        @DefaultValue("14d") Duration safetyRetention,
        @DefaultValue("6h") Duration abandonedAfter,
        @DefaultValue("200") int sweepBatch,
        @DefaultValue("65536") int restoreLogLimit,
        @DefaultValue("15s") Duration destinationCheckTimeout,
        @DefaultValue("true") boolean s3PathStyle,
        @DefaultValue("") String serverSideEncryption) {

    public BackupSettings {
        requireAtLeast(keepDaily, 1, "keep-daily");
        requireAtLeast(keepWeekly, 0, "keep-weekly");
        requireAtLeast(keepMonthly, 0, "keep-monthly");
        requireAtLeast(sweepBatch, 1, "sweep-batch");
        requireAtLeast(restoreLogLimit, 1024, "restore-log-limit");
        requirePositive(backupTimeout, "backup-timeout");
        requirePositive(restoreTimeout, "restore-timeout");
        requirePositive(safetyRetention, "safety-retention");
        requirePositive(abandonedAfter, "abandoned-after");
        requirePositive(destinationCheckTimeout, "destination-check-timeout");
        serverSideEncryption = serverSideEncryption == null ? "" : serverSideEncryption.strip();
    }

    /** The backup deadline as the whole seconds {@code RunBackup} carries. */
    public long backupTimeoutSeconds() {
        return Math.max(1L, backupTimeout.toSeconds());
    }

    /** The restore deadline as the whole seconds {@code RestoreBackup} carries. */
    public long restoreTimeoutSeconds() {
        return Math.max(1L, restoreTimeout.toSeconds());
    }

    private static void requireAtLeast(int value, int minimum, String key) {
        if (value < minimum) {
            throw new IllegalArgumentException("wisper.backup." + key + " must be at least "
                    + minimum + ", not " + value);
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("wisper.backup." + key + " must be positive");
        }
    }
}
