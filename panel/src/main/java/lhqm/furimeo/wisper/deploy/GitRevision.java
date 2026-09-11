package lhqm.furimeo.wisper.deploy;

/**
 * The four Git columns on a deployment, travelling together.
 *
 * <p>They are one value because they are always set together and always read together:
 * a deployment list that shows a commit hash with no subject line is a column of noise,
 * and a build pinned to a commit whose ref nobody recorded cannot be explained afterwards.
 * Passing four nullable strings through five call sites is how three of them end up null
 * on the path nobody tested.
 *
 * <p>Everything here is nullable and every field is allowed to be absent. An archive
 * upload has no revision at all; a manual deploy of a branch has a ref and no commit
 * until the node resolves one and reports it back in {@code BuildCompleted}.
 *
 * @param ref           the branch or tag asked for, such as {@code main}
 * @param commitSha     the exact commit, when it is known before the build
 * @param commitMessage the full message; the list shows only its first line
 * @param commitAuthor  whatever the provider called the author, shown as-is
 */
public record GitRevision(String ref, String commitSha, String commitMessage,
                          String commitAuthor) {

    public GitRevision {
        ref = blankToNull(ref);
        commitSha = blankToNull(commitSha);
        commitMessage = blankToNull(commitMessage);
        commitAuthor = blankToNull(commitAuthor);
    }

    /** For an archive deploy, which has no repository behind it. */
    public static GitRevision unknown() {
        return new GitRevision(null, null, null, null);
    }

    /** A branch or tag with nothing resolved yet: the manual "deploy latest" case. */
    public static GitRevision ofRef(String ref) {
        return new GitRevision(ref, null, null, null);
    }

    /** Whether there is a ref to hand to a clone. */
    public boolean hasRef() {
        return ref != null;
    }

    /** Whether the build can be pinned to one commit instead of "whatever is on the tip". */
    public boolean isPinned() {
        return commitSha != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
