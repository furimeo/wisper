package lhqm.furimeo.wisper.files;

import java.time.Instant;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.FileInfo;

/**
 * One directory entry as the browser receives it.
 *
 * <p>A translation of {@code FileInfo} and not the message itself. Protobuf objects
 * serialise to JSON with their own conventions - unset fields vanish, a timestamp becomes
 * an object with seconds and nanos - and letting them out of the panel would make the
 * client's types the protobuf schema's shape rather than the screen's.
 *
 * @param mode      POSIX permission bits, low nine only. The type bits are already in
 *                  {@code directory}
 * @param modeOctal the same bits as the four digits a person edits, because
 *                  {@code "0644"} is what the chmod field contains and computing it in the
 *                  browser is one more place to get the padding wrong
 * @param symlink   reported, never followed. A symlink in a customer volume pointing outside
 *                  its root is shown as a symlink and refused as a target - following it is
 *                  the traversal hole a path check alone does not close
 */
public record FileEntryView(
        String name,
        String path,
        boolean directory,
        long sizeBytes,
        int mode,
        String modeOctal,
        Instant modifiedAt,
        boolean symlink,
        String symlinkTarget,
        int uid,
        int gid) {

    /** Only the permission bits; anything above them belongs to the file type. */
    private static final int PERMISSION_BITS = 0777;

    public static FileEntryView of(FileInfo info) {
        int mode = info.getMode() & PERMISSION_BITS;
        return new FileEntryView(
                info.getName(),
                info.getPath(),
                info.getDirectory(),
                info.getSizeBytes(),
                mode,
                String.format("%04o", mode),
                info.hasModifiedAt() ? instant(info.getModifiedAt()) : null,
                info.getSymlink(),
                info.getSymlinkTarget(),
                info.getUid(),
                info.getGid());
    }

    /** Whether the inline editor should be offered, given the ceiling it will open. */
    public boolean isEditable(long maxEditableBytes) {
        return !directory && !symlink && sizeBytes <= maxEditableBytes;
    }

    private static Instant instant(Timestamp at) {
        return Instant.ofEpochSecond(at.getSeconds(), at.getNanos());
    }
}
