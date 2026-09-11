package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
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

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The restore button: what it writes, and everything it refuses before writing anything.
 *
 * <p>Design §8.3 asks for restoring to be one press. That only works if the press either
 * starts something or explains itself, so every refusal here has a sentence a customer can
 * act on and none of them leaves a half-written run behind.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestoreFromPointTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Mock
    private RestorePointRepository points;

    @Mock
    private RestoreRunRepository runs;

    @Mock
    private ResolveBackupTarget targets;

    @Mock
    private JobQueue jobs;

    @Mock
    private AuditTrail audit;

    @Captor
    private ArgumentCaptor<AuditEntry> entries;

    private RestoreFromPoint restoreFromPoint;

    private RestorePoint snapshot;

    private Membership developer;

    @BeforeEach
    void anAvailableSnapshotOfAPlacedVolume() {
        restoreFromPoint = new RestoreFromPoint(points, runs, targets, jobs, audit);
        snapshot = BackupFixture.availableSnapshot(VOLUME, UUID.randomUUID());
        developer = new Membership(BackupFixture.ORGANIZATION, UUID.randomUUID(),
                MemberRole.DEVELOPER);

        given(points.findByIdAndOrganizationId(snapshot.id(), BackupFixture.ORGANIZATION))
                .willReturn(Optional.of(snapshot));
        given(targets.forSnapshot(snapshot)).willReturn(Optional.of(
                BackupFixture.volumeOn(BackupFixture.NEW_NODE, VOLUME, SERVICE)));
        given(runs.findActiveForVolume(VOLUME)).willReturn(Optional.empty());
        given(runs.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Nested
    class WhatOnePressWrites {

        @Test
        void aQueuedRunAgainstTheTargetTheSnapshotBelongsTo() {
            RestoreRun run = restoreFromPoint.start(actor(), developer, snapshot.id(),
                    RestoreMode.IN_PLACE, true);

            assertThat(run.state()).isEqualTo(RestoreState.QUEUED);
            assertThat(run.targetVolumeId()).isEqualTo(VOLUME);
            assertThat(run.targetManagedDatabaseId()).isNull();
            assertThat(run.mode()).isEqualTo(RestoreMode.IN_PLACE);
            assertThat(run.log()).contains("acme / api / data");
        }

        @Test
        void aJobKeyedOnTheRunSoOnePressIsOneRestore() {
            RestoreRun run = restoreFromPoint.start(actor(), developer, snapshot.id(),
                    RestoreMode.VERIFY, false);

            verify(jobs).enqueue(eq(BackupTasks.RESTORE), eq(run.id().toString()),
                    eq(new RestoreJob(run.id())));
        }

        @Test
        void anAuditEntryNamingTheNodeItWillRunOn() {
            restoreFromPoint.start(actor(), developer, snapshot.id(), RestoreMode.IN_PLACE, true);

            verify(audit).record(entries.capture());
            assertThat(entries.getValue().action()).isEqualTo("backup.restore");
            assertThat(entries.getValue().detail())
                    .contains(BackupFixture.NEW_NODE.toString())
                    .doesNotContain(BackupFixture.OLD_NODE.toString());
        }
    }

    @Nested
    class WhatItRefuses {

        @Test
        void anInPlaceRestoreWithNoConfirmation() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, snapshot.id(),
                            RestoreMode.IN_PLACE, false))
                    .withMessageContaining("replaces what is there now");
            verify(runs, never()).save(any());
        }

        @Test
        void averifyNeedsNoConfirmationBecauseItOverwritesNothing() {
            RestoreRun run = restoreFromPoint.start(actor(), developer, snapshot.id(),
                    RestoreMode.VERIFY, false);

            assertThat(run.mode()).isEqualTo(RestoreMode.VERIFY);
        }

        @Test
        void aSnapshotThatIsNotAvailable() {
            given(points.findByIdAndOrganizationId(snapshot.id(), BackupFixture.ORGANIZATION))
                    .willReturn(Optional.of(snapshot.expired()));

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, snapshot.id(),
                            RestoreMode.VERIFY, true))
                    .withMessageContaining("EXPIRED");
            verify(jobs, never()).enqueue(any(), any(), any());
        }

        @Test
        void aTargetNoNodeIsHolding() {
            given(targets.forSnapshot(snapshot)).willReturn(Optional.of(new BackupTargetRef(
                    BackupTargetKind.VOLUME, VOLUME, BackupFixture.ORGANIZATION,
                    UUID.randomUUID(), SERVICE, null, SERVICE.toString(), "acme / api / data",
                    null, null, true, true)));

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, snapshot.id(),
                            RestoreMode.VERIFY, true))
                    .withMessageContaining("no node to restore on");
        }

        @Test
        void aTargetThatHasBeenDeletedSaysSoRatherThanFailingLater() {
            given(targets.forSnapshot(snapshot)).willReturn(Optional.empty());

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, snapshot.id(),
                            RestoreMode.IN_PLACE, true))
                    .withMessageContaining("no longer exists");
        }

        @Test
        void asecondRestoreIntoATargetOneIsAlreadyRunningInto() {
            given(runs.findActiveForVolume(VOLUME)).willReturn(Optional.of(
                    RestoreRun.queued(UUID.randomUUID(), snapshot.id(), null, VOLUME, null,
                            RestoreMode.IN_PLACE, "queued")
                            .startedOn(BackupFixture.NEW_NODE, Instant.now())));

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, snapshot.id(),
                            RestoreMode.IN_PLACE, true))
                    .withMessageContaining("already running");
        }

        @Test
        void aViewer() {
            Membership viewer = new Membership(BackupFixture.ORGANIZATION, UUID.randomUUID(),
                    MemberRole.VIEWER);

            assertThatExceptionOfType(PermissionDenied.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), viewer, snapshot.id(),
                            RestoreMode.VERIFY, true));
            verify(runs, never()).save(any());
        }

        @Test
        void anotherTenantsSnapshotIsANotFoundAndNotAForbidden() {
            UUID theirs = UUID.randomUUID();
            given(points.findByIdAndOrganizationId(theirs, BackupFixture.ORGANIZATION))
                    .willReturn(Optional.empty());

            assertThatExceptionOfType(NotFoundException.class)
                    .isThrownBy(() -> restoreFromPoint.start(actor(), developer, theirs,
                            RestoreMode.VERIFY, true));
        }
    }

    private static AuditActor actor() {
        return AuditActor.system("test");
    }
}
