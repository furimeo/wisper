package lhqm.furimeo.wisper.jobs;

import java.util.Objects;

/**
 * The name of a background job and the type of the payload it carries.
 *
 * <p>A db-scheduler task is identified by a string. A string is also what a typo looks
 * like, and a task enqueued under a name no {@code Task} bean answers to sits in
 * {@code scheduled_tasks} being logged as unresolved until it is swept - which is a job
 * that silently never runs. Declaring the pair once, as a constant next to the
 * {@code Task} bean that implements it, means the name is written exactly twice and both
 * writings are in the same file.
 *
 * <p>The payload type is here so {@link JobQueue} can be generic: the compiler then
 * refuses to enqueue a {@code BackupJob} against the deployment task, which is the class
 * of mistake that otherwise surfaces as a deserialisation failure inside a worker
 * thread at three in the morning.
 *
 * <h2>Naming</h2>
 *
 * <p>{@code <domain>-<verb>}, lower case, hyphenated: {@code deploy-run},
 * {@code backup-run}, {@code backup-prune}, {@code node-sweep-heartbeats},
 * {@code database-provision}, {@code stats-roll-up}. The prefix is the package that owns
 * the work, so an operator reading the table knows who to talk to.
 *
 * <p><strong>A task name is permanent.</strong> Renaming one orphans every row already
 * queued under the old name. If a job genuinely has to change shape, add a new name and
 * leave the old {@code Task} bean in place until the table has drained.
 *
 * @param taskName    the db-scheduler task name, matching the {@code Task} bean exactly
 * @param payloadType the class db-scheduler serialises into {@code task_data}
 */
public record JobKind<T>(String taskName, Class<T> payloadType) {

    private static final String NAME_SHAPE = "[a-z][a-z0-9]*(-[a-z0-9]+)+";

    public JobKind {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(payloadType, "payloadType");
        if (!taskName.matches(NAME_SHAPE)) {
            throw new IllegalArgumentException(
                    "Task name \"" + taskName + "\" must be <domain>-<verb> in lower case, "
                            + "for example \"deploy-run\". A task name is global and permanent.");
        }
    }

    /** The domain prefix, which is the package that owns the work. */
    public String domain() {
        return taskName.substring(0, taskName.indexOf('-'));
    }
}
