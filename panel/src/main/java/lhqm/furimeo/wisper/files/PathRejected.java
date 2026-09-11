package lhqm.furimeo.wisper.files;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A path the panel refused before it ever reached a node.
 *
 * <p>The node checks every path too, and its check is the authoritative one - it is the
 * side that owns the filesystem, and {@code FILE_ERROR_CODE_PATH_ESCAPES_ROOT} is its
 * answer. This is the second lock on the same door, and the reason for having two is that
 * they fail differently: the node's check is against a resolved real path, this one is
 * against what the browser typed, and a bug in either is caught by the other.
 *
 * <p>{@link #isTraversal()} separates the two kinds of refusal, because they get different
 * treatment. A path with {@code ..} in it is an attack or a bug - the file manager sends
 * paths it was given, so a customer cannot type one by accident - and it is recorded as
 * {@code files.path_escape} with {@code AuditOutcome.DENIED} and answered flatly. A name
 * that is simply too long is a mistake, and the customer is told what is wrong with it.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PathRejected extends RuntimeException {

    private final String path;
    private final boolean traversal;

    private PathRejected(String path, boolean traversal, String message) {
        super(message);
        this.path = path;
        this.traversal = traversal;
    }

    /** The path tried to leave its root. Never a typo. */
    public static PathRejected traversal(String path) {
        return new PathRejected(path, true,
                "That path leaves its root and was refused.");
    }

    /** The path is malformed in a way the customer can fix. */
    public static PathRejected malformed(String path, String reason) {
        return new PathRejected(path, false, reason);
    }

    /** What was asked for, as it arrived. */
    public String path() {
        return path;
    }

    /**
     * Whether this is the refusal that gets an audit entry rather than a hint.
     */
    public boolean isTraversal() {
        return traversal;
    }
}
