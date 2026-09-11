package lhqm.furimeo.wisper.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Chunked upload with resume: {@code /services/{id}/files/uploads/**}.
 *
 * <p>The one part of the file manager that is not an Inertia form, and it cannot be. A
 * customer on 4G in a lift needs the upload to survive losing signal, which means the client
 * controls the chunking, watches each acknowledgement and continues from the first gap - none
 * of which survives a page render between requests. So these five endpoints answer JSON to
 * {@code fetch} (design §8.2).
 *
 * <p>They are still session-authenticated and still CSRF-protected: {@code SecurityConfig}
 * exempts only {@code /api/**} and {@code /webhooks/**}, so the client must send the
 * {@code X-XSRF-TOKEN} header it already reads from the cookie for every {@code POST} here.
 *
 * <h2>The five requests, and why each exists</h2>
 *
 * <ol>
 * <li>{@code POST uploads} - register a session and find out what the node already has. Also
 *     the resume entry point: the client calls it again after any interruption, including one
 *     that restarted the panel.</li>
 * <li>{@code GET uploads/{id}} - the cheap poll, for a client that has the session id and
 *     wants the ranges without resending the metadata.</li>
 * <li>{@code POST uploads/{id}/chunks} - one chunk. The client waits for the acknowledgement
 *     before releasing it from memory, which is what stops a phone buffering a file it cannot
 *     hold.</li>
 * <li>{@code POST uploads/{id}/complete} - assemble, verify, move into place. Until this
 *     succeeds there is no file at the destination, only parts.</li>
 * <li>{@code POST uploads/{id}/abort} - the customer cancelled. Deletes the parts now rather
 *     than leaving them against their quota until the node's own sweep.</li>
 * </ol>
 */
@Controller
public class FileUploadController {

    private final AuthorizeFileAccess authorize;
    private final BeginUpload begin;
    private final ReceiveUploadChunk receive;
    private final QueryUploadState query;
    private final FinishUpload finish;
    private final AbandonUpload abandon;

    public FileUploadController(AuthorizeFileAccess authorize, BeginUpload begin,
                                ReceiveUploadChunk receive, QueryUploadState query,
                                FinishUpload finish, AbandonUpload abandon) {
        this.authorize = authorize;
        this.begin = begin;
        this.receive = receive;
        this.query = query;
        this.finish = finish;
        this.abandon = abandon;
    }

    /**
     * Opens or reopens a session.
     *
     * @param sessionId     minted by the browser and kept next to the file it picked, which
     *                      is what makes a resume survive a reload
     * @param totalBytes    from {@code File.size}
     * @param contentSha256 hex SHA-256 of the whole file, or omitted
     */
    @PostMapping(path = "/services/{serviceId}/files/uploads",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public UploadProgress open(@PathVariable UUID serviceId,
                               @RequestParam(name = "rootId", required = false) String rootId,
                               @RequestParam(name = "sessionId") String sessionId,
                               @RequestParam(name = "path") String path,
                               @RequestParam(name = "totalBytes") long totalBytes,
                               @RequestParam(name = "contentSha256", required = false)
                               String contentSha256,
                               @RequestParam(name = "overwrite", defaultValue = "false")
                               boolean overwrite,
                               HttpServletRequest request) {
        FileAccess access = authorize.toWrite(serviceId, rootId, "files.upload", request);
        return begin.start(access, sessionId, RelativePath.of(path), totalBytes, contentSha256,
                overwrite);
    }

    /** What the node already has, so the client can skip ahead. */
    @GetMapping(path = "/services/{serviceId}/files/uploads/{sessionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public UploadProgress state(@PathVariable UUID serviceId,
                                @PathVariable String sessionId,
                                @RequestParam(name = "rootId", required = false) String rootId,
                                HttpServletRequest request) {
        FileAccess access = authorize.toWrite(serviceId, rootId, "files.upload", request);
        return query.of(access, UploadTicket.requireSessionId(sessionId));
    }

    /**
     * One chunk.
     *
     * @param index  which chunk of the file. The offset is derived from it and never sent -
     *               an upload that trusts a client-supplied offset can be made to write
     *               anywhere in the file
     * @param sha256 the browser's hash of these bytes, forwarded verbatim so the node checks
     *               the whole path and not just this hop
     */
    @PostMapping(path = "/services/{serviceId}/files/uploads/{sessionId}/chunks",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ChunkReceipt chunk(@PathVariable UUID serviceId,
                              @PathVariable String sessionId,
                              @RequestParam(name = "rootId", required = false) String rootId,
                              @RequestParam(name = "index") long index,
                              @RequestParam(name = "sha256", required = false) String sha256,
                              @RequestParam(name = "chunk") MultipartFile chunk,
                              HttpServletRequest request) {
        FileAccess access = authorize.toWrite(serviceId, rootId, "files.upload", request);
        return receive.accept(access, UploadTicket.requireSessionId(sessionId), index, sha256,
                bytesOf(chunk));
    }

    /** Assemble the parts, verify the checksum, move the file into place. */
    @PostMapping(path = "/services/{serviceId}/files/uploads/{sessionId}/complete",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FileEntryView complete(@PathVariable UUID serviceId,
                                  @PathVariable String sessionId,
                                  @RequestParam(name = "rootId", required = false) String rootId,
                                  HttpServletRequest request) {
        FileAccess access = authorize.toWrite(serviceId, rootId, "files.upload", request);
        return finish.complete(access, UploadTicket.requireSessionId(sessionId));
    }

    /** The customer cancelled, or navigated away. Drops the parts now. */
    @PostMapping(path = "/services/{serviceId}/files/uploads/{sessionId}/abort",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public UploadProgress abort(@PathVariable UUID serviceId,
                                @PathVariable String sessionId,
                                @RequestParam(name = "rootId", required = false) String rootId,
                                HttpServletRequest request) {
        FileAccess access = authorize.toWrite(serviceId, rootId, "files.upload", request);
        String id = UploadTicket.requireSessionId(sessionId);
        abandon.abandon(access, id);
        return UploadProgress.unknown(id, 0, 0);
    }

    private static byte[] bytesOf(MultipartFile chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return new byte[0];
        }
        try {
            return chunk.getBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The uploaded chunk could not be read", unreadable);
        }
    }
}
