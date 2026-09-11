package lhqm.furimeo.wisper.backup;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The rows the tests in this package need, built with the defaults that make the case under
 * test the only interesting thing about them.
 *
 * <p>Not a mock factory. Everything here is a real record built through the same factory
 * methods production code uses, so a change to a constructor breaks these tests rather than
 * letting them keep asserting against a shape that no longer exists.
 */
final class BackupFixture {

    static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** Where the snapshot was taken. */
    static final UUID OLD_NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** Where the service lives now, after a migration. */
    static final UUID NEW_NODE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private BackupFixture() {
    }

    /** The platform defaults, which is what {@code @DefaultValue} would bind. */
    static BackupSettings settings() {
        return new BackupSettings(7, 4, 12, Duration.ofHours(2), Duration.ofHours(4), false,
                Duration.ofDays(14), Duration.ofHours(6), 200, 65_536, Duration.ofSeconds(15),
                true, "");
    }

    /** A nightly policy over a volume, keeping seven for thirty days. */
    static Backup nightlyVolumePolicy(UUID volumeId) {
        return Backup.forVolume(UUID.randomUUID(), ORGANIZATION, UUID.randomUUID(), "nightly",
                volumeId, "0 3 * * *", "UTC", 7, 30, Instant.parse("2026-03-01T03:00:00Z"));
    }

    /** A policy with the retention numbers a test wants and nothing else interesting. */
    static Backup policyKeeping(int retentionCount, int retentionDays, String timezone) {
        return Backup.forVolume(UUID.randomUUID(), ORGANIZATION, UUID.randomUUID(), "nightly",
                UUID.randomUUID(), null, timezone, retentionCount, retentionDays, null);
    }

    /** An S3 destination whose secret is already an envelope. */
    static BackupDestination s3Destination(String encryptedSecret) {
        return BackupDestination.s3(UUID.randomUUID(), ORGANIZATION, "offsite",
                "https://s3.example.com", "eu-central-1", "wisper-backups", "tenant-a/",
                "AKIAEXAMPLE", encryptedSecret, null, null);
    }

    /** A volume on {@code node}, ready to be copied. */
    static BackupTargetRef volumeOn(UUID nodeId, UUID volumeId, UUID serviceId) {
        return new BackupTargetRef(BackupTargetKind.VOLUME, volumeId, ORGANIZATION,
                UUID.randomUUID(), serviceId, nodeId, serviceId.toString(),
                "acme / api / data", null, null, true, true);
    }

    /** A finished snapshot of {@code volumeId}, taken on {@link #OLD_NODE}. */
    static RestorePoint availableSnapshot(UUID volumeId, UUID backupId) {
        Instant startedAt = Instant.parse("2026-02-01T03:00:00Z");
        return RestorePoint.running(UUID.randomUUID(), ORGANIZATION, backupId, UUID.randomUUID(),
                        OLD_NODE, BackupTargetKind.VOLUME, volumeId, null, "acme / api / data",
                        RestorePointTrigger.SCHEDULED, false, startedAt)
                .available("tenant-a/api/data/2026-02-01.tar.zst", 4096, "abc123",
                        startedAt.plusSeconds(60), startedAt.plusSeconds(2_592_000));
    }
}
