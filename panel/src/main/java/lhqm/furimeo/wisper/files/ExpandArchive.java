package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.ExtractArchive;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.OperationDone;

/**
 * Unpacks an archive in place, on the node.
 *
 * <p>The dangerous half of the pair, and the reason {@code files.proto} says so out loud: an
 * archive full of {@code ../../etc} entries is the oldest trick there is, and the entries
 * come from a file the customer uploaded rather than from anything the panel produced. The
 * node resolves and checks every entry against the root exactly like any other path, and an
 * entry that escapes comes back as {@code FILE_ERROR_CODE_PATH_ESCAPES_ROOT} naming which
 * one - which the panel records as {@code files.path_escape} and shows flatly.
 *
 * <p>The panel's own checks here are the two it can make without reading the archive: the
 * archive path and the destination directory. It deliberately does not unpack anything
 * itself. A zip bomb expanded in the panel's memory to inspect it would take the panel down
 * instead of the node, and the node is the side with the quota.
 *
 * <p>{@code overwrite} defaults to false. Extracting over a running application's files is a
 * legitimate thing to do and doing it by accident is not.
 */
@Component
public class ExpandArchive {

    private final DispatchFileRequest dispatch;
    private final RecordArchiveAction record;

    public ExpandArchive(DispatchFileRequest dispatch, RecordArchiveAction record) {
        this.dispatch = dispatch;
        this.record = record;
    }

    /**
     * Unpacks {@code archive} into {@code destination}.
     *
     * @return how many entries were written, as the node counted them
     * @throws FileOperationFailed with {@code FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE} when the
     *         node does not recognise the format, or
     *         {@code FILE_ERROR_CODE_PATH_ESCAPES_ROOT} naming the entry that tried to leave
     */
    public long into(FileAccess access, RelativePath archive, RelativePath destination,
                     boolean overwrite) {
        if (archive.isRoot()) {
            throw PathRejected.malformed("", "Choose an archive to extract.");
        }
        OperationDone done = dispatch.call(access, FileRequest.newBuilder()
                .setExtract(ExtractArchive.newBuilder()
                        .setArchivePath(archive.value())
                        .setDestinationDirectory(destination.value())
                        .setOverwrite(overwrite)))
                .done();
        record.extracted(access, archive, destination, overwrite, done.getAffected());
        return done.getAffected();
    }
}
