package lhqm.furimeo.wisper.files;

import java.util.ArrayList;
import java.util.List;

/**
 * A path inside a file root, checked and normalised before it leaves the panel.
 *
 * <p>The file manager is the largest attack surface in v1 (AGENTS.md §5, design §9), and
 * the rule the whole design hangs off is that <strong>the panel never sends an absolute
 * path</strong>. Every request names a root by id and a path relative to it; the node
 * resolves the root and refuses anything that climbs out. This type is the panel's half of
 * that: nothing reaches a {@code FileRequest} without passing through it.
 *
 * <p>Two checks in two places is not redundancy. The node's check is against a resolved
 * real path on a real filesystem and catches symlinks; this one is against the characters
 * the browser sent and catches a request that should never have been built. A bug in either
 * is caught by the other, and a traversal attempt refused here never costs a round trip.
 *
 * <h2>What is refused, and what is merely tidied</h2>
 *
 * <p>Refused outright, as {@link PathRejected#traversal}: any {@code ..} segment, in any
 * position. There is no legitimate use for one - the browser navigates by asking for the
 * parent path, which the panel computes from a path it already validated.
 *
 * <p>Tidied silently: leading and repeated slashes, trailing slashes, and {@code .}
 * segments. A customer typing {@code /logs//app.log} into the address bar means
 * {@code logs/app.log}, and refusing them would be pedantry rather than security. The
 * leading slash in particular is <em>not</em> treated as absolute: there is no absolute
 * path in this vocabulary, so it can only have meant the root.
 *
 * <p>Refused as {@link PathRejected#malformed}: control characters including NUL - a NUL
 * truncates a path in every C library there is - and segments or paths longer than a
 * filesystem will accept.
 *
 * @param value the normalised path: no leading or trailing slash, no empty segment, and
 *              the empty string for the root itself
 */
public record RelativePath(String value) {

    /** {@code PATH_MAX} on Linux. Longer than this cannot be opened, so it cannot be real. */
    public static final int MAX_LENGTH = 4096;

    /** {@code NAME_MAX} on every filesystem a node is likely to have. */
    public static final int MAX_SEGMENT_LENGTH = 255;

    public RelativePath {
        value = normalise(value);
    }

    /** The root of a file root: the directory the browser opens on. */
    public static RelativePath root() {
        return new RelativePath("");
    }

    /**
     * A path as the browser sent it.
     *
     * @throws PathRejected if it tries to leave the root, or could not name a real file
     */
    public static RelativePath of(String raw) {
        return new RelativePath(raw);
    }

    /** Whether this is the root of its file root. */
    public boolean isRoot() {
        return value.isEmpty();
    }

    /** The last segment, which is the file's own name. Empty for the root. */
    public String name() {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    /** The directory this sits in. The root's parent is the root. */
    public RelativePath parent() {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? root() : new RelativePath(value.substring(0, slash));
    }

    /**
     * A child of this directory.
     *
     * <p>The child is validated on its own first, so a "name" containing a slash or a
     * {@code ..} is refused rather than joined.
     */
    public RelativePath resolve(String childName) {
        if (childName == null || childName.isBlank()) {
            return this;
        }
        if (childName.indexOf('/') >= 0) {
            throw PathRejected.malformed(childName, "A name cannot contain a slash.");
        }
        String child = new RelativePath(childName).value();
        return new RelativePath(isRoot() ? child : value + "/" + child);
    }

    /** What goes on the wire, which is exactly the normalised value. */
    @Override
    public String toString() {
        return value;
    }

    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String candidate = raw.strip();
        if (candidate.length() > MAX_LENGTH) {
            throw PathRejected.malformed(candidate,
                    "That path is longer than " + MAX_LENGTH + " characters.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : candidate.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                // A repeated, leading or trailing slash, or a no-op segment.
                continue;
            }
            if ("..".equals(segment)) {
                // Not normalised away by climbing: "a/../b" and "../b" would then differ
                // only in whether the attacker guessed a real directory name first.
                throw PathRejected.traversal(candidate);
            }
            checkSegment(candidate, segment);
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static void checkSegment(String path, String segment) {
        if (segment.length() > MAX_SEGMENT_LENGTH) {
            throw PathRejected.malformed(path,
                    "\"" + segment.substring(0, 32) + "…\" is longer than a filename may be.");
        }
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                // A NUL truncates a path in every C library there is, and the rest are
                // unusable in a listing even when the kernel would accept them.
                throw PathRejected.malformed(path,
                        "That name contains a character a filename cannot hold.");
            }
        }
    }
}
