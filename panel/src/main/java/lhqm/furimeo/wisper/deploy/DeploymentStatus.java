package lhqm.furimeo.wisper.deploy;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where one deployment is in its life, and the only moves it is allowed to make.
 *
 * <p>Mirrors the {@code deployment_status_known} CHECK exactly. The CHECK says which
 * values may be stored; this enum says which <em>sequences</em> of them are possible, and
 * that is the part a database cannot express. Every write goes through
 * {@link #transitionTo(DeploymentStatus)}, so a deployment can never go from
 * {@code SUCCEEDED} back to {@code BUILDING} because two callers raced, and a cancelled
 * build whose result arrives ten minutes late cannot resurrect itself.
 *
 * <h2>The names the design uses, and the names here</h2>
 *
 * <p>The design sketch (§5.5) names the states "queued, building, publishing, live,
 * failed, rolled-back". Two of those are not statuses:
 *
 * <ul>
 * <li><strong>live</strong> is {@link #SUCCEEDED} plus {@code deployment.is_current},
 *     which the {@code deployment_current_idx} partial unique index keeps to one row per
 *     service. A status would let two rows both claim to be live; an index cannot.</li>
 * <li><strong>rolled-back</strong> is not a state a deployment enters. Rolling back
 *     creates a <em>new</em> deployment whose {@code rolled_back_from_deployment_id}
 *     points at the one being undone, so the history reads forwards and "what is live"
 *     has exactly one answer.</li>
 * </ul>
 *
 * <p>Two statuses in the CHECK that the sketch does not mention earn their place:
 * {@link #ASSIGNED} is the window in which a node has been chosen and has not yet
 * answered, which is the difference between "waiting for a worker" and "waiting for a
 * machine"; {@link #SUPERSEDED} is what happens to a deployment still sitting in the
 * queue when a newer push overtakes it, and building it would burn a node's minutes on
 * a commit nobody will ever see.
 */
public enum DeploymentStatus {

    /** Accepted and written down. A worker has not picked it up yet. */
    QUEUED,

    /**
     * A node has been chosen and the work has been handed to it.
     *
     * <p>For a site the next stop is {@link #BUILDING}; for an app there is nothing to
     * build, so it goes straight to {@link #PUBLISHING}.
     */
    ASSIGNED,

    /** The node is cloning, installing and compiling. Sites only. */
    BUILDING,

    /**
     * The release is being put in front of customers: a symlink swap for a site, a spec
     * naming the new image for an app.
     *
     * <p>Deliberately not cancellable. Once the swap is under way, telling a customer it
     * was cancelled would be a claim about the world the panel cannot make good on.
     */
    PUBLISHING,

    /** It is out. Whether it is also <em>live</em> is {@code is_current}. */
    SUCCEEDED,

    /** It stopped somewhere and said why in {@code error_message}. */
    FAILED,

    /** Somebody stopped it before it finished. */
    CANCELLED,

    /** A newer deployment overtook it while it was still waiting. */
    SUPERSEDED;

    private static final Set<DeploymentStatus> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, CANCELLED, SUPERSEDED);

    /**
     * Whether this status may be followed by {@code next}.
     *
     * <p>Written as a switch over the source status rather than as a table of pairs so
     * that adding a status is a compile error here until somebody has decided what it
     * may become.
     */
    public boolean canTransitionTo(DeploymentStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case QUEUED -> next == ASSIGNED || next == CANCELLED || next == SUPERSEDED
                    || next == FAILED;
            case ASSIGNED -> next == BUILDING || next == PUBLISHING || next == CANCELLED
                    || next == FAILED;
            case BUILDING -> next == PUBLISHING || next == CANCELLED || next == FAILED;
            case PUBLISHING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED, CANCELLED, SUPERSEDED -> false;
        };
    }

    /**
     * {@code next}, if getting there from here is legal.
     *
     * @throws IllegalStatusTransition otherwise. Unchecked and loud: every caller of this
     *         is a use-case that has already decided what it wants to do, and a silently
     *         ignored transition is a deployment that stops moving with no explanation on
     *         any screen.
     */
    public DeploymentStatus transitionTo(DeploymentStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStatusTransition(this, next);
        }
        return next;
    }

    /** Whether nothing further can happen to a deployment in this status. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether the platform still owes this deployment work.
     *
     * <p>The same predicate as the {@code deployment_pending_idx} partial index, which is
     * the worker's queue view - so the index and this method cannot drift into disagreeing
     * about what "in flight" means.
     */
    public boolean isInFlight() {
        return !isTerminal();
    }

    /** Whether a customer may still stop it. */
    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }

    /** The word a status pill shows. */
    public String label() {
        return switch (this) {
            case QUEUED -> "Queued";
            case ASSIGNED -> "Assigned";
            case BUILDING -> "Building";
            case PUBLISHING -> "Publishing";
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            case SUPERSEDED -> "Superseded";
        };
    }
}
