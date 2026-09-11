package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditOutcome;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.BackupCompleted;
import lhqm.furimeo.wisper.proto.v1.BackupStage;

/**
 * What happens to a snapshot's row when the node answers - and, as importantly, when the
 * same answer arrives twice.
 *
 * <p>Two paths reach this class for every backup: the {@code CommandResult} the panel is
 * waiting on, and the frame {@code grpc} routes here directly. Either can be first, both are
 * real, and a class that is not idempotent under that would double-count a customer's stored
 * bytes or resurrect a snapshot the timeout had already failed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompleteBackupTest {

    private static final Instant FINISHED = Instant.parse("2026-03-01T03:04:05Z");

    @Mock
    private RestorePointRepository points;

    @Mock
    private BackupRepository backups;

    @Mock
    private PruneByRetention pruning;

    @Mock
    private AuditTrail audit;

    @Captor
    private ArgumentCaptor<RestorePoint> saved;

    @Captor
    private ArgumentCaptor<AuditEntry> entries;

    private CompleteBackup completeBackup;

    private Backup policy;

    private RestorePoint running;

    @BeforeEach
    void aRunningSnapshotUnderANightlyPolicy() {
        completeBackup = new CompleteBackup(points, backups, pruning, BackupFixture.settings(),
                audit);
        policy = BackupFixture.nightlyVolumePolicy(UUID.randomUUID());
        running = RestorePoint.running(UUID.randomUUID(), BackupFixture.ORGANIZATION, policy.id(),
                UUID.randomUUID(), BackupFixture.NEW_NODE, BackupTargetKind.VOLUME,
                policy.volumeId(), null, "acme / api / data", RestorePointTrigger.SCHEDULED,
                false, FINISHED.minusSeconds(90));

        given(points.findById(running.id())).willReturn(Optional.of(running));
        given(backups.findById(policy.id())).willReturn(Optional.of(policy));
        given(points.save(any())).willAnswer(call -> call.getArgument(0));
        given(backups.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Nested
    class ASnapshotThatLanded {

        @Test
        void becomesAvailableWithEverythingARestoreNeeds() {
            completeBackup.accept(running.id(), succeeded()
                    .setLocation("tenant-a/api/data/2026-03-01.tar.zst")
                    .setSizeBytes(4_096)
                    .setSha256("deadbeef")
                    .build());

            verify(points).save(saved.capture());
            RestorePoint stored = saved.getValue();
            assertThat(stored.state()).isEqualTo(RestorePointState.AVAILABLE);
            assertThat(stored.objectKey()).isEqualTo("tenant-a/api/data/2026-03-01.tar.zst");
            assertThat(stored.sizeBytes()).isEqualTo(4_096);
            assertThat(stored.checksumSha256()).isEqualTo("deadbeef");
            assertThat(stored.finishedAt()).isEqualTo(FINISHED);
            assertThat(stored.isRestorable()).isTrue();
        }

        @Test
        void expiresOnThePolicysOwnClock() {
            completeBackup.accept(running.id(), succeeded().setLocation("k").build());

            verify(points).save(saved.capture());
            assertThat(saved.getValue().expiresAt())
                    .isEqualTo(FINISHED.plus(policy.retentionDays(), ChronoUnit.DAYS));
        }

        @Test
        void marksThePolicySucceededAndAppliesRetentionStraightAway() {
            completeBackup.accept(running.id(), succeeded().setLocation("k").build());

            ArgumentCaptor<Backup> policies = ArgumentCaptor.forClass(Backup.class);
            verify(backups).save(policies.capture());
            assertThat(policies.getValue().lastStatus()).isEqualTo(BackupRunStatus.SUCCEEDED);
            assertThat(policies.getValue().lastError()).isNull();
            // Immediately, not on the hourly sweep: the customer is looking at the list now.
            verify(pruning).forPolicy(any());
        }

        @Test
        void isAudited() {
            completeBackup.accept(running.id(),
                    succeeded().setLocation("k").setSizeBytes(10).setVerified(true).build());

            verify(audit).record(entries.capture());
            assertThat(entries.getValue().action()).isEqualTo("backup.run");
            assertThat(entries.getValue().outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(entries.getValue().detail()).contains("verified");
        }
    }

    @Nested
    class ASnapshotThatDidNot {

        @Test
        void successWithNoLocationIsAFailureBecauseNothingCouldRestoreFromIt() {
            completeBackup.accept(running.id(), succeeded().setLocation("").build());

            verify(points).save(saved.capture());
            assertThat(saved.getValue().state()).isEqualTo(RestorePointState.FAILED);
            assertThat(saved.getValue().errorMessage()).contains("did not say where");
        }

        @Test
        void aFailureNamesTheStageSoTheRightPersonLooksAtIt() {
            completeBackup.accept(running.id(), BackupCompleted.newBuilder()
                    .setBackupId(running.id().toString())
                    .setSuccess(false)
                    .setFailedStage(BackupStage.BACKUP_STAGE_UPLOAD)
                    .setDetail("connection reset by peer")
                    .build());

            verify(points).save(saved.capture());
            assertThat(saved.getValue().errorMessage())
                    .contains("during upload")
                    .contains("connection reset by peer");
        }

        @Test
        void marksThePolicyFailedAndPrunesNothing() {
            completeBackup.accept(running.id(),
                    BackupCompleted.newBuilder().setSuccess(false).build());

            ArgumentCaptor<Backup> policies = ArgumentCaptor.forClass(Backup.class);
            verify(backups).save(policies.capture());
            assertThat(policies.getValue().lastStatus()).isEqualTo(BackupRunStatus.FAILED);
            verify(pruning, never()).forPolicy(any());
        }

        @Test
        void aTimeoutClosesTheRowThroughTheOtherDoor() {
            completeBackup.failed(running.id(), "The node did not report a result: timeout");

            verify(points).save(saved.capture());
            assertThat(saved.getValue().state()).isEqualTo(RestorePointState.FAILED);
            verify(audit).record(entries.capture());
            assertThat(entries.getValue().outcome()).isEqualTo(AuditOutcome.FAILED);
        }
    }

    @Nested
    class ArrivingTwice {

        @Test
        void theSecondResultForAFinishedSnapshotChangesNothing() {
            RestorePoint alreadyAvailable = running.available("k", 1, "x", FINISHED, FINISHED);
            given(points.findById(running.id())).willReturn(Optional.of(alreadyAvailable));

            completeBackup.accept(running.id(), succeeded().setLocation("k").build());

            verify(points, never()).save(any());
            verifyNoInteractions(pruning);
        }

        @Test
        void aFailureAfterTheRowIsGoneIsIgnoredRatherThanThrown() {
            UUID vanished = UUID.randomUUID();
            given(points.findById(vanished)).willReturn(Optional.empty());

            completeBackup.failed(vanished, "whatever");

            verify(points, never()).save(any());
        }
    }

    @Nested
    class ASafetySnapshotWithNoPolicy {

        @Test
        void expiresOnThePlatformsSafetyClockInstead() {
            RestorePoint orphan = RestorePoint.running(UUID.randomUUID(),
                    BackupFixture.ORGANIZATION, null, UUID.randomUUID(), BackupFixture.NEW_NODE,
                    BackupTargetKind.VOLUME, UUID.randomUUID(), null, "acme / api / data",
                    RestorePointTrigger.PRE_RESTORE, false, FINISHED.minusSeconds(30));
            given(points.findById(orphan.id())).willReturn(Optional.of(orphan));

            completeBackup.accept(orphan.id(), BackupCompleted.newBuilder()
                    .setBackupId(orphan.id().toString())
                    .setSuccess(true)
                    .setLocation("tenant-a/pre-restore.tar.zst")
                    .setSizeBytes(1)
                    .setFinishedAt(timestamp(FINISHED))
                    .build());

            verify(points).save(saved.capture());
            assertThat(saved.getValue().expiresAt())
                    .isEqualTo(FINISHED.plus(BackupFixture.settings().safetyRetention()));
            verify(backups, never()).save(any());
            verify(pruning, never()).forPolicy(any());
        }
    }

    private BackupCompleted.Builder succeeded() {
        return BackupCompleted.newBuilder()
                .setBackupId(running.id().toString())
                .setSuccess(true)
                .setFinishedAt(timestamp(FINISHED));
    }

    private static Timestamp timestamp(Instant at) {
        return Timestamp.newBuilder()
                .setSeconds(at.getEpochSecond())
                .setNanos(at.getNano())
                .build();
    }
}
