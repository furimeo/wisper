package lhqm.furimeo.wisper.files;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A node accepted a file operation and did not answer in time.
 *
 * <p>Deliberately not a {@link FileOperationFailed}: that carries a {@code FileError} the
 * node produced, and pretending a node said something it did not is how a diagnosis goes
 * wrong. Nothing is known here except that no answer arrived, and the node may still be
 * working - the request has been cancelled, but a cancel is a request too.
 *
 * <p>Also not {@link lhqm.furimeo.wisper.node.NodeOffline}: the stream is open and the node
 * is reachable. Telling a customer their node is down when it is merely slow sends them to
 * the wrong screen.
 */
@ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
public class FileOperationTimedOut extends RuntimeException {

    private final String path;

    private FileOperationTimedOut(String path, String message) {
        super(message);
        this.path = path;
    }

    /** The deadline passed. */
    public static FileOperationTimedOut after(Duration waited, String path) {
        return new FileOperationTimedOut(path, "The node did not answer within "
                + waited.toSeconds() + " seconds. It may still be working; try again in a moment.");
    }

    /** The panel is shutting down, or the request thread was interrupted. */
    public static FileOperationTimedOut interrupted(String path) {
        return new FileOperationTimedOut(path,
                "That operation was interrupted before the node answered.");
    }

    /** Which path it was about, relative to the root, or empty when it was about none. */
    public String path() {
        return path;
    }
}
