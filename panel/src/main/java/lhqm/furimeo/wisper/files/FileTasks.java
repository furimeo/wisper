package lhqm.furimeo.wisper.files;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The one background job {@code files} owns.
 *
 * <p>The {@link JobKind} constant sits next to the {@code Task} bean that implements it, so
 * the name is written exactly twice and both writings are in this file (panel-ports.md
 * §2.3). <strong>A task name is permanent.</strong>
 *
 * <p>Recurring and with no subject row, so there is nothing for it to be idempotent against.
 * It is safe to run twice: abandoning an upload the node has already forgotten is a no-op on
 * both sides.
 */
@Configuration(proxyBeanMethods = false)
public class FileTasks {

    /** Tell nodes to drop the parts of uploads nobody is continuing. */
    public static final JobKind<Void> SWEEP_UPLOADS =
            new JobKind<>("files-sweep-uploads", Void.class);

    @Bean
    RecurringTask<Void> filesSweepUploadsTask(SweepStaleUploads sweep, FilesSettings settings) {
        return Tasks.recurring(SWEEP_UPLOADS.taskName(),
                        Schedules.fixedDelay(settings.sweepInterval()))
                .execute((instance, context) -> sweep.sweep());
    }
}
