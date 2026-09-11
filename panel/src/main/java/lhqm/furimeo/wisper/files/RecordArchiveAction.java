package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Writes the audit entries for packing and unpacking.
 *
 * <p>The two operations are separate use-cases with separate validation, but their trail
 * entries are the same sentence with different nouns and they are the pair somebody reads
 * together: "who put this archive here, and who unpacked it over what". Keeping both
 * writings in one file means the wording of one cannot drift from the other, which matters
 * more here than in most places because these two are the file-manager actions most likely
 * to have destroyed something.
 */
@Component
public class RecordArchiveAction {

    private final AuditTrail audit;

    public RecordArchiveAction(AuditTrail audit) {
        this.audit = audit;
    }

    /** An archive was created from {@code count} selected paths. */
    public void archived(FileAccess access, int count, RelativePath destination) {
        audit.record(AuditEntry.succeeded(access.actor(), "files.archive", access.target(),
                access.organizationId(),
                "packed " + count + " path(s) into " + destination.value() + " in "
                        + access.root().label()));
    }

    /** An archive was unpacked. {@code overwrote} is the part somebody comes looking for. */
    public void extracted(FileAccess access, RelativePath archive, RelativePath into,
                          boolean overwrote, long entries) {
        audit.record(AuditEntry.succeeded(access.actor(), "files.extract", access.target(),
                access.organizationId(),
                "extracted " + archive.value() + " into "
                        + (into.isRoot() ? "the root of " + access.root().label()
                                         : into.value())
                        + " (" + entries + " entr" + (entries == 1 ? "y" : "ies") + ")"
                        + (overwrote ? ", replacing existing files" : "")));
    }
}
