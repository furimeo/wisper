package lhqm.furimeo.wisper.node;

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
 * The housekeeping the {@code node} package owns.
 *
 * <p>Recurring rather than enqueued, because neither piece of work has a subject row and
 * therefore nothing to be idempotent against - which is exactly the distinction
 * {@link lhqm.furimeo.wisper.jobs.JobQueue} exists to keep (panel-ports.md §2.3).
 *
 * <p>One task, two sweeps. panel-ports.md §2.3 names a single node job,
 * {@code node-sweep-heartbeats}, and a task name is permanent, so the token purge rides
 * along in it rather than claiming a second name that the contract does not list. They
 * belong together in practice anyway: both are "make the node screens tell the truth",
 * both are cheap, and both are pointless if the other has not run.
 *
 * <p>Every twenty seconds. The heartbeat window is sixty, and a sweep at the same cadence
 * would mean a node showing as connected for up to two minutes after it went away; a third
 * of the window is the usual compromise between a stale screen and a pointless query.
 */
@Configuration(proxyBeanMethods = false)
public class NodeSweepTasks {

    private static final Logger log = LoggerFactory.getLogger(NodeSweepTasks.class);

    /**
     * The one node job panel-ports.md §2.3 names. Declared next to the {@code Task} bean
     * that answers to it, so the name is written twice and both writings are in this file.
     */
    public static final JobKind<Void> SWEEP_HEARTBEATS =
            new JobKind<>("node-sweep-heartbeats", Void.class);

    private static final Duration EVERY_TWENTY_SECONDS = Duration.ofSeconds(20);

    @Bean
    RecurringTask<Void> nodeSweepHeartbeatsTask(MarkLostNodes markLostNodes,
                                                PurgeExpiredEnrolmentTokens purgeTokens) {
        return Tasks.recurring(SWEEP_HEARTBEATS.taskName(),
                        Schedules.fixedDelay(EVERY_TWENTY_SECONDS))
                .execute((instance, context) -> {
                    int corrected = markLostNodes.sweep();
                    int purged = purgeTokens.sweep();
                    if (corrected > 0 || purged > 0) {
                        log.info("Node sweep: {} node(s) marked disconnected, {} dead bootstrap "
                                + "token(s) deleted", corrected, purged);
                    }
                });
    }
}
