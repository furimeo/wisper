package lhqm.furimeo.wisper.deploy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The two background jobs {@code deploy} owns, and the names they answer to.
 *
 * <p>The {@link JobKind} constants sit next to the {@code Task} beans that implement them
 * so the task name is written exactly twice and both writings are in this file
 * (panel-ports.md §2.3). <strong>A task name is permanent.</strong> Renaming one orphans
 * every row already queued under the old name, and db-scheduler logs those as unresolved
 * until it sweeps them - a job that silently never runs.
 *
 * <p>Two jobs rather than one long-running one. {@code deploy-run} hands the work to a
 * node and returns in milliseconds; the build then takes minutes on the node, and the
 * worker thread is not sitting in it. {@code deploy-publish} is enqueued when the build
 * result comes back, which is what makes the promotion survive the panel restarting
 * between "the build succeeded" and "the symlink moved".
 *
 * <p>Neither handler throws for a deployment that failed: the use-cases mark the row
 * {@code FAILED} and return. An exception escaping here means the panel could not reach
 * its own database, and db-scheduler's retry is exactly the right answer to that.
 */
@Configuration
public class DeploymentTasks {

    /** Hand a queued deployment to a node. Enqueued by {@link StartDeployment}. */
    public static final JobKind<DeploymentJob> RUN =
            new JobKind<>("deploy-run", DeploymentJob.class);

    /**
     * Put a finished deployment in front of customers. Enqueued by
     * {@link CompleteDeployment} when a build succeeds, and by {@link BuildArtifact} for
     * an app, which has no build to wait for.
     */
    public static final JobKind<PublishJob> PUBLISH =
            new JobKind<>("deploy-publish", PublishJob.class);

    @Bean
    Task<DeploymentJob> deployRunTask(BuildArtifact buildArtifact) {
        return Tasks.oneTime(RUN.taskName(), DeploymentJob.class)
                .execute((instance, context) -> buildArtifact.run(instance.getData()));
    }

    @Bean
    Task<PublishJob> deployPublishTask(PublishRelease publishRelease) {
        return Tasks.oneTime(PUBLISH.taskName(), PublishJob.class)
                .execute((instance, context) -> publishRelease.run(instance.getData()));
    }
}
