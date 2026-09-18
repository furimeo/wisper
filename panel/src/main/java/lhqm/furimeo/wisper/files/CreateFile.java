package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Creates a blank file.
 *
 * <p>One level only. Audited as {@code files.create_file}.
 */
@Component
public class CreateFile {

    private final SaveFileContent save;
    private final AuditTrail audit;

    public CreateFile(SaveFileContent save, AuditTrail audit) {
        this.save = save;
        this.audit = audit;
    }

    public RelativePath in(FileAccess access, RelativePath parent, String name) {
        RelativePath created = parent.resolve(name);
        save.save(access, created, "");
        audit.record(AuditEntry.succeeded(access.actor(), "files.create_file",
                access.target(), access.organizationId(),
                "created blank file " + created.value() + " in " + access.root().label()));
        return created;
    }
}
