package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.BackupCompleted;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.RestoreCompleted;

/**
 * The restore job, which is where the two hardest promises in this package are kept.
 *
 * <p>The first is that a restore follows the target's <strong>current</strong> placement.
 * {@code restore_point.node_id} says where the snapshot was taken; a service migrated since
 * then lives somewhere else, and restoring onto the machine named in the snapshot would put
 * a customer's data on a node that no longer runs their application - or on one that has
 * been retired, which fails with a message nobody can act on.
 *
 * <p>The second is the ordering: an in-place restore takes a snapshot of what is there now
 * and waits for it to succeed before anything is overwritten. If that snapshot fails, the
 * restore does not happen. That is what makes restoring the wrong snapshot - a mistake made
 * by people already under pressure - undoable.
 *
 * <p>Over 300 lines and deliberately not split (AGENTS.md §3.2): every case here drives the
 * same class through the same fourteen-collaborator wiring, and splitting the file would
 * duplicate that setup rather than separate two subjects. The {@code @Nested} classes are
 * the split.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunRestoreTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private RestoreRunRepository runs;

    @Mock
    private RestorePointRepository points;

    @Mock
    private BackupRepository backups;

    @Mock
    private BackupDestinationRepository destinations;

    @Mock
    private ResolveBackupTarget targets;

    @Mock
    private RecordRestorePoint restorePoints;

    @Mock
    private NodeConnections nodes;

    @Mock
    private CompleteBackup completeBackup;

    @Mock
    private CompleteRestore completeRestore;

    @Captor
    private ArgumentCaptor<PanelMessage.Builder> messages;

    @Captor
    private ArgumentCaptor<UUID> calledNodes;

    private RunRestore runRestore;

    private RestorePoint snapshot;

    private RestoreRun queuedInPlace;

    private UUID safetyPointId;

    @BeforeEach
    void aVolumeThatHasMovedNodeSinceItsSnapshotWasTaken() {
        BackupSettings settings = BackupFixture.settings();
        RecordingCipher cipher = new RecordingCipher();
        runRestore = new RunRestore(transactionManager, runs, points, backups, destinations,
                targets, new ReadDestinationCredentials(cipher, settings), restorePoints,
                new ComposeRunBackup(settings), new ComposeRestoreBackup(settings), nodes,
                completeBackup, completeRestore, settings);

        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());

        snapshot = BackupFixture.availableSnapshot(VOLUME, UUID.randomUUID());
        queuedInPlace = RestoreRun.queued(UUID.randomUUID(), snapshot.id(), UUID.randomUUID(),
                VOLUME, null, RestoreMode.IN_PLACE, "queued");

        given(points.findById(snapshot.id())).willReturn(Optional.of(snapshot));
        given(targets.forSnapshot(snapshot))
                .willReturn(Optional.of(BackupFixture.volumeOn(BackupFixture.NEW_NODE, VOLUME,
                        SERVICE)));
        BackupDestination destination =
                BackupFixture.s3Destination(cipher.encrypt("secret-key"));
        given(destinations.findById(snapshot.destinationId()))
                .willReturn(Optional.of(destination));
        given(runs.save(any())).willAnswer(call -> call.getArgument(0));

        safetyPointId = UUID.randomUUID();
        given(restorePoints.of(any(), any(), any(), eq(RestorePointTrigger.PRE_RESTORE),
                anyBoolean(), any()))
                .willAnswer(call -> RestorePoint.running(safetyPointId,
                        BackupFixture.ORGANIZATION, null, destination.id(),
                        BackupFixture.NEW_NODE, BackupTargetKind.VOLUME, VOLUME, null,
                        "acme / api / data", RestorePointTrigger.PRE_RESTORE, false,
                        Instant.now()));
    }

    @Nested
    class TheNodeItRunsOn {

        @Test
        void isWhereTheTargetLivesNowAndNotWhereTheSnapshotWasTaken() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any()))
                    .willReturn(completedBackup(safetyPointId))
                    .willReturn(completedRestore());

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, times(2)).call(calledNodes.capture(), any());
            assertThat(calledNodes.getAllValues())
                    .containsExactly(BackupFixture.NEW_NODE, BackupFixture.NEW_NODE);
            assertThat(snapshot.nodeId())
                    .as("the snapshot still records where it was taken")
                    .isEqualTo(BackupFixture.OLD_NODE);
        }

        @Test
        void theRestoreCommandNamesTheVolumeAndTheWorkloadTheTargetResolvedTo() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any()))
                    .willReturn(completedBackup(safetyPointId))
                    .willReturn(completedRestore());

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            PanelMessage restore = sentMessages().get(1);
            assertThat(restore.hasRestoreBackup()).isTrue();
            assertThat(restore.getRestoreBackup().getSubjectId()).isEqualTo(VOLUME.toString());
            assertThat(restore.getRestoreBackup().getWorkloadId()).isEqualTo(SERVICE.toString());
            // The location comes from the snapshot, so a destination reorganised since it
            // was taken does not lose the archive.
            assertThat(restore.getRestoreBackup().getLocation()).isEqualTo(snapshot.objectKey());
            assertThat(restore.getRestoreBackup().getStopWorkload()).isTrue();
            assertThat(restore.getRestoreBackup().getDryRun()).isFalse();
        }

        @Test
        void aTargetNobodyIsHoldingIsCancelledRatherThanSentNowhere() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(targets.forSnapshot(snapshot)).willReturn(Optional.of(
                    new BackupTargetRef(BackupTargetKind.VOLUME, VOLUME,
                            BackupFixture.ORGANIZATION, UUID.randomUUID(), SERVICE, null,
                            SERVICE.toString(), "acme / api / data", null, null, true, true)));

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, never()).call(any(), any());
            assertThat(savedRuns()).last()
                    .extracting(RestoreRun::state).isEqualTo(RestoreState.CANCELLED);
        }

        @Test
        void aTargetThatHasBeenDeletedIsCancelledWithAReasonOnTheRun() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(targets.forSnapshot(snapshot)).willReturn(Optional.empty());

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, never()).call(any(), any());
            RestoreRun last = savedRuns().get(savedRuns().size() - 1);
            assertThat(last.state()).isEqualTo(RestoreState.CANCELLED);
            assertThat(last.log()).contains("deleted");
        }
    }

    @Nested
    class TheSafetySnapshot {

        @Test
        void isTakenBeforeTheOverwriteAndTheRestoreWaitsForIt() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any()))
                    .willReturn(completedBackup(safetyPointId))
                    .willReturn(completedRestore());

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            List<PanelMessage> sent = sentMessages();
            assertThat(sent).hasSize(2);
            assertThat(sent.get(0).hasRunBackup()).isTrue();
            assertThat(sent.get(0).getRunBackup().getBackupId())
                    .isEqualTo(safetyPointId.toString());
            assertThat(sent.get(1).hasRestoreBackup()).isTrue();
            verify(completeBackup).accept(eq(safetyPointId), any());
            verify(completeRestore).accept(eq(queuedInPlace.id()), any());
        }

        @Test
        void isRecordedOnTheRunSoTheMistakeCanBeUndone() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any()))
                    .willReturn(completedBackup(safetyPointId))
                    .willReturn(completedRestore());

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            assertThat(savedRuns()).anyMatch(
                    run -> safetyPointId.equals(run.safetyRestorePointId()));
        }

        @Test
        void failingStopsTheRestoreAndNothingIsOverwritten() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any())).willReturn(failedBackup(safetyPointId));

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, times(1)).call(any(), any());
            assertThat(sentMessages().get(0).hasRunBackup()).isTrue();
            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(completeRestore).failed(eq(queuedInPlace.id()), reason.capture());
            assertThat(reason.getValue()).contains("Nothing was overwritten");
        }

        @Test
        void anUnreachableNodeStopsTheRestoreTheSameWay() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(nodes.call(any(), any())).willReturn(
                    CompletableFuture.failedFuture(new NodeOffline(BackupFixture.NEW_NODE)));

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, times(1)).call(any(), any());
            verify(completeBackup).failed(eq(safetyPointId), anyString());
            verify(completeRestore).failed(eq(queuedInPlace.id()), anyString());
        }

        @Test
        void isNotTakenForAVerifyBecauseAVerifyOverwritesNothing() {
            RestoreRun verifyRun = RestoreRun.queued(UUID.randomUUID(), snapshot.id(),
                    UUID.randomUUID(), VOLUME, null, RestoreMode.VERIFY, "queued");
            given(runs.findById(verifyRun.id())).willReturn(Optional.of(verifyRun));
            given(nodes.call(any(), any())).willReturn(completedRestore());

            runRestore.run(new RestoreJob(verifyRun.id()));

            verify(restorePoints, never()).of(any(), any(), any(), any(), anyBoolean(), any());
            List<PanelMessage> sent = sentMessages();
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).getRestoreBackup().getDryRun()).isTrue();
            assertThat(sent.get(0).getRestoreBackup().getStopWorkload()).isFalse();
        }
    }

    @Nested
    class RunsThatShouldNotStart {

        @Test
        void oneThatAWorkerAlreadyPickedUpIsLeftAlone() {
            RestoreRun running = queuedInPlace.startedOn(BackupFixture.NEW_NODE, Instant.now());
            given(runs.findById(running.id())).willReturn(Optional.of(running));

            runRestore.run(new RestoreJob(running.id()));

            verify(nodes, never()).call(any(), any());
            verify(runs, never()).save(any());
        }

        @Test
        void oneWhoseSnapshotIsNoLongerAvailableIsCancelled() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(points.findById(snapshot.id()))
                    .willReturn(Optional.of(snapshot.expired()));

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, never()).call(any(), any());
            assertThat(savedRuns()).last()
                    .extracting(RestoreRun::state).isEqualTo(RestoreState.CANCELLED);
        }

        @Test
        void oneWhoseDestinationHasBeenSwitchedOffIsCancelled() {
            given(runs.findById(queuedInPlace.id())).willReturn(Optional.of(queuedInPlace));
            given(destinations.findById(snapshot.destinationId())).willReturn(Optional.of(
                    BackupFixture.s3Destination(new RecordingCipher().encrypt("k"))
                            .enabled(false)));

            runRestore.run(new RestoreJob(queuedInPlace.id()));

            verify(nodes, never()).call(any(), any());
            assertThat(savedRuns()).last()
                    .extracting(RestoreRun::state).isEqualTo(RestoreState.CANCELLED);
        }
    }

    private List<PanelMessage> sentMessages() {
        verify(nodes, atLeastOnce()).call(any(), messages.capture());
        return messages.getAllValues().stream().map(PanelMessage.Builder::build).toList();
    }

    private List<RestoreRun> savedRuns() {
        ArgumentCaptor<RestoreRun> saved = ArgumentCaptor.forClass(RestoreRun.class);
        verify(runs, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private static CompletableFuture<CommandResult> completedBackup(UUID restorePointId) {
        return CompletableFuture.completedFuture(CommandResult.newBuilder()
                .setOk(true)
                .setBackup(BackupCompleted.newBuilder()
                        .setBackupId(restorePointId.toString())
                        .setSuccess(true)
                        .setLocation("tenant-a/pre-restore.tar.zst")
                        .setSizeBytes(2048))
                .build());
    }

    private static CompletableFuture<CommandResult> failedBackup(UUID restorePointId) {
        return CompletableFuture.completedFuture(CommandResult.newBuilder()
                .setOk(true)
                .setBackup(BackupCompleted.newBuilder()
                        .setBackupId(restorePointId.toString())
                        .setSuccess(false)
                        .setDetail("the volume was busy"))
                .build());
    }

    private static CompletableFuture<CommandResult> completedRestore() {
        return CompletableFuture.completedFuture(CommandResult.newBuilder()
                .setOk(true)
                .setRestore(RestoreCompleted.newBuilder()
                        .setSuccess(true)
                        .setBytesRestored(4096)
                        .setRestoredTo("/var/lib/wisper/volumes"))
                .build());
    }
}
