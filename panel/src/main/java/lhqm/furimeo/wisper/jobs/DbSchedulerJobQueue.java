package lhqm.furimeo.wisper.jobs;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;

/**
 * The one implementation of {@link JobQueue}, and the only class in the panel that
 * imports {@code com.github.kagkarlsson}.
 *
 * <h2>Why the transaction check is the point</h2>
 *
 * <p>db-scheduler's Spring Boot starter wraps the application {@code DataSource} in a
 * {@code TransactionAwareDataSourceProxy} before it builds the {@code Scheduler}, so an
 * enqueue made while a Spring transaction is in progress uses that transaction's
 * connection and commits with it. That is the entire reason the queue is PostgreSQL: a
 * deployment row and the job that builds it are one commit, and neither can exist without
 * the other.
 *
 * <p>It also means an enqueue made <em>outside</em> a transaction quietly commits on its
 * own, which looks identical at the call site and is the bug this port exists to prevent.
 * Every method therefore refuses to run without one. The refusal is an
 * {@link IllegalStateException} and not a warning, because the failure it replaces - a job
 * that ran for a row that was rolled back - is only discoverable by reading two tables side
 * by side, weeks later.
 *
 * <h2>Why the client is looked up lazily</h2>
 *
 * <p>The {@code Scheduler} bean is built from every {@code Task} bean in the context, and
 * those tasks are built from the use-cases that enqueue - {@code deploy-run} needs
 * {@code BuildArtifact}, which needs this. Injecting the client directly closes that
 * circle and the context refuses to start. An {@link ObjectProvider} resolved on first use
 * breaks it: by the time anything enqueues, every bean exists.
 */
@Component
public class DbSchedulerJobQueue implements JobQueue {

    private static final Logger log = LoggerFactory.getLogger(DbSchedulerJobQueue.class);

    private final ObjectProvider<SchedulerClient> client;
    private final Clock clock;

    @Autowired
    public DbSchedulerJobQueue(ObjectProvider<SchedulerClient> client) {
        this(client, Clock.systemUTC());
    }

    DbSchedulerJobQueue(ObjectProvider<SchedulerClient> client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public <T> void enqueue(JobKind<T> kind, String instanceId, T payload) {
        enqueueAt(kind, instanceId, payload, Instant.now(clock));
    }

    @Override
    public <T> void enqueueAt(JobKind<T> kind, String instanceId, T payload, Instant when) {
        if (!enqueueIfAbsent(kind, instanceId, payload, when)) {
            throw new JobAlreadyQueued(kind.taskName(), instanceId);
        }
    }

    @Override
    public <T> boolean enqueueIfAbsent(JobKind<T> kind, String instanceId, T payload,
                                       Instant when) {
        TaskInstance<T> instance = instanceOf(kind, instanceId, payload);
        requireTransaction("enqueue " + instance.getTaskAndInstance());
        // A time in the past is a time now: db-scheduler picks up anything due, and
        // refusing would turn "retry immediately" into an exception at every call site
        // that computes a backoff from a clock that has already moved.
        Instant due = when == null ? Instant.now(clock) : when;
        boolean scheduled = scheduler().scheduleIfNotExists(instance, due);
        if (scheduled) {
            log.debug("Queued {} for {}", instance.getTaskAndInstance(), due);
        }
        return scheduled;
    }

    @Override
    public boolean cancel(JobKind<?> kind, String instanceId) {
        TaskInstanceId id = TaskInstanceId.of(kind.taskName(), requireInstanceId(instanceId));
        requireTransaction("cancel " + kind.taskName() + "/" + instanceId);
        try {
            scheduler().cancel(id);
            return true;
        } catch (TaskInstanceNotFoundException nothingQueued) {
            return false;
        } catch (TaskInstanceCurrentlyExecutingException running) {
            // A worker holds it. Deleting the row would leave a half-finished job nobody
            // is watching; stopping a running job means cancelling the work it is doing.
            log.debug("{}/{} is already running and was not cancelled", kind.taskName(),
                    instanceId);
            return false;
        }
    }

    private SchedulerClient scheduler() {
        SchedulerClient resolved = client.getIfAvailable();
        if (resolved == null) {
            throw new IllegalStateException("db-scheduler is not running. Jobs cannot be queued "
                    + "with db-scheduler.enabled=false, because a queued job nothing will ever "
                    + "pick up is worse than a failure here.");
        }
        return resolved;
    }

    /**
     * Refuses an enqueue that is not part of a write.
     *
     * <p>{@code isActualTransactionActive} rather than {@code isSynchronizationActive}: a
     * read-only transaction and a {@code @Transactional(readOnly = true)} method both
     * report synchronization, and neither is a write a job could belong to.
     */
    private static void requireTransaction(String what) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refusing to " + what + " outside a transaction. A "
                    + "job and the row that justifies it commit together or not at all; that is "
                    + "why this queue is PostgreSQL (panel-ports.md §2.3).");
        }
    }

    private static <T> TaskInstance<T> instanceOf(JobKind<T> kind, String instanceId, T payload) {
        if (payload != null && !kind.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException(kind.taskName() + " carries a "
                    + kind.payloadType().getSimpleName() + ", not a "
                    + payload.getClass().getSimpleName());
        }
        return new TaskInstance<>(kind.taskName(), requireInstanceId(instanceId), payload);
    }

    /**
     * An instance id is half the primary key of {@code scheduled_tasks}. A blank one makes
     * every job of that kind the same row, so the second enqueue silently replaces nothing
     * and the work is never done twice even when it should be.
     */
    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("A job needs an instance id. Use the id of the row "
                    + "it acts on, or JobQueue.uniqueInstanceId() for work with no subject.");
        }
        return instanceId;
    }
}
