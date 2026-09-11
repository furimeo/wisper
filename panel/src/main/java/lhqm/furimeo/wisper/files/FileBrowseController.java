package lhqm.furimeo.wisper.files;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reading the tree, as JSON: paging, stat and folder size.
 *
 * <p>Not Inertia visits. Scrolling to the next page of a directory, opening a file's details
 * and asking how big a folder is are all things that happen inside a page that is already
 * open; answering them with a full page render would push history entries, re-run the
 * shared props and lose the customer's scroll position on a phone.
 *
 * <p>Reading is allowed to any member, {@code VIEWER} included. Looking at a file list
 * changes nothing, and a read-only role that cannot read is not a role.
 */
@Controller
public class FileBrowseController {

    private final AuthorizeFileAccess authorize;
    private final ListFileRoots roots;
    private final ListDirectoryEntries listing;
    private final StatFile stat;
    private final MeasureDirectorySize measure;

    public FileBrowseController(AuthorizeFileAccess authorize, ListFileRoots roots,
                                ListDirectoryEntries listing, StatFile stat,
                                MeasureDirectorySize measure) {
        this.authorize = authorize;
        this.roots = roots;
        this.listing = listing;
        this.stat = stat;
        this.measure = measure;
    }

    /** The roots this service exposes, for the picker at the top of the page. */
    @GetMapping(path = "/services/{serviceId}/files/roots",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<FileRootRef> roots(@PathVariable UUID serviceId, HttpServletRequest request) {
        // Resolves membership and throws NotFoundException when the service is not the
        // caller's; the roots themselves come from the same query the check used.
        authorize.toRead(serviceId, null, request);
        return roots.forService(serviceId);
    }

    /**
     * One page of a directory.
     *
     * @param cursor from the previous page's {@code nextCursor}; absent starts at the top
     */
    @GetMapping(path = "/services/{serviceId}/files/list",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DirectoryPage list(@PathVariable UUID serviceId,
                              @RequestParam(name = "rootId", required = false) String rootId,
                              @RequestParam(name = "path", required = false) String path,
                              @RequestParam(name = "cursor", required = false) String cursor,
                              @RequestParam(name = "pageSize", defaultValue = "0") int pageSize,
                              @RequestParam(name = "hidden", defaultValue = "false") boolean hidden,
                              HttpServletRequest request) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        return listing.in(access, RelativePath.of(path), cursor, pageSize, hidden);
    }

    /** One path's metadata, for a details panel or before a download. */
    @GetMapping(path = "/services/{serviceId}/files/stat",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FileEntryView stat(@PathVariable UUID serviceId,
                              @RequestParam(name = "rootId", required = false) String rootId,
                              @RequestParam(name = "path") String path,
                              HttpServletRequest request) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        return stat.at(access, RelativePath.of(path));
    }

    /**
     * How big a folder is.
     *
     * <p>Its own request because it walks the tree, which can take seconds on a real volume.
     * The customer asks for it deliberately, by tapping the folder's size, rather than paying
     * for it on every listing.
     */
    @GetMapping(path = "/services/{serviceId}/files/size",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DirectorySizeView size(@PathVariable UUID serviceId,
                                  @RequestParam(name = "rootId", required = false) String rootId,
                                  @RequestParam(name = "path", required = false) String path,
                                  HttpServletRequest request) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        return measure.of(access, RelativePath.of(path));
    }
}
