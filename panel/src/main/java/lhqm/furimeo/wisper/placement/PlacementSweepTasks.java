package lhqm.furimeo.wisper.placement;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The housekeeping the {@code placement} package owns: finishing what a drain started.
 *
 * <p>Recurring rather than enqueued, because neither half has a subject row to be
 * idempotent against - which is the distinction {@link lhqm.furimeo.wisper.jobs.JobQueue}
 * exists to keep (panel-ports.md §2.3).
 *
 * <p>One task, two sweeps, and they are two halves of one move. {@link EvacuateDrainingNodes}
 * takes services off a machine an operator has marked draining;
 * {@link FinishDrainedPlacements} releases the old binding once the service is genuinely
 * gone from it. Running them in one task keeps them in order and keeps the second from
 * being a separate name in a table where a name is permanent.
 *
 * <p>Every thirty seconds. A drain is not urgent - nothing is broken while it is in
 * progress, the old copy is still serving - but it is something an operator is watching, so
 * a minute of apparently nothing happening is a minute they spend wondering whether they
 * clicked the button.
 */
@Configuration(proxyBeanMethods = false)
public class PlacementSweepTasks {

    /**
     * The task name, declared next to the bean that answers to it so it is written exactly
     * twice and both writings are in this file.
     *
     * <p>Not in the table in panel-ports.md §2.3, which predates this package having any
     * background work. The drain in {@code node} sets a lifecycle and talks to the daemon;
     * moving the placements is the panel-side half of the same operation, it cannot be a
     * call from {@code node} because that arrow is forbidden, and it must survive the
     * browser being closed. See {@link EvacuateDrainingNodes}.
     */
    public static final JobKind<Void> SWEEP_DRAINS =
            new JobKind<>("placement-sweep-drains", Void.class);

    private static final Logger log = LoggerFactory.getLogger(PlacementSweepTasks.class);

    private static final Duration EVERY_THIRTY_SECONDS = Duration.ofSeconds(30);

    @Bean
    RecurringTask<Void> placementSweepDrainsTask(EvacuateDrainingNodes evacuate,
                                                 FinishDrainedPlacements finish) {
        return Tasks.recurring(SWEEP_DRAINS.taskName(),
                        Schedules.fixedDelay(EVERY_THIRTY_SECONDS))
                .execute((instance, context) -> {
                    int moved = evacuate.sweep();
                    int released = finish.sweep();
                    if (moved > 0 || released > 0) {
                        log.info("Placement sweep: {} service(s) moved off a draining node, "
                                + "{} old binding(s) released", moved, released);
                    }
                });
    }
}
