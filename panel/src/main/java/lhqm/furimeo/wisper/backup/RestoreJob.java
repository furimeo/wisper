package lhqm.furimeo.wisper.backup;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code restore-run} job: carry out this restore.
 *
 * <p>The run id, not the snapshot id. The run holds the mode, the target and the account
 * that asked, and it is also the row the screen behind the button is watching - so the job
 * and the progress report are about the same thing by construction.
 */
public record RestoreJob(UUID restoreRunId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public RestoreJob {
        if (restoreRunId == null) {
            throw new IllegalArgumentException("A restore job needs a run id");
        }
    }

    /** The db-scheduler instance id: the run, so one button press is one restore. */
    public String instanceId() {
        return restoreRunId.toString();
    }
}
