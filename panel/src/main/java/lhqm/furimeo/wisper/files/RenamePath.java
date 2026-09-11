package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.MovePath;

/**
 * Renames or moves a path.
 *
 * <p>One operation, not two. A rename is a move whose destination happens to share a parent,
 * and separating them produces two code paths with one bug each - usually in the one nobody
 * exercised, which is moving a directory onto an existing name.
 *
 * <p>{@code overwrite} defaults to false everywhere it is offered. There is no trash on a
 * node: a move that silently replaces a file has destroyed it, and the customer finds out
 * later.
 */
@Component
public class RenamePath {

    private final DispatchFileRequest dispatch;
    private final AuditTrail audit;

    public RenamePath(DispatchFileRequest dispatch, AuditTrail audit) {
        this.dispatch = dispatch;
        this.audit = audit;
    }

    /**
     * Moves {@code from} to {@code to}.
     *
     * @param overwrite replace anything already at the destination
     * @throws FileOperationFailed with {@code FILE_ERROR_CODE_ALREADY_EXISTS} when something
     *         is there and {@code overwrite} is false
     */
    public void move(FileAccess access, RelativePath from, RelativePath to, boolean overwrite) {
        if (from.isRoot()) {
            throw PathRejected.malformed("", "A file root cannot be moved.");
        }
        if (to.isRoot()) {
            throw PathRejected.malformed("", "A file cannot be moved onto its root.");
        }
        dispatch.call(access, FileRequest.newBuilder()
                .setMove(MovePath.newBuilder()
                        .setFrom(from.value())
                        .setTo(to.value())
                        .setOverwrite(overwrite)))
                .done();
        audit.record(AuditEntry.succeeded(access.actor(), "files.move", access.target(),
                access.organizationId(),
                "moved " + from.value() + " to " + to.value() + " in " + access.root().label()
                        + (overwrite ? ", replacing what was there" : "")));
    }
}
