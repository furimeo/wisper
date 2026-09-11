package lhqm.furimeo.wisper.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The two background jobs {@code stats} owns, and the names they answer to.
 *
 * <p>The {@link JobKind} constants sit next to the {@code Task} beans that implement them,
 * so each name is written exactly twice and both writings are in this file
 * (panel-ports.md §2.3). <strong>A task name is permanent.</strong> Renaming one orphans
 * every row already queued under the old name.
 *
 * <p>Both are recurring and neither has a subject row, so there is nothing for them to be
 * idempotent against - exactly the distinction {@link lhqm.furimeo.wisper.jobs.JobQueue}
 * exists to keep. They are also both safe to run twice: the rollup upserts and the sweep
 * deletes what has already gone, so two panels racing produce the same table.
 *
 * <p>The cadences come from {@code wisper.stats} rather than being written here, because
 * they are the same numbers the retention windows are computed from and two copies of a
 * number is one copy that gets changed.
 */
@Configuration(proxyBeanMethods = false)
public class StatsTasks {

    /** Fold raw samples into buckets. */
    public static final JobKind<Void> ROLL_UP = new JobKind<>("stats-roll-up", Void.class);

    /** Delete readings past their retention. */
    public static final JobKind<Void> SWEEP_SAMPLES =
            new JobKind<>("stats-sweep-samples", Void.class);

    /**
     * Fixed delay rather than a cron. The work is "catch up to now", so a run that took
     * four minutes should not be followed immediately by another; what matters is the gap
     * between runs and not the wall clock they land on.
     */
    @Bean
    RecurringTask<Void> statsRollUpTask(RollUpSamples rollUp, StatsSettings settings) {
        return Tasks.recurring(ROLL_UP.taskName(),
                        Schedules.fixedDelay(settings.rollupInterval()))
                .execute((instance, context) -> rollUp.sweep());
    }

    @Bean
    RecurringTask<Void> statsSweepSamplesTask(PruneStatHistory prune, StatsSettings settings) {
        return Tasks.recurring(SWEEP_SAMPLES.taskName(),
                        Schedules.fixedDelay(settings.sweepInterval()))
                .execute((instance, context) -> prune.sweep());
    }
}
