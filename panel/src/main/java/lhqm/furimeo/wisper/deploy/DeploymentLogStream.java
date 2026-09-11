package lhqm.furimeo.wisper.deploy;

/**
 * Which stream a build log line came out of. Mirrors the
 * {@code deployment_log_stream_known} CHECK.
 *
 * <p>Kept apart because a browser colours them apart, and because "the build printed this
 * on stderr" and "the platform is telling you something" are different claims. A node's
 * compiler warnings are not the panel speaking.
 */
public enum DeploymentLogStream {

    /** The build process's standard output. */
    STDOUT,

    /** The build process's standard error. Most build tools log progress here. */
    STDERR,

    /**
     * The panel itself: which node was chosen, that a build started, that bytes were
     * dropped, why it failed.
     *
     * <p>These lines are what makes the log readable when the build never got far enough
     * to print anything of its own - a customer whose deployment failed at "no node has
     * room" should see that sentence in the log they are already looking at, not an empty
     * pane and a red pill.
     */
    SYSTEM
}
