package lhqm.furimeo.wisper.deploy;

/**
 * A deployment was asked to move somewhere it cannot go from where it is.
 *
 * <p>Always a bug in the panel, never something a customer did: the buttons a screen
 * offers come from {@link DeploymentStatus#isCancellable()} and friends, and the jobs
 * check the status before they act. What produces this in practice is a race - a build
 * result arriving after the deployment was cancelled, two workers picking up the same
 * row - and in every one of those cases the honest answer is to stop, not to overwrite
 * whatever the other party decided.
 *
 * <p>Unchecked, because no caller can recover by doing something else with the same row.
 * Callers that expect a race - {@link CompleteDeployment} taking a late build result -
 * test the status first and return quietly instead of catching this.
 */
public class IllegalStatusTransition extends RuntimeException {

    private final DeploymentStatus from;
    private final DeploymentStatus to;

    public IllegalStatusTransition(DeploymentStatus from, DeploymentStatus to) {
        super("A deployment cannot go from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public DeploymentStatus from() {
        return from;
    }

    public DeploymentStatus to() {
        return to;
    }
}
