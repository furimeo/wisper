package lhqm.furimeo.wisper.auth;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

/**
 * The two housekeeping jobs the {@code auth} package owns.
 *
 * <p>Declared here rather than by {@link lhqm.furimeo.wisper.jobs.JobQueue}, which is for
 * work enqueued in the transaction that justifies it. These two have no subject row and
 * nothing to be idempotent against; they are periodic sweeps, which is what db-scheduler's
 * recurring tasks are for (docs/contracts/panel-configuration.md).
 *
 * <p>Task names are global and permanent, and prefixed with the owning domain. Renaming
 * one orphans whatever is already in {@code scheduled_tasks} under the old name.
 *
 * <p>Hourly, not by the minute. Neither sweep is what makes anything safe - the request
 * path already refuses an expired session and an expired token - so running them often
 * would buy a fresher screen at the cost of two writes a minute on every deployment,
 * busy or idle.
 */
@Configuration(proxyBeanMethods = false)
public class AuthSweepTasks {

    private static final Duration EVERY_HOUR = Duration.ofHours(1);

    @Bean
    RecurringTask<Void> authExpireSessionsTask(ExpireStaleSessions expireStaleSessions) {
        return Tasks.recurring("auth-expire-sessions", Schedules.fixedDelay(EVERY_HOUR))
                .execute((instance, context) -> expireStaleSessions.run());
    }

    @Bean
    RecurringTask<Void> authExpireTokensTask(ExpireApiTokens expireApiTokens) {
        return Tasks.recurring("auth-expire-tokens", Schedules.fixedDelay(EVERY_HOUR))
                .execute((instance, context) -> expireApiTokens.run());
    }
}
