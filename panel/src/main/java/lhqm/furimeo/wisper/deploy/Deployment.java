package lhqm.furimeo.wisper.deploy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One attempt to put a new version of a service in front of customers. Maps the
 * {@code deployment} table, column for column.
 *
 * <p>Every state change is a method on this record and every one of them goes through
 * {@link DeploymentStatus#transitionTo}, so there is no path that writes a status without
 * the state machine having agreed to it. That is the whole reason the transitions live
 * here rather than in the use-cases: eight use-cases each writing {@code status} directly
 * is eight chances to write the one that cannot happen.
 *
 * <p>Two columns are <strong>not</strong> the panel's to invent: {@code release_path} and
 * {@code image_digest} are facts the node reports (schema.md §2), and the only method
 * that sets them is {@link #withBuildOutput}, called from {@link CompleteDeployment} with
 * a {@code BuildCompleted} in hand. Every other transition copies them through unchanged.
 *
 * @param sequence the number the customer sees, per service, starting at 1
 * @param isCurrent whether this is what is serving traffic. At most one per service, kept
 *                  so by the {@code deployment_current_idx} partial unique index rather
 *                  than by a status value, because an index can enforce it and a status
 *                  cannot
 * @param rolledBackFromDeploymentId set on the row a rollback creates, pointing at the
 *                  deployment being undone, so the history reads forwards
 * @param version   null means new; Spring Data JDBC needs it to tell an insert of an
 *                  application-assigned id from an update of a row that does not exist
 */
public record Deployment(
        @Id UUID id,
        UUID serviceId,
        UUID nodeId,
        long sequence,
        DeploymentTrigger trigger,
        UUID triggeredByAccountId,
        DeploymentSource source,
        DeploymentStatus status,

        String gitRef,
        String commitSha,
        String commitMessage,
        String commitAuthor,
        String archivePath,
        String imageDigest,
        String releasePath,

        boolean isCurrent,
        UUID rolledBackFromDeploymentId,

        Instant queuedAt,
        Instant assignedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A commit subject longer than this is cut; the deployment list is one line per row. */
    private static final int COMMIT_MESSAGE_LIMIT = 500;

    /**
     * A fresh deployment, queued, with nothing decided about where it will run.
     *
     * @param sequence already allocated as {@code max(sequence) + 1} for this service,
     *                 inside the transaction that will insert this row
     */
    public static Deployment queued(UUID id, UUID serviceId, long sequence,
                                    DeploymentTrigger trigger, UUID triggeredByAccountId,
                                    DeploymentSource source, GitRevision revision,
                                    String archivePath, UUID rolledBackFrom, Instant at) {
        GitRevision git = revision == null ? GitRevision.unknown() : revision;
        return new Deployment(id, serviceId, null, sequence, trigger, triggeredByAccountId,
                source, DeploymentStatus.QUEUED,
                git.ref(), git.commitSha(), trim(git.commitMessage()), git.commitAuthor(),
                archivePath, null, null,
                false, rolledBackFrom,
                at, null, null, null, null, null,
                null, null, null);
    }

    /** Handed to a node. The node id is panel intent: it is where the work was sent. */
    public Deployment assignedTo(UUID node, Instant at) {
        return moved(status.transitionTo(DeploymentStatus.ASSIGNED), node, at, startedAt,
                finishedAt, durationMs, errorMessage, isCurrent);
    }

    /** The node has started cloning and compiling. Sites only. */
    public Deployment building(Instant at) {
        return moved(status.transitionTo(DeploymentStatus.BUILDING), nodeId, assignedAt, at,
                finishedAt, durationMs, errorMessage, isCurrent);
    }

    /**
     * Going live: the symlink is swapping, or a spec naming the new image is on its way.
     *
     * <p>An app arrives here straight from {@link DeploymentStatus#ASSIGNED} and has
     * therefore never had a start time, so this sets one. A site keeps the one
     * {@link #building} recorded, because a customer asking how long a deploy took means
     * the build.
     */
    public Deployment publishing(Instant at) {
        return moved(status.transitionTo(DeploymentStatus.PUBLISHING), nodeId, assignedAt,
                startedAt == null ? at : startedAt, finishedAt, durationMs, errorMessage,
                isCurrent);
    }

    /** It is out. Whether it is also live is {@link #promotedToCurrent()}. */
    public Deployment succeeded(Instant at) {
        return finished(DeploymentStatus.SUCCEEDED, at, null);
    }

    /** It stopped, and {@code message} is what a customer is shown about why. */
    public Deployment failed(String message, Instant at) {
        return finished(DeploymentStatus.FAILED, at,
                message == null || message.isBlank() ? "The deployment failed." : message);
    }

    /** Somebody stopped it before it finished. */
    public Deployment cancelled(String reason, Instant at) {
        return finished(DeploymentStatus.CANCELLED, at, reason);
    }

    /** A newer deployment overtook it while it was still in the queue. */
    public Deployment superseded(long bySequence, Instant at) {
        return finished(DeploymentStatus.SUPERSEDED, at,
                "Overtaken by deployment #" + bySequence + " before this one started.");
    }

    /**
     * What the node produced, taken from a {@code BuildCompleted}.
     *
     * <p>Almost the only writer of {@code release_path} and {@code image_digest}, which
     * are the node's columns and not the panel's (schema.md §2). The other is
     * {@link RollbackRelease}, and it is not an exception to the rule: it copies both
     * values from the deployment being restored, so the panel is pointing at a fact the
     * node reported rather than inventing one. {@code deployment.image_digest} exists for
     * precisely that - "the digest actually deployed, so a rollback restarts the same
     * bytes" (V16).
     *
     * <p>The commit fields are overwritten too: a build of a branch resolves to a commit
     * that was not knowable when the row was written, and the resolved one is the truth.
     */
    public Deployment withBuildOutput(String release, String digest, String resolvedCommit,
                                      String resolvedMessage) {
        return new Deployment(id, serviceId, nodeId, sequence, trigger, triggeredByAccountId,
                source, status,
                gitRef,
                blankToNull(resolvedCommit) == null ? commitSha : resolvedCommit,
                blankToNull(resolvedMessage) == null ? commitMessage : trim(resolvedMessage),
                commitAuthor, archivePath,
                blankToNull(digest) == null ? imageDigest : digest,
                blankToNull(release) == null ? releasePath : release,
                isCurrent, rolledBackFromDeploymentId,
                queuedAt, assignedAt, startedAt, finishedAt, durationMs, errorMessage,
                createdAt, updatedAt, version);
    }

    /** Where the panel parked the uploaded zip, for the deploy-a-zip path. */
    public Deployment withArchiveAt(String path) {
        return new Deployment(id, serviceId, nodeId, sequence, trigger, triggeredByAccountId,
                source, status, gitRef, commitSha, commitMessage, commitAuthor, path,
                imageDigest, releasePath, isCurrent, rolledBackFromDeploymentId, queuedAt,
                assignedAt, startedAt, finishedAt, durationMs, errorMessage, createdAt,
                updatedAt, version);
    }

    /**
     * This is what is serving traffic now.
     *
     * @throws IllegalStatusTransition if it has not succeeded, which is the same rule the
     *         {@code deployment_current_must_have_succeeded} CHECK enforces - caught here
     *         so the failure names the mistake instead of arriving as a constraint
     */
    public Deployment promotedToCurrent() {
        if (status != DeploymentStatus.SUCCEEDED) {
            throw new IllegalStatusTransition(status, DeploymentStatus.SUCCEEDED);
        }
        return moved(status, nodeId, assignedAt, startedAt, finishedAt, durationMs,
                errorMessage, true);
    }

    /** Something else took over. The row stays; only the flag moves. */
    public Deployment retiredFromCurrent() {
        return moved(status, nodeId, assignedAt, startedAt, finishedAt, durationMs,
                errorMessage, false);
    }

    /** Whether the platform still owes this deployment work. */
    public boolean isInFlight() {
        return status.isInFlight();
    }

    /** Whether a customer may still stop it. */
    public boolean isCancellable() {
        return status.isCancellable();
    }

    /** Whether rolling back to this is meaningful: it succeeded and is not already live. */
    public boolean isRollbackTarget() {
        return status == DeploymentStatus.SUCCEEDED && !isCurrent;
    }

    /** How long it took, once it is over. */
    public Duration duration() {
        return durationMs == null ? Duration.ZERO : Duration.ofMillis(durationMs);
    }

    /** The first line of the commit subject, for a list that has one line per row. */
    public String commitSubject() {
        if (commitMessage == null) {
            return "";
        }
        int newline = commitMessage.indexOf('\n');
        return newline < 0 ? commitMessage : commitMessage.substring(0, newline);
    }

    /** The seven characters a person recognises a commit by. */
    public String shortCommit() {
        return commitSha == null || commitSha.length() < 7 ? commitSha : commitSha.substring(0, 7);
    }

    private Deployment finished(DeploymentStatus next, Instant at, String message) {
        Instant from = startedAt != null ? startedAt : queuedAt;
        long millis = from == null || at == null ? 0L : Duration.between(from, at).toMillis();
        return moved(status.transitionTo(next), nodeId, assignedAt, startedAt, at,
                Math.max(millis, 0L), message, next == DeploymentStatus.SUCCEEDED && isCurrent);
    }

    private Deployment moved(DeploymentStatus next, UUID node, Instant assigned, Instant started,
                             Instant ended, Long millis, String message, boolean current) {
        return new Deployment(id, serviceId, node, sequence, trigger, triggeredByAccountId,
                source, next, gitRef, commitSha, commitMessage, commitAuthor, archivePath,
                imageDigest, releasePath, current, rolledBackFromDeploymentId, queuedAt,
                assigned, started, ended, millis, message, createdAt, updatedAt, version);
    }

    private static String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= COMMIT_MESSAGE_LIMIT
                ? message
                : message.substring(0, COMMIT_MESSAGE_LIMIT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
