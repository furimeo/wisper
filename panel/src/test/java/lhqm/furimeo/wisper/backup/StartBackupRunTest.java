package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.org.QuotaAllowance;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.proto.v1.BackupCompleted;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * The backup job: decide what to copy, write the snapshot's row, hand the command over.
 *
 * <p>Two properties are worth a test rather than a comment. The first is that the id on the
 * wire is the {@code restore_point} id the panel minted, because that is what lets a result
 * be matched to a row instead of inventing one. The second is that <strong>every</strong>
 * reason a run does not happen ends up on the policy: a backup that silently did not run is
 * the failure a customer discovers three weeks later, when they need it.
 *
 * <p>The refusal cases are not symmetric, and the asymmetry is the subtle part. A missing
 * target is discovered by a plain read and can be written in the same transaction; a quota
 * refusal comes out of {@link RecordRestorePoint}, which is {@code @Transactional}, so by
 * the time it is caught the transaction is already rollback-only and anything written into
 * it is lost at commit. The test below is what keeps the second path from quietly becoming
 * the first again.
 *
 * <p>Over 300 lines and deliberately not split (AGENTS.md §3.2): one class under test, one
 * ten-collaborator wiring, and the refusal cases only make sense read against the happy path
 * they diverge from. The {@code @Nested} classes are the split.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StartBackupRunTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Mock
    private PlatformTransactionManager transactionManager;

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
    private CompleteBackup completions;

    @Captor
    private ArgumentCaptor<Backup> saved;

    @Captor
    private ArgumentCaptor<PanelMessage.Builder> messages;

    private StartBackupRun startBackupRun;

    private Backup policy;

    private BackupDestination destination;

    private UUID restorePointId;

    /**
     * Every transaction the template has opened, newest last.
     *
     * <p>A fresh {@link SimpleTransactionStatus} per call, and a {@code commit} that refuses a
     * rollback-only one, because that is the behaviour the class under test has to survive: a
     * participating {@code @Transactional} method that throws marks the transaction it joined,
     * and the commit afterwards fails rather than quietly writing nothing. A mock that
     * committed anything handed to it would let that whole class of bug through.
     */
    private final List<SimpleTransactionStatus> transactions = new ArrayList<>();

    @BeforeEach
    void aNightlyPolicyOverAPlacedVolume() {
        BackupSettings settings = BackupFixture.settings();
        RecordingCipher cipher = new RecordingCipher();
        startBackupRun = new StartBackupRun(transactionManager, backups, destinations, targets,
                new ReadDestinationCredentials(cipher, settings), restorePoints,
                new ComposeRunBackup(settings), nodes, completions, settings);

        given(transactionManager.getTransaction(any())).willAnswer(call -> {
            SimpleTransactionStatus status = new SimpleTransactionStatus();
            transactions.add(status);
            return status;
        });
        willAnswer(call -> {
            TransactionStatus status = call.getArgument(0);
            if (status.isRollbackOnly()) {
                throw new UnexpectedRollbackException("Transaction silently rolled back because "
                        + "it has been marked as rollback-only");
            }
            return null;
        }).given(transactionManager).commit(any());

        policy = BackupFixture.nightlyVolumePolicy(VOLUME);
        destination = BackupFixture.s3Destination(cipher.encrypt("secret-key"));
        restorePointId = UUID.randomUUID();

        given(backups.findById(policy.id())).willReturn(Optional.of(policy));
        given(backups.save(any())).willAnswer(call -> call.getArgument(0));
        given(destinations.findById(policy.destinationId())).willReturn(Optional.of(destination));
        given(targets.forPolicy(policy)).willReturn(Optional.of(
                BackupFixture.volumeOn(BackupFixture.NEW_NODE, VOLUME, SERVICE)));
        given(restorePoints.of(any(), any(), any(), any(), anyBoolean(), any()))
                .willAnswer(call -> RestorePoint.running(restorePointId,
                        BackupFixture.ORGANIZATION, policy.id(), destination.id(),
                        BackupFixture.NEW_NODE, BackupTargetKind.VOLUME, VOLUME, null,
                        "acme / api / data", call.getArgument(3), true, Instant.now()));
        given(nodes.call(any(), any())).willReturn(new CompletableFuture<>());
    }

    @Nested
    class WhatTheNodeIsAsked {

        @Test
        void isRunBackupCarryingTheRestorePointsOwnId() {
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(nodes).call(any(), messages.capture());
            PanelMessage message = messages.getValue().build();
            assertThat(message.hasRunBackup()).isTrue();
            assertThat(message.getRunBackup().getBackupId())
                    .isEqualTo(restorePointId.toString());
            assertThat(message.getRunBackup().getSubjectId()).isEqualTo(VOLUME.toString());
        }

        @Test
        void goesToTheNodeHoldingTheTargetNowAndNotToTheOneOnThePolicy() {
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(nodes).call(eq(BackupFixture.NEW_NODE), any());
        }

        @Test
        void carriesTheSameRetentionNumbersThePanelWillApplyItself() {
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(nodes).call(any(), messages.capture());
            RetentionPolicy expected = RetentionPolicy.of(policy, BackupFixture.settings());
            assertThat(messages.getValue().build().getRunBackup().getRetention())
                    .isEqualTo(ComposeRunBackup.ruleOf(expected));
        }

        @Test
        void marksThePolicyRunningBeforeTheCommandLeaves() {
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(backups).save(saved.capture());
            assertThat(saved.getValue().lastStatus()).isEqualTo(BackupRunStatus.RUNNING);
            assertThat(saved.getValue().lastRunAt()).isNotNull();
        }
    }

    @Nested
    class WhenTheRunCannotHappen {

        @Test
        void aQuotaRefusalIsWrittenOnThePolicyAndDoesNotEscapeTheJob() {
            // Exactly what a participating @Transactional method does when it throws: it
            // marks the transaction it joined rollback-only and then propagates. Recording
            // the reason inside that transaction would be discarded at commit, and the commit
            // itself would throw - which db-scheduler retries for ever with nothing on the
            // policy to say why.
            given(restorePoints.of(any(), any(), any(), any(), anyBoolean(), any()))
                    .willAnswer(call -> {
                        transactions.getLast().setRollbackOnly();
                        throw new QuotaExceeded(BackupFixture.ORGANIZATION,
                                QuotaResource.RESTORE_POINT, 1,
                                new QuotaAllowance(QuotaResource.RESTORE_POINT, 20, 20,
                                        QuotaAllowance.QuotaSource.PLAN));
                    });

            assertThatCode(() -> startBackupRun.run(BackupJob.scheduled(policy.id())))
                    .doesNotThrowAnyException();

            verify(backups).save(saved.capture());
            assertThat(saved.getValue().lastStatus()).isEqualTo(BackupRunStatus.FAILED);
            assertThat(saved.getValue().lastError()).contains("restore point");
            verify(nodes, never()).call(any(), any());
        }

        @Test
        void aTargetNobodyIsHoldingIsRefusedWithASentence() {
            given(targets.forPolicy(policy)).willReturn(Optional.of(
                    BackupFixture.volumeOn(null, VOLUME, SERVICE)));

            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(backups).save(saved.capture());
            assertThat(saved.getValue().lastStatus()).isEqualTo(BackupRunStatus.FAILED);
            assertThat(saved.getValue().lastError()).contains("no node");
            verify(nodes, never()).call(any(), any());
        }

        @Test
        void aTargetThatHasBeenDeletedIsRefusedRatherThanSentNowhere() {
            given(targets.forPolicy(policy)).willReturn(Optional.empty());

            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(backups).save(saved.capture());
            assertThat(saved.getValue().lastError()).contains("no longer exists");
            verify(nodes, never()).call(any(), any());
        }

        @Test
        void aDestinationThatWasSwitchedOffIsRefused() {
            given(destinations.findById(policy.destinationId()))
                    .willReturn(Optional.of(destination.enabled(false)));

            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(backups).save(saved.capture());
            assertThat(saved.getValue().lastError()).contains("switched off");
            verify(nodes, never()).call(any(), any());
        }

        @Test
        void aPolicySomebodyDeletedWhileTheJobWasQueuedIsSimplyDropped() {
            given(backups.findById(policy.id())).willReturn(Optional.empty());

            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(backups, never()).save(any());
            verify(nodes, never()).call(any(), any());
        }

        @Test
        void aNodeThatDroppedBetweenTheCommitAndTheSendClosesTheSnapshot() {
            given(nodes.call(any(), any())).willThrow(new NodeOffline(BackupFixture.NEW_NODE));

            startBackupRun.run(BackupJob.scheduled(policy.id()));

            verify(completions).failed(eq(restorePointId),
                    contains("could not be reached"));
        }
    }

    @Nested
    class WhenTheNodeAnswers {

        @Test
        void aResultCarryingASnapshotIsHandedToCompleteBackup() {
            CompletableFuture<CommandResult> answer = new CompletableFuture<>();
            given(nodes.call(any(), any())).willReturn(answer);
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            answer.complete(CommandResult.newBuilder()
                    .setOk(true)
                    .setBackup(BackupCompleted.newBuilder()
                            .setBackupId(restorePointId.toString())
                            .setSuccess(true)
                            .setLocation("tenant-a/api/data/2026-03-01.tar.zst")
                            .setSizeBytes(4096))
                    .build());

            verify(completions).accept(eq(restorePointId), any());
        }

        @Test
        void aRefusalWithNoSnapshotStillClosesTheRow() {
            CompletableFuture<CommandResult> answer = new CompletableFuture<>();
            given(nodes.call(any(), any())).willReturn(answer);
            startBackupRun.run(BackupJob.scheduled(policy.id()));

            answer.complete(CommandResult.newBuilder()
                    .setOk(false)
                    .setDetail("No such volume on this node.")
                    .build());

            verify(completions).failed(eq(restorePointId),
                    eq("No such volume on this node."));
        }
    }
}
