package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.ListDirectory;

/**
 * Reads one page of a directory.
 *
 * <p>Paged rather than whole. A customer with a {@code node_modules} has a hundred thousand
 * entries; sending all of them to a phone produces a request that never finishes rendering
 * and a node that spends a second building it. The node returns directories first and then
 * by name, so the cursor is stable and a file created while somebody is scrolling does not
 * shift what they have already seen.
 *
 * <p>Dotfiles are hidden by default because the customer's own files are what they came to
 * see, and showing them is one tap.
 */
@Component
public class ListDirectoryEntries {

    private final DispatchFileRequest dispatch;
    private final FilesSettings settings;

    public ListDirectoryEntries(DispatchFileRequest dispatch, FilesSettings settings) {
        this.dispatch = dispatch;
        this.settings = settings;
    }

    /**
     * One page.
     *
     * @param cursor         from the previous page's {@code nextCursor}; null starts at the
     *                       beginning
     * @param pageSize       zero or less means the configured default
     * @param includeHidden  whether dotfiles are shown
     */
    public DirectoryPage in(FileAccess access, RelativePath path, String cursor, int pageSize,
                            boolean includeHidden) {
        ListDirectory.Builder list = ListDirectory.newBuilder()
                .setPath(path.value())
                .setPageSize(settings.pageSize(pageSize))
                .setIncludeHidden(includeHidden);
        if (cursor != null && !cursor.isBlank()) {
            list.setCursor(cursor.strip());
        }
        return DirectoryPage.of(
                dispatch.call(access, FileRequest.newBuilder().setList(list)).listing());
    }
}
