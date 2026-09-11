package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.DirectorySize;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.MeasureDirectory;

/**
 * Answers "how big is this folder" - the question a customer asks when their quota is full.
 *
 * <p>A separate operation from a listing because it walks the whole tree, and a listing must
 * not: a directory page that stats every subtree before it renders takes minutes on a real
 * volume. The node stops when it hits its own budget and marks the answer approximate, which
 * is more useful than a request that never returns.
 */
@Component
public class MeasureDirectorySize {

    private final DispatchFileRequest dispatch;

    public MeasureDirectorySize(DispatchFileRequest dispatch) {
        this.dispatch = dispatch;
    }

    /** The size of a directory tree. */
    public DirectorySizeView of(FileAccess access, RelativePath path) {
        DirectorySize size = dispatch.call(access, FileRequest.newBuilder()
                .setMeasure(MeasureDirectory.newBuilder().setPath(path.value()))).size();
        return new DirectorySizeView(size.getPath(), size.getBytes(), size.getFileCount(),
                size.getDirectoryCount(), size.getApproximate(),
                access.root().quotaBytes());
    }
}
