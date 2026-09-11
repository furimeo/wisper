package lhqm.furimeo.wisper.files;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Getting bytes out: the download, and the inline editor's read.
 *
 * <p>The download streams. A customer taking a 3 GB database dump off a volume must not cost
 * the panel 3 GB of heap, so each chunk the node sends is written into the servlet's output
 * stream and flushed - which is also what applies backpressure to the node when the phone at
 * the other end is slow.
 *
 * <p>Neither endpoint is audited. Reading is not a state change, and an entry per file view
 * would bury the entries that matter under a file manager's ordinary browsing.
 */
@Controller
public class FileTransferController {

    private static final Logger log = LoggerFactory.getLogger(FileTransferController.class);

    private final AuthorizeFileAccess authorize;
    private final ReadFileContent read;
    private final StatFile stat;
    private final FilesSettings settings;

    public FileTransferController(AuthorizeFileAccess authorize, ReadFileContent read,
                                  StatFile stat, FilesSettings settings) {
        this.authorize = authorize;
        this.read = read;
        this.stat = stat;
        this.settings = settings;
    }

    /**
     * Sends a file to the browser.
     *
     * <p>{@code Content-Length} comes from a stat first, so the browser can draw a progress
     * bar and detect a truncated download. Without it a connection that dies half way looks
     * to the browser exactly like a file that ended.
     *
     * @param offset resume point for a download the customer's connection interrupted
     */
    @GetMapping("/services/{serviceId}/files/download")
    public void download(@PathVariable UUID serviceId,
                         @RequestParam(name = "rootId", required = false) String rootId,
                         @RequestParam(name = "path") String path,
                         @RequestParam(name = "offset", defaultValue = "0") long offset,
                         HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        RelativePath file = RelativePath.of(path);
        FileEntryView info = stat.at(access, file);
        if (info.directory()) {
            throw PathRejected.malformed(file.value(),
                    "That is a folder. Compress it first, then download the archive.");
        }

        long from = Math.max(0, Math.min(offset, info.sizeBytes()));
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(info.sizeBytes() - from);
        // attachment, always: a customer's HTML or SVG rendered inline would run their own
        // script on the panel's origin, next to their session cookie.
        response.setHeader("Content-Disposition", disposition(info.name()));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        try (OutputStream out = response.getOutputStream()) {
            read.into(access, file, from, 0, out);
        } catch (UncheckedIOException gone) {
            // The customer closed the download. The read has already been cancelled on the
            // node by DispatchFileRequest; there is nothing to report and nobody to report to.
            log.debug("Download of {} ended early: {}", file.value(), gone.getMessage());
        }
    }

    /**
     * The beginning of a file, as text, for the inline editor.
     *
     * <p>Capped at {@code wisper.files.max-editable-bytes}. Past that the editor opens what
     * it got read-only rather than refusing to show a large log at all - and read-only
     * matters, because saving a truncated buffer would write the first megabyte over the
     * whole file.
     */
    @GetMapping(path = "/services/{serviceId}/files/content",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FileText content(@PathVariable UUID serviceId,
                            @RequestParam(name = "rootId", required = false) String rootId,
                            @RequestParam(name = "path") String path,
                            HttpServletRequest request) {
        FileAccess access = authorize.toRead(serviceId, rootId, request);
        return read.text(access, RelativePath.of(path), settings.maxEditableBytes().toBytes());
    }

    /**
     * A filename in a header, twice.
     *
     * <p>The ASCII {@code filename} for clients that only understand RFC 2616, and the
     * percent-encoded {@code filename*} for everything since RFC 6266. A customer's file
     * really is called {@code 請求書.pdf} and a header that drops the name is a download
     * called "download".
     */
    private static String disposition(String name) {
        String fallback = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
