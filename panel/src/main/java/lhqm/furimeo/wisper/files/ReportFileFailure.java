package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.ServiceNotPlaced;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * Turns anything a file operation can fail with into a message and, where it belongs, a
 * {@code DENIED} entry.
 *
 * <p>Six exception types reach a file controller's {@code catch}, they need six different
 * sentences, and two of them must not be audited twice. Written out in each of the eight
 * handlers this would be the same forty lines eight times, seven of them right.
 *
 * <p>The rules, in the order they matter:
 *
 * <ul>
 * <li><strong>A traversal is recorded once.</strong> If the node refused it,
 *     {@link DispatchFileRequest} has already written the entry; writing another here would
 *     double every attempt in the trail. If the panel refused it, nothing has been written
 *     yet and this is where it happens.</li>
 * <li><strong>A traversal is answered flatly.</strong> No hint about what would have worked:
 *     the browser sends paths it was given, so a customer cannot type one by accident, and
 *     the only reader of a helpful message is somebody probing.</li>
 * <li><strong>A refusal is a {@code DENIED} entry; a failure is not.</strong> A
 *     {@code VIEWER} pressing delete and a node running out of disk are different events, and
 *     only the first belongs in a security trail.</li>
 * </ul>
 */
@Component
public class ReportFileFailure {

    private final AuditTrail audit;

    public ReportFileFailure(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * Writes the message and, if it was a refusal, the entry.
     *
     * @param action  the dotted audit action that was attempted
     * @param failure whatever came out of the use-case
     */
    public void of(FileAccess access, String action, RuntimeException failure,
                   RedirectAttributes flash) {
        if (failure instanceof PathRejected rejected && rejected.isTraversal()) {
            // Refused before it left the panel, so nothing has recorded it yet.
            audit.record(AuditEntry.denied(access.actor(), "files.path_escape", access.target(),
                    access.organizationId(), "a path containing \"..\" was refused by the panel"));
            InertiaFlash.failure(flash, rejected.getMessage());
            return;
        }
        if (failure instanceof FileOperationFailed refused && refused.isTraversalAttempt()) {
            // DispatchFileRequest already wrote the entry when the node refused it.
            InertiaFlash.failure(flash, "That path was refused.");
            return;
        }
        InertiaFlash.failure(flash, messageOf(failure));
        if (isRefusal(failure)) {
            audit.record(AuditEntry.denied(access.actor(), action, access.target(),
                    access.organizationId(), messageOf(failure)));
        }
    }

    /**
     * Whether somebody was told no, as opposed to something going wrong.
     *
     * <p>A node that is offline, an operation that timed out and a disk that filled are all
     * failures of the platform. Recording them as denials would bury the handful of entries
     * that mean "a person tried to do something they were not allowed to".
     */
    private static boolean isRefusal(RuntimeException failure) {
        return failure instanceof PermissionDenied
                || failure instanceof RequestRejected
                || failure instanceof PathRejected;
    }

    private static String messageOf(RuntimeException failure) {
        if (failure instanceof NodeOffline offline) {
            return "The machine holding these files is not reachable right now. Nothing was "
                    + "changed; it is still running everything it was given. (" + offline.nodeId()
                    + ")";
        }
        if (failure instanceof ServiceNotPlaced notPlaced) {
            return notPlaced.getMessage();
        }
        if (failure instanceof FileOperationFailed refused) {
            return refused.detail();
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "That did not work." : message;
    }
}
