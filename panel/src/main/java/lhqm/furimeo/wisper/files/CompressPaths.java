package lhqm.furimeo.wisper.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.ArchiveFormat;
import lhqm.furimeo.wisper.proto.v1.CreateArchive;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Packs selected paths into an archive, on the node.
 *
 * <p>Compressing here rather than in the browser is the whole point of having it: a customer
 * on a phone can package a directory they could never download first, and then download the
 * one file. It is also how they take a copy of a volume before doing something risky to it,
 * which is the closest thing to an undo this file manager has.
 *
 * <p>Every source path is validated on its own, and so is the destination. An archive is one
 * of the two places a path list arrives from the browser rather than from a listing the
 * panel produced, which makes it one of the two places worth being careful about - the other
 * is {@link ExpandArchive}.
 */
@Component
public class CompressPaths {

    /** More than this in one archive is a request that will time out rather than finish. */
    private static final int MAX_SOURCES = 500;

    private final DispatchFileRequest dispatch;
    private final RecordArchiveAction record;

    public CompressPaths(DispatchFileRequest dispatch, RecordArchiveAction record) {
        this.dispatch = dispatch;
        this.record = record;
    }

    /**
     * Creates the archive.
     *
     * @param sources     paths as the browser sent them, each validated here
     * @param destination where the archive goes, relative to the root
     * @param format      {@code zip} or {@code tar.gz}; anything else is zip, which is what
     *                    a customer on Windows can open
     * @throws PathRejected if a source or the destination is not a usable path
     */
    public void into(FileAccess access, List<String> sources, RelativePath destination,
                     String format) {
        List<String> paths = validate(sources);
        dispatch.call(access, FileRequest.newBuilder()
                .setArchive(CreateArchive.newBuilder()
                        .addAllPaths(paths)
                        .setDestination(destination.value())
                        .setFormat(formatOf(format))))
                .done();
        record.archived(access, paths.size(), destination);
    }

    private static List<String> validate(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            throw PathRejected.malformed("", "Select something to compress first.");
        }
        if (sources.size() > MAX_SOURCES) {
            throw PathRejected.malformed("", "That is more than " + MAX_SOURCES
                    + " entries. Compress the folder that contains them instead.");
        }
        List<String> checked = new ArrayList<>(sources.size());
        for (String source : sources) {
            RelativePath path = RelativePath.of(source);
            if (path.isRoot()) {
                throw PathRejected.malformed("",
                        "Compress the entries inside a root rather than the root itself.");
            }
            checked.add(path.value());
        }
        return List.copyOf(checked);
    }

    private static ArchiveFormat formatOf(String format) {
        if (format == null) {
            return ArchiveFormat.ARCHIVE_FORMAT_ZIP;
        }
        String wanted = format.strip().toLowerCase(Locale.ROOT);
        return switch (wanted) {
            case "tar.gz", "targz", "tgz" -> ArchiveFormat.ARCHIVE_FORMAT_TAR_GZ;
            default -> ArchiveFormat.ARCHIVE_FORMAT_ZIP;
        };
    }
}
