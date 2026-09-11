package lhqm.furimeo.wisper.database;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The background job {@code database} owns, and the name it answers to.
 *
 * <p>The {@link JobKind} constant sits next to the {@code Task} bean that implements it so
 * the task name is written exactly twice and both writings are in this file
 * (panel-ports.md §2.3). <strong>A task name is permanent.</strong> Renaming it orphans
 * every row already queued under the old name, and db-scheduler logs those as unresolved
 * until it sweeps them - a job that silently never runs.
 *
 * <p>One job, not three. Provisioning is asynchronous because the very first database on a
 * node waits for an engine image to be pulled, and a customer holding an HTTP request open
 * for four minutes is a customer whose tunnel closes it. Rotating and dropping are not:
 * both are one statement inside a container that is already running, and a person is
 * watching the screen for the answer, so {@link RotateDatabasePassword} and
 * {@link DropDatabase} wait for the node themselves.
 *
 * <p>The handler never throws for a database that failed to provision:
 * {@link ProvisionOnNode} records the failure on the row and returns. An exception
 * escaping here would mean the panel could not reach its own database, and db-scheduler's
 * retry is exactly the right answer to that.
 */
@Configuration
public class DatabaseTasks {

    /**
     * Create one customer database on its node. Enqueued by {@link ProvisionDatabase} in
     * the same transaction that writes the row.
     */
    public static final JobKind<ProvisionJob> PROVISION =
            new JobKind<>("database-provision", ProvisionJob.class);

    @Bean
    Task<ProvisionJob> databaseProvisionTask(ProvisionOnNode provisionOnNode) {
        return Tasks.oneTime(PROVISION.taskName(), ProvisionJob.class)
                .execute((instance, context) -> provisionOnNode.run(instance.getData()));
    }
}
