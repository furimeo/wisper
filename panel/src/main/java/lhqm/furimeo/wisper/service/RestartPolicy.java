package lhqm.furimeo.wisper.service;

/**
 * What the node does when an app's process exits. Mirrors the
 * {@code service_restart_policy_known} CHECK.
 *
 * <p>Three values, not Docker's five. {@code UNLESS_STOPPED} and {@code NO} both exist in
 * {@code RestartPolicyMode} on the wire, but the panel already models "the customer
 * stopped it" as {@link DesiredState#STOPPED} - a second way to say the same thing is a
 * second way for the two to disagree.
 *
 * <p>Meaningless for a {@link ServiceKind#SITE}, which has no process. The column keeps
 * its default there and nothing reads it.
 */
public enum RestartPolicy {

    /** Bring it back whenever it exits, however it exited. The default for a long-running app. */
    ALWAYS,

    /** Bring it back only on a non-zero exit. For a job that is supposed to finish. */
    ON_FAILURE,

    /** Leave it down. For something that runs once and is inspected afterwards. */
    NEVER;

    /** The sentence under the radio button. */
    public String label() {
        return switch (this) {
            case ALWAYS -> "Always restart";
            case ON_FAILURE -> "Restart only after a failure";
            case NEVER -> "Never restart";
        };
    }
}
