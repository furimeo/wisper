package lhqm.furimeo.wisper.domain;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The {@code domain-verify} job.
 *
 * <p>One task name, as panel-ports.md §2.3 fixes it, carrying a {@link DomainCheckJob} and
 * keyed by the domain's id - so a customer pressing "Check now" while a check is already
 * queued gets the queued one rather than a second lookup.
 *
 * <h2>Why it reschedules itself instead of a sweep re-enqueueing it</h2>
 *
 * <p>A hostname is checked, and if it did not verify it asks to be looked at again later.
 * Each domain therefore carries its own next attempt in {@code scheduled_tasks}, at a delay
 * that widens with the domain's age: a minute while somebody is watching the screen, then
 * every half hour, indefinitely, because a customer who adds a hostname on Friday and edits
 * DNS on Monday should come back to it already verified.
 *
 * <p>The alternative - a recurring sweep that enqueues one job per unverified domain - needs
 * a second task name, and the contract names one. Self-rescheduling is also the shape that
 * cannot fall behind: there is no window in which a domain exists and nothing is scheduled
 * for it.
 *
 * <p>A verified domain schedules nothing. Verification is not re-litigated (see
 * {@link VerifyDomainOwnership}), so the row is removed and the job is done.
 */
@Configuration(proxyBeanMethods = false)
public class DomainVerificationTasks {

    /**
     * The job panel-ports.md §2.3 names, declared next to the {@code Task} bean that answers
     * to it so the name is written exactly twice and both writings are in this file.
     */
    public static final JobKind<DomainCheckJob> VERIFY =
            new JobKind<>("domain-verify", DomainCheckJob.class);

    /**
     * How long to wait after the check itself threw.
     *
     * <p>Not the same thing as a check that ran and found the wrong answer: this is a bug or
     * a database that was unreachable, and repeating it in a minute would turn one failure
     * into a hot loop.
     */
    private static final Duration AFTER_AN_ERROR = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(DomainVerificationTasks.class);

    /**
     * A custom task rather than a one-time one, because the completion handler is the whole
     * point: {@code OnCompleteRemove} would delete the row and leave nothing scheduled, and
     * enqueueing the next attempt from inside the handler would collide with the execution
     * still holding that primary key.
     */
    @Bean
    CustomTask<DomainCheckJob> domainVerifyTask(VerifyDomainOwnership verification) {
        return Tasks.custom(VERIFY.taskName(), DomainCheckJob.class)
                .onFailureReschedule(Schedules.fixedDelay(AFTER_AN_ERROR))
                .execute((instance, context) -> {
                    DomainCheckJob job = instance.getData();
                    VerificationResult result = verification.check(job.domainId());
                    log.debug("Checked domain {}: {}", job.domainId(), result.state());
                    return completion(result);
                });
    }

    /**
     * Whether to come back, and when.
     *
     * <p>{@link CompletionHandler.OnCompleteRemove} is db-scheduler's own "this job is
     * finished" handler and is what a one-time task uses; rescheduling keeps the same row and
     * the same instance id, so the domain never has two checks queued at once.
     */
    private static CompletionHandler<DomainCheckJob> completion(VerificationResult result) {
        if (!result.shouldRecheck()) {
            return new CompletionHandler.OnCompleteRemove<>();
        }
        Duration wait = result.recheckIn();
        return (complete, operations) ->
                operations.reschedule(complete, Instant.now().plus(wait));
    }
}
