package lhqm.furimeo.wisper.jobs;

/**
 * A job with this task name and instance id is already in {@code scheduled_tasks}.
 *
 * <p>Thrown rather than swallowed because the two callers want opposite things and only
 * one of them can be the default. A customer pressing "deploy" twice should be told the
 * first one is already running; a poller that finds the same backup due twice should
 * shrug. The first gets {@link JobQueue#enqueue}, the second gets
 * {@link JobQueue#enqueueIfAbsent}, and neither has to guess what the other decided.
 */
public class JobAlreadyQueued extends RuntimeException {

    private final String taskName;
    private final String instanceId;

    public JobAlreadyQueued(String taskName, String instanceId) {
        super("Job " + taskName + "/" + instanceId + " is already queued");
        this.taskName = taskName;
        this.instanceId = instanceId;
    }

    public String taskName() {
        return taskName;
    }

    public String instanceId() {
        return instanceId;
    }
}
