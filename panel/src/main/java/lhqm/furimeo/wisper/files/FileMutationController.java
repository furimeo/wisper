package lhqm.furimeo.wisper.files;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * Everything that changes the tree: new folder, rename, delete, chmod, compress, extract.
 *
 * <p>Six Inertia form posts, each redirecting back to the directory it acted on with a flash
 * message, because a POST that renders a page directly leaves the browser on a URL that
 * cannot be reloaded (panel-http.md). Upload is not here - it is a series of XHRs and lives
 * in {@link FileUploadController} - and neither is the editor's save, which is an upload of
 * one chunk.
 *
 * <p>Every handler has the same three lines around it: resolve read access so a refusal has a
 * service and an actor to name, check the write permission inside the {@code try} so a
 * {@code VIEWER} pressing delete produces a {@code DENIED} entry rather than a 403 page with
 * no record, and report whatever came out through {@link ReportFileFailure}.
 */
@Controller
public class FileMutationController {

    private final AuthorizeFileAccess authorize;
    private final CreateFolder createFolder;
    private final CreateFile createFile;
    private final RenamePath rename;
    private final RemovePath remove;
    private final ChangeFileMode chmod;
    private final CompressPaths compress;
    private final ExpandArchive expand;
    private final SaveFileContent save;
    private final ReportFileFailure failures;

    public FileMutationController(AuthorizeFileAccess authorize, CreateFolder createFolder,
                                  CreateFile createFile, RenamePath rename, RemovePath remove,
                                  ChangeFileMode chmod, CompressPaths compress, ExpandArchive expand,
                                  SaveFileContent save, ReportFileFailure failures) {
        this.authorize = authorize;
        this.createFolder = createFolder;
        this.createFile = createFile;
        this.rename = rename;
        this.remove = remove;
        this.chmod = chmod;
        this.compress = compress;
        this.expand = expand;
        this.save = save;
        this.failures = failures;
    }

