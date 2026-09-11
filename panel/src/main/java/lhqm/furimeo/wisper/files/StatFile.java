package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.StatPath;

/**
 * Reads one path's metadata.
 *
 * <p>Its own operation rather than "list the parent and find it", because the parent may
 * have forty thousand entries and the answer needed is one row. The download and the inline
 * editor both start here: the size decides whether the editor opens the file or offers the
 * first megabyte of it, and the {@code Content-Length} of a download comes from the same
 * call.
 */
@Component
public class StatFile {

    private final DispatchFileRequest dispatch;

    public StatFile(DispatchFileRequest dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * What is at this path.
     *
     * @throws FileOperationFailed with {@code FILE_ERROR_CODE_NOT_FOUND} when there is
     *         nothing there
     */
    public FileEntryView at(FileAccess access, RelativePath path) {
        return FileEntryView.of(dispatch.call(access, FileRequest.newBuilder()
                .setStat(StatPath.newBuilder().setPath(path.value()))).info());
    }
}
