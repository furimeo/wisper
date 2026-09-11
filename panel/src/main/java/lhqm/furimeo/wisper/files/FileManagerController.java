package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.service.ServiceNotPlaced;

/**
 * The file manager itself: {@code GET /services/{id}/files}.
 *
 * <p>Renders {@code features/files/FileManagerPage.tsx} with the first page of the directory
 * already in its props. A page that arrives empty and then fetches shows a spinner on every
 * navigation, and on a phone on mobile data that spinner is most of the experience.
 *
 * <p>There is no SSH, no SFTP and no WebDAV in wisper, so this screen is the entire surface a
 * customer has on their disk (design §8.2). It therefore has to degrade honestly rather than
 * render an empty frame: a service with no volumes says so, a service that has never been
 * started says so, and a node that is unreachable says so - three states that all look like
 * "no files" if nobody distinguishes them.
 *
 * <p>Props: {@code service}, {@code roots}, {@code root}, {@code path}, {@code page},
 * {@code canWrite}, {@code chunkSize}, {@code maxEditableBytes}, {@code unavailable}.
 */
@Controller
public class FileManagerController {

    private final AuthorizeFileAccess authorize;
    private final ListFileRoots roots;
    private final ListDirectoryEntries listing;
    private final FilesSettings settings;

    public FileManagerController(AuthorizeFileAccess authorize, ListFileRoots roots,
                                 ListDirectoryEntries listing, FilesSettings settings) {
        this.authorize = authorize;
        this.roots = roots;
        this.listing = listing;
        this.settings = settings;
    }

    @GetMapping("/services/{serviceId}/files")
    public String browse(@PathVariable UUID serviceId,
                         @RequestParam(name = "rootId", required = false) String rootId,
                         @RequestParam(name = "path", required = false) String path,
                         @RequestParam(name = "hidden", defaultValue = "false") boolean hidden,
                         HttpServletRequest request, Model model) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        RelativePath here = RelativePath.of(path);

        model.addAttribute("service", access.service());
        model.addAttribute("roots", roots.forService(serviceId));
        model.addAttribute("root", access.root());
        model.addAttribute("path", here.value());
        model.addAttribute("parentPath", here.parent().value());
        model.addAttribute("canWrite", access.canWrite());
        model.addAttribute("showHidden", hidden);
        // The client needs both to size its uploads and to decide whether to offer the
        // editor; sending them saves a second endpoint that exists only to report settings.
        model.addAttribute("chunkSize", settings.chunkSizeBytes());
        model.addAttribute("maxEditableBytes", settings.maxEditableBytes().toBytes());

        String unavailable = null;
        try {
            model.addAttribute("page", listing.in(access, here, null, 0, hidden));
        } catch (ServiceNotPlaced notPlaced) {
            unavailable = notPlaced.getMessage();
            model.addAttribute("page", DirectoryPage.empty(here.value()));
        } catch (NodeOffline offline) {
            unavailable = "The machine holding these files is not reachable right now. Your "
                    + "files are safe; the panel just cannot see them until it reconnects.";
            model.addAttribute("page", DirectoryPage.empty(here.value()));
        } catch (FileOperationFailed | FileOperationTimedOut refused) {
            unavailable = refused.getMessage();
            model.addAttribute("page", DirectoryPage.empty(here.value()));
        }
        model.addAttribute("unavailable", unavailable);
        return "files/FileManager";
    }
}
