package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.DeletePath;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.OperationDone;

/**
 * Deletes a path.
 *
 * <p>{@code recursive} is required for a non-empty directory and defaults to false. There
 * is no trash on a node and no snapshot between the button and the syscall: deleting a tree
 * because a flag defaulted the other way is not recoverable, and the customer discovers it
 * when they look for something that is gone.
 *
 * <p>The root itself cannot be deleted. Emptying a volume is a legitimate thing to want and
 * removing the directory the mount points at is not the way to do it.
 */
@Component
public class RemovePath {

    private final DispatchFileRequest dispatch;
    private final AuditTrail audit;

    public RemovePath(DispatchFileRequest dispatch, AuditTrail audit) {
        this.dispatch = dispatch;
        this.audit = audit;
    }

    /**
     * Deletes one path.
     *
     * @param recursive required for a directory that is not empty
     * @return how many entries went, as the node counted them
     * @throws FileOperationFailed with {@code FILE_ERROR_CODE_DIRECTORY_NOT_EMPTY} when the
     *         flag was needed and not given
     */
    public long at(FileAccess access, RelativePath path, boolean recursive) {
        if (path.isRoot()) {
            throw PathRejected.malformed("",
                    "A file root cannot be deleted. Delete what is inside it instead.");
        }
        OperationDone done = dispatch.call(access, FileRequest.newBuilder()
                .setDelete(DeletePath.newBuilder()
                        .setPath(path.value())
                        .setRecursive(recursive)))
                .done();
        audit.record(AuditEntry.succeeded(access.actor(), "files.delete", access.target(),
                access.organizationId(),
                "deleted " + path.value() + " from " + access.root().label()
                        + (done.getAffected() > 1 ? " (" + done.getAffected() + " entries)" : "")));
        return done.getAffected();
    }
}
