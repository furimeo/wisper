package lhqm.furimeo.wisper.jobs;

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
 * Removes a job that is never going to succeed.
 *
 * <p>The case this exists for: the row a job acts on was deleted, or the job was queued by
 * a version of the code that no longer exists. It will fail on every attempt forever, and
 * db-scheduler will keep retrying it forever, because backoff has no end.
 *
 * <p>Destructive and unrecoverable - there is no dead-letter table to move it to and
 * inventing one would be a second queue nobody watches - so the audit entry carries the
 * failure count and the task name, which is the only record that the work was abandoned
 * rather than done.
 *
 * <p>A recurring task discarded here comes back: db-scheduler recreates its row on the
 * next scheduler start, because a recurring task's schedule is code, not data. That is
 * usually what an operator wants and it is worth knowing before pressing the button.
 */
@Component
public class DiscardFailedJob {

    private final SchedulerClient scheduler;
    private final ListFailedJobs jobs;
    private final AuditTrail audit;

    public DiscardFailedJob(SchedulerClient scheduler, ListFailedJobs jobs, AuditTrail audit) {
        this.scheduler = scheduler;
        this.jobs = jobs;
        this.audit = audit;
    }

    @Transactional
    public JobActionOutcome discard(AuditActor actor, String taskName, String instanceId) {
        FailedJob job = jobs.byId(taskName, instanceId);
        if (job == null) {
            return JobActionOutcome.GONE;
        }
        try {
            scheduler.cancel(TaskInstanceId.of(taskName, instanceId));
        } catch (TaskInstanceNotFoundException gone) {
            return JobActionOutcome.GONE;
        } catch (TaskInstanceCurrentlyExecutingException running) {
            return JobActionOutcome.RUNNING;
        }
        audit.record(AuditEntry.succeeded(actor, "job.discard",
                AuditTarget.unidentified("scheduled_task", taskName + "/" + instanceId), null,
                "abandoned after " + job.consecutiveFailures() + " consecutive failure(s); "
                        + "the work was not done"));
        return JobActionOutcome.DONE;
    }
}
