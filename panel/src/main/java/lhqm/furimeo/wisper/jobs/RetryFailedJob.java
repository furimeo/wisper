package lhqm.furimeo.wisper.jobs;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Puts a failing job back at the front of the queue.
 *
 * <p>db-scheduler's failure handler pushes {@code execution_time} further out on every
 * failure, so a job on its fourteenth attempt may not be tried again for hours. When the
 * cause has been fixed - a node came back, a bucket's credentials were corrected - waiting
 * out that backoff is pointless, and this is the button that says so.
 *
 * <p>Rescheduling clears the failure streak as well as the time, which is deliberate: the
 * operator is asserting that the previous failures no longer describe the situation. If
 * they were wrong, the job fails again and the streak starts from one, which is the honest
 * count of "how many times has this failed since somebody last thought it was fixed".
 *
 * <p>Transactional so the reschedule and the audit entry are one act. The audit write is
 * {@code REQUIRES_NEW} and survives a rollback on purpose (panel-ports.md §2.4): an
 * attempted retry that failed is still worth knowing about.
 */
@Component
public class RetryFailedJob {

    private final SchedulerClient scheduler;
    private final ListFailedJobs jobs;
    private final AuditTrail audit;
    private final Clock clock;

    public RetryFailedJob(SchedulerClient scheduler, ListFailedJobs jobs, AuditTrail audit) {
        this(scheduler, jobs, audit, Clock.systemUTC());
    }

    RetryFailedJob(SchedulerClient scheduler, ListFailedJobs jobs, AuditTrail audit, Clock clock) {
        this.scheduler = scheduler;
        this.jobs = jobs;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Schedules the job for now.
     *
     * @param actor      the operator who pressed the button
     * @param taskName   the db-scheduler task name from the row
     * @param instanceId the instance id from the row
     */
    @Transactional
    public JobActionOutcome retry(AuditActor actor, String taskName, String instanceId) {
        FailedJob job = jobs.byId(taskName, instanceId);
        if (job == null) {
            return JobActionOutcome.GONE;
        }
        TaskInstanceId id = TaskInstanceId.of(taskName, instanceId);
        try {
            if (!scheduler.reschedule(id, Instant.now(clock))) {
                // The row was there a moment ago and is not now. Another panel or another
                // operator got to it first, which is not a failure of this request.
                return JobActionOutcome.GONE;
            }
        } catch (TaskInstanceNotFoundException gone) {
            return JobActionOutcome.GONE;
        } catch (TaskInstanceCurrentlyExecutingException running) {
            return JobActionOutcome.RUNNING;
        }
        audit.record(AuditEntry.succeeded(actor, "job.retry",
                AuditTarget.unidentified("scheduled_task", taskName + "/" + instanceId), null,
                "requeued after " + job.consecutiveFailures() + " consecutive failure(s)"));
        return JobActionOutcome.DONE;
    }
}
