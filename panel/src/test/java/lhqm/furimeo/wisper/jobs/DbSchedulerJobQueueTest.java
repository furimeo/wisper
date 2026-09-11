package lhqm.furimeo.wisper.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;

/**
 * The rule this port exists to enforce: a job is queued inside the transaction that writes
 * the row it acts on, or it is not queued at all.
 *
 * <p>The failure it prevents is invisible at the call site. db-scheduler's Spring starter
 * wraps the {@code DataSource} so an enqueue joins the ambient transaction - and an
 * enqueue with no ambient transaction commits on its own, which looks identical in the
 * source and produces a job for a row that was rolled back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbSchedulerJobQueueTest {

    private static final Instant NOW = Instant.parse("2026-04-02T08:00:00Z");
    private static final JobKind<UUID> RUN = new JobKind<>("deploy-run", UUID.class);
    private static final JobKind<Void> SWEEP = new JobKind<>("stats-roll-up", Void.class);

    @Mock
    private SchedulerClient scheduler;

    @Mock
    private ObjectProvider<SchedulerClient> provider;

    private DbSchedulerJobQueue queue() {
        given(provider.getIfAvailable()).willReturn(scheduler);
        return new DbSchedulerJobQueue(provider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void enqueueingWithoutATransactionIsRefusedBeforeAnythingIsWritten() {
        DbSchedulerJobQueue queue = queue();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> queue.enqueue(RUN, "deployment-1", UUID.randomUUID()))
                .withMessageContaining("outside a transaction");

        verify(scheduler, never()).scheduleIfNotExists(any(TaskInstance.class), any());
    }

    @Test
    void anEnqueueInsideATransactionIsScheduledForNow() {
        inTransaction();
        given(scheduler.scheduleIfNotExists(any(TaskInstance.class), any())).willReturn(true);
        UUID deploymentId = UUID.randomUUID();

        queue().enqueue(RUN, deploymentId.toString(), deploymentId);

        ArgumentCaptor<TaskInstance<UUID>> instance = captor();
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleIfNotExists(instance.capture(), when.capture());
        assertThat(instance.getValue().getTaskName()).isEqualTo("deploy-run");
        assertThat(instance.getValue().getId()).isEqualTo(deploymentId.toString());
        assertThat(instance.getValue().getData()).isEqualTo(deploymentId);
        assertThat(when.getValue()).isEqualTo(NOW);
    }

    @Test
    void aSecondEnqueueOfTheSameSubjectIsRefusedSoTwoClicksAreOneBuild() {
        inTransaction();
        given(scheduler.scheduleIfNotExists(any(TaskInstance.class), any())).willReturn(false);

        assertThatExceptionOfType(JobAlreadyQueued.class)
                .isThrownBy(() -> queue().enqueue(RUN, "deployment-1", UUID.randomUUID()));
    }

    @Test
    void theIdempotentFormShrugsInsteadOfThrowing() {
        inTransaction();
        given(scheduler.scheduleIfNotExists(any(TaskInstance.class), any())).willReturn(false);

        assertThat(queue().enqueueIfAbsent(SWEEP, "sweep", null, NOW)).isFalse();
    }

    @Test
    void aTimeInThePastIsATimeNowAndNotAnError() {
        inTransaction();
        given(scheduler.scheduleIfNotExists(any(TaskInstance.class), any())).willReturn(true);
        Instant longAgo = NOW.minusSeconds(3600);

        queue().enqueueAt(SWEEP, "sweep", null, longAgo);

        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleIfNotExists(any(TaskInstance.class), when.capture());
        assertThat(when.getValue()).isEqualTo(longAgo);
    }

    @Test
    void aBlankInstanceIdIsRefusedBecauseItIsHalfThePrimaryKey() {
        inTransaction();
        DbSchedulerJobQueue queue = queue();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> queue.enqueue(RUN, "  ", UUID.randomUUID()));
    }

    @Test
    void cancellingAJobAWorkerAlreadyHoldsReportsFalseRatherThanDeletingItsRow() {
        inTransaction();
        willThrow(new TaskInstanceCurrentlyExecutingException("deploy-run", "deployment-1"))
                .given(scheduler).cancel(any(TaskInstanceId.class));

        assertThat(queue().cancel(RUN, "deployment-1")).isFalse();
    }

    @Test
    void cancellingSomethingThatWasNeverQueuedIsFalseAndNotAFailure() {
        inTransaction();
        willThrow(new TaskInstanceNotFoundException("deploy-run", "deployment-1"))
                .given(scheduler).cancel(any(TaskInstanceId.class));

        assertThat(queue().cancel(RUN, "deployment-1")).isFalse();
    }

    @Test
    void cancellingAQueuedJobReportsTrue() {
        inTransaction();

        assertThat(queue().cancel(RUN, "deployment-1")).isTrue();
        verify(scheduler).cancel(any(TaskInstanceId.class));
    }

    @Test
    void cancellingWithoutATransactionIsRefusedToo() {
        DbSchedulerJobQueue queue = queue();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> queue.cancel(RUN, "deployment-1"));
        verify(scheduler, never()).cancel(any(TaskInstanceId.class));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<TaskInstance<UUID>> captor() {
        return ArgumentCaptor.forClass(TaskInstance.class);
    }
}