    @PostMapping("/services/{serviceId}/files/file")
    public String file(@PathVariable UUID serviceId,
                       @RequestParam(name = "rootId", required = false) String rootId,
                       @RequestParam(name = "path", required = false) String path,
                       @RequestParam(name = "name") String name,
                       HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.create_file");
            createFile.in(access, RelativePath.of(path), name);
            InertiaFlash.success(flash, "Created " + name + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.create_file", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path));
    }

    @PostMapping("/services/{serviceId}/files/folder")
    public String folder(@PathVariable UUID serviceId,
                         @RequestParam(name = "rootId", required = false) String rootId,
                         @RequestParam(name = "path", required = false) String path,
                         @RequestParam(name = "name") String name,
                         HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.create_directory");
            createFolder.in(access, RelativePath.of(path), name);
            InertiaFlash.success(flash, "Created " + name + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.create_directory", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path));
    }

    @PostMapping("/services/{serviceId}/files/rename")
    public String rename(@PathVariable UUID serviceId,
                         @RequestParam(name = "rootId", required = false) String rootId,
                         @RequestParam(name = "from") String from,
                         @RequestParam(name = "to") String to,
                         @RequestParam(name = "overwrite", defaultValue = "false")
                         boolean overwrite,
                         HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.move");
            RelativePath source = RelativePath.of(from);
            rename.move(access, source, RelativePath.of(to), overwrite);
            InertiaFlash.success(flash, "Moved " + source.name() + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.move", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(from).parent());
    }

    @PostMapping("/services/{serviceId}/files/delete")
    public String delete(@PathVariable UUID serviceId,
                         @RequestParam(name = "rootId", required = false) String rootId,
                         @RequestParam(name = "path") String path,
                         @RequestParam(name = "recursive", defaultValue = "false")
                         boolean recursive,
                         HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.delete");
            RelativePath target = RelativePath.of(path);
            long affected = remove.at(access, target, recursive);
            InertiaFlash.success(flash, "Deleted " + target.name()
                    + (affected > 1 ? " and " + (affected - 1) + " other entries." : "."));
        } catch (RuntimeException refused) {
            failures.of(access, "files.delete", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path).parent());
    }

    @PostMapping("/services/{serviceId}/files/chmod")
    public String chmod(@PathVariable UUID serviceId,
                        @RequestParam(name = "rootId", required = false) String rootId,
                        @RequestParam(name = "path") String path,
                        @RequestParam(name = "mode") String mode,
                        @RequestParam(name = "recursive", defaultValue = "false")
                        boolean recursive,
                        HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.chmod");
            RelativePath target = RelativePath.of(path);
            chmod.to(access, target, mode, recursive);
            InertiaFlash.success(flash, "Permissions on " + target.name() + " are now " + mode
                    + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.chmod", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path).parent());
    }

    /** Packs the selected entries into an archive beside them. */
    @PostMapping("/services/{serviceId}/files/archive")
    public String archive(@PathVariable UUID serviceId,
                          @RequestParam(name = "rootId", required = false) String rootId,
                          @RequestParam(name = "path", required = false) String path,
                          @RequestParam(name = "paths") List<String> paths,
                          @RequestParam(name = "destination") String destination,
                          @RequestParam(name = "format", defaultValue = "zip") String format,
                          HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.archive");
            RelativePath target = RelativePath.of(destination);
            compress.into(access, paths, target, format);
            InertiaFlash.success(flash, "Created " + target.name() + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.archive", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path));
    }

    /** Unpacks an archive into a directory. */
    @PostMapping("/services/{serviceId}/files/extract")
    public String extract(@PathVariable UUID serviceId,
                          @RequestParam(name = "rootId", required = false) String rootId,
                          @RequestParam(name = "archive") String archive,
                          @RequestParam(name = "destination", required = false) String destination,
                          @RequestParam(name = "overwrite", defaultValue = "false")
                          boolean overwrite,
                          HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.extract");
            long entries = expand.into(access, RelativePath.of(archive),
                    RelativePath.of(destination), overwrite);
            InertiaFlash.success(flash, "Extracted " + entries + " entr"
                    + (entries == 1 ? "y" : "ies") + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.extract", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(destination));
    }

    /**
     * Saves what the inline editor holds.
     *
     * <p>A one-chunk upload underneath, so there is no second write path (see
     * {@link SaveFileContent}). Redirects back to the file rather than to the directory,
     * because the customer is still editing it.
     */
    @PostMapping("/services/{serviceId}/files/content")
    public String save(@PathVariable UUID serviceId,
                       @RequestParam(name = "rootId", required = false) String rootId,
                       @RequestParam(name = "path") String path,
                       @RequestParam(name = "text", required = false) String text,
                       HttpServletRequest request, RedirectAttributes flash) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        try {
            authorize.requireWritable(access, "files.upload");
            FileEntryView saved = save.save(access, RelativePath.of(path), text);
            InertiaFlash.success(flash, "Saved " + saved.name() + ".");
        } catch (RuntimeException refused) {
            failures.of(access, "files.upload", refused, flash);
        }
        return back(serviceId, access.rootId(), forRedirect(path).parent());
    }

    /** Back to the directory the action happened in, with the root kept. */
    private static String back(UUID serviceId, String rootId, RelativePath path) {
        return "redirect:/services/" + serviceId + "/files?rootId=" + encode(rootId)
                + "&path=" + encode(path.value());
    }

    /**
     * A path good enough to redirect to.
     *
     * <p>The strict parse happens inside each handler's {@code try}, so a traversal attempt
     * produces a {@code DENIED} entry and a message. This one only has to produce somewhere
     * to send the browser afterwards, and for a path that was refused the honest destination
     * is the root of the tree.
     */
    private static RelativePath forRedirect(String raw) {
        try {
            return RelativePath.of(raw);
        } catch (PathRejected refused) {
            return RelativePath.root();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
