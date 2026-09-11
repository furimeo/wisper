package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.CreateDirectory;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Makes a directory.
 *
 * <p>One level only. The file manager's "new folder" is a name typed into the directory the
 * customer is standing in, and a name containing a slash is a mistake rather than a request
 * for a tree - {@link RelativePath#resolve} refuses it and says so. An extract needs the
 * parents created, and that happens on the node inside the extract itself.
 *
 * <p>Audited as {@code files.create_directory}, which is its own action in
 * {@code docs/contracts/panel-ports.md} §2.4 rather than being folded into
 * {@code files.upload}. Making a directory is a state change and AGENTS.md §5 requires an
 * entry for one; borrowing a neighbouring word would put the wrong verb in the only record
 * of what happened.
 */
@Component
public class CreateFolder {

    private final DispatchFileRequest dispatch;
    private final AuditTrail audit;

    public CreateFolder(DispatchFileRequest dispatch, AuditTrail audit) {
        this.dispatch = dispatch;
        this.audit = audit;
    }

    /**
     * Creates {@code name} inside {@code parent}.
     *
     * @return the path of the new directory
     * @throws PathRejected        if the name is not one a filename can hold
     * @throws FileOperationFailed with {@code FILE_ERROR_CODE_ALREADY_EXISTS} if something
     *         is already there
     */
    public RelativePath in(FileAccess access, RelativePath parent, String name) {
        RelativePath created = parent.resolve(name);
        dispatch.call(access, FileRequest.newBuilder()
                .setCreateDirectory(CreateDirectory.newBuilder()
                        .setPath(created.value())
                        // One level. The button that calls this creates one folder in the
                        // directory that is on screen, and silently creating three because
                        // somebody pasted a path is not what they asked for.
                        .setParents(false)))
                .done();
        audit.record(AuditEntry.succeeded(access.actor(), "files.create_directory",
                access.target(), access.organizationId(),
                "created " + created.value() + " in " + access.root().label()));
        return created;
    }
}
