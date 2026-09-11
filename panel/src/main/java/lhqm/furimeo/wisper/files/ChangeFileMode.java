package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.ChangeMode;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Changes a path's permission bits.
 *
 * <p>The bits arrive as the four octal digits the customer typed, because that is what a
 * chmod field contains and parsing it here means the browser does not have to know about
 * setuid. Only the low nine are sent: the type bits are not a permission and setuid, setgid
 * and the sticky bit are not something a web file manager should be handing out inside a
 * container that is already running as a fixed user.
 *
 * <p>{@code recursive} applies the change to everything underneath, and the node applies the
 * execute bit to directories only - a directory without {@code x} cannot be entered, so a
 * recursive {@code 644} would otherwise lock a customer out of their own tree.
 */
@Component
public class ChangeFileMode {

    /** The nine bits a file manager may set: owner, group and other, read/write/execute. */
    private static final int PERMISSION_BITS = 0777;

    private final DispatchFileRequest dispatch;
    private final AuditTrail audit;

    public ChangeFileMode(DispatchFileRequest dispatch, AuditTrail audit) {
        this.dispatch = dispatch;
        this.audit = audit;
    }

    /**
     * Sets the mode.
     *
     * @param octal     the digits as typed, such as {@code "755"} or {@code "0644"}
     * @param recursive apply to everything underneath
     * @throws PathRejected if the digits are not a mode
     */
    public void to(FileAccess access, RelativePath path, String octal, boolean recursive) {
        int mode = parse(octal);
        dispatch.call(access, FileRequest.newBuilder()
                .setChangeMode(ChangeMode.newBuilder()
                        .setPath(path.value())
                        .setMode(mode)
                        .setRecursive(recursive)))
                .done();
        audit.record(AuditEntry.succeeded(access.actor(), "files.chmod", access.target(),
                access.organizationId(),
                "set " + path.value() + " to " + String.format("%04o", mode)
                        + (recursive ? " recursively" : "") + " in " + access.root().label()));
    }

    /**
     * Octal digits to the nine bits that are allowed.
     *
     * <p>Anything above the low nine is dropped rather than refused. A customer pasting
     * {@code 4755} means "rwxr-xr-x and please make it setuid"; the honest answer is the
     * permissions they asked for without the bit this platform will not grant, and refusing
     * the whole request would leave them guessing which digit was the problem.
     */
    private static int parse(String octal) {
        if (octal == null || octal.isBlank()) {
            throw PathRejected.malformed("", "A mode is three or four octal digits, like 755.");
        }
        String digits = octal.strip();
        try {
            return Integer.parseInt(digits, 8) & PERMISSION_BITS;
        } catch (NumberFormatException notAMode) {
            throw PathRejected.malformed("",
                    "\"" + digits + "\" is not a mode. Use three or four octal digits, like 755.");
        }
    }
}
