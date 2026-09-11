package lhqm.furimeo.wisper.deploy;

/**
 * A push, as both Git providers describe it once their differences are stripped off.
 *
 * <p>GitHub and GitLab disagree about nearly every field name and agree about every fact,
 * so each provider's class produces one of these and everything downstream is written
 * once. The alternative - two paths into {@link StartDeployment} - is two places for the
 * branch filter to be forgotten.
 *
 * @param ref           the full ref, {@code refs/heads/main} or {@code refs/tags/v2.1}
 * @param commitSha     the commit the branch now points at. Empty on a delete
 * @param commitMessage the head commit's message, for the deployment list
 * @param author        whoever the provider names as the author, shown as-is
 * @param deleted       the branch or tag was removed. There is nothing to deploy, and
 *                      building the commit before it would publish what was just deleted
 */
public record PushEvent(String ref, String commitSha, String commitMessage, String author,
                        boolean deleted) {

    /** What a deleted ref's commit sha looks like in both providers' payloads. */
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    public PushEvent {
        ref = ref == null ? "" : ref.strip();
        commitSha = normaliseSha(commitSha);
        commitMessage = commitMessage == null ? "" : commitMessage.strip();
        author = author == null ? "" : author.strip();
        deleted = deleted || commitSha.isEmpty();
    }

    /**
     * The branch or tag name without its {@code refs/heads/} or {@code refs/tags/}
     * prefix, which is what a customer typed into the service's branch field.
     */
    public String shortRef() {
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    /** Whether this push says anything worth deploying. */
    public boolean isDeployable() {
        return !deleted && !ref.isEmpty();
    }

    /** The four Git columns of the deployment this push will produce. */
    public GitRevision revision() {
        return new GitRevision(shortRef(), commitSha, commitMessage, author);
    }

    /**
     * Whether this push should reach a service configured for {@code branch}.
     *
     * <p>A service with no branch set takes any push, which is the right default for the
     * repository that has one branch. A service with one set takes only that ref, so a
     * feature branch cannot publish itself over production.
     */
    public boolean matches(String branch) {
        return branch == null || branch.isBlank() || branch.strip().equals(shortRef());
    }

    private static String normaliseSha(String sha) {
        if (sha == null || sha.isBlank() || ZERO_SHA.equals(sha)) {
            return "";
        }
        return sha.strip();
    }
}
