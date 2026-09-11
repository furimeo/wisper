package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the deployment list, with the two things the table itself cannot answer.
 *
 * <p>{@code triggeredBy} is joined from {@code account}, because "9f3a-…" is not who
 * deployed this, and {@code rolledBackFromSequence} is joined from {@code deployment}
 * itself, because "#14 rolled back to #11" is the sentence and the id is not it. Every
 * other field is the row.
 *
 * <p>{@code commitSubject} is the first line only. A deployment list is one line per row,
 * and a commit whose message has six paragraphs in it must not be allowed to become six
 * paragraphs of the page.
 *
 * @param triggeredBy            the account's email, or null for a webhook, which has no
 *                               person behind it. The page shows the trigger instead
 * @param rolledBackFromSequence the release this one restored, or null if it restored none
 */
public record DeploymentSummary(
        UUID id,
        long sequence,
        DeploymentStatus status,
        DeploymentTrigger trigger,
        DeploymentSource source,
        String gitRef,
        String commitSha,
        String commitSubject,
        String commitAuthor,
        boolean current,
        UUID nodeId,
        String releasePath,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage,
        String triggeredBy,
        Long rolledBackFromSequence) {

    /** The seven characters a person recognises a commit by. */
    public String shortCommit() {
        return commitSha == null || commitSha.length() < 7 ? commitSha : commitSha.substring(0, 7);
    }

    /** Whether the platform still owes this deployment work, so the page can keep polling. */
    public boolean inFlight() {
        return status.isInFlight();
    }

    /** Whether the cancel button is offered. */
    public boolean cancellable() {
        return status.isCancellable();
    }

    /** Whether the rollback button is offered: it succeeded and something else is live. */
    public boolean rollbackTarget() {
        return status == DeploymentStatus.SUCCEEDED && !current;
    }
}
