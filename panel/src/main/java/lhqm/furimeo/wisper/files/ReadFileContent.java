package lhqm.furimeo.wisper.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileChunk;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.ReadFile;

/**
 * Reads a file, or the first part of one.
 *
 * <p>Two callers with the same needs and one code path. A download streams every chunk into
 * the servlet's output stream and never holds the file in memory - a customer downloading a
 * 3 GB database dump must not cost the panel 3 GB. The inline editor asks for the first
 * megabyte, because opening a 400 MB log in CodeMirror hangs the tab and refusing to show
 * it at all is worse than showing the beginning.
 *
 * <p>The range exists for a third reason as well: a download that resumes after the
 * customer's connection dropped starts at the offset the browser already has.
 */
@Component
public class ReadFileContent {

    private final DispatchFileRequest dispatch;

    public ReadFileContent(DispatchFileRequest dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * Streams a file into an output stream.
     *
     * <p>Each chunk is written and flushed as it arrives, which is what applies
     * backpressure: the node's stream stalls while a slow phone catches up, rather than the
     * panel buffering the difference.
     *
     * @param offset   where to start, for a resumed download
     * @param maxBytes zero reads to the end
     */
    public void into(FileAccess access, RelativePath path, long offset, long maxBytes,
                     OutputStream out) {
        dispatch.stream(access, request(path, offset, maxBytes), event -> write(event, out));
    }

    /**
     * Reads the beginning of a file as text, for the editor.
     *
     * <p>Decoded as UTF-8 with replacement, because a customer really can open a binary file
     * by accident and the editor showing replacement characters is a better answer than an
     * exception. {@code truncated} tells the page to open read-only rather than let somebody
     * save the first megabyte over the whole file.
     */
    public FileText text(FileAccess access, RelativePath path, long maxBytes) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        dispatch.stream(access, request(path, 0, maxBytes), event -> write(event, collected));
        byte[] bytes = collected.toByteArray();
        return new FileText(path.value(), new String(bytes, StandardCharsets.UTF_8), bytes.length,
                bytes.length >= maxBytes);
    }

    private static FileRequest.Builder request(RelativePath path, long offset, long maxBytes) {
        return FileRequest.newBuilder().setRead(ReadFile.newBuilder()
                .setPath(path.value())
                .setOffset(Math.max(0, offset))
                .setMaxBytes(Math.max(0, maxBytes)));
    }

    /**
     * One event's worth of bytes.
     *
     * <p>Anything that is not a chunk is ignored rather than refused: the node terminates a
     * read with the last chunk, and a future event type on this stream is not a reason to
     * corrupt a download that is already half written.
     */
    private static void write(FileEvent event, OutputStream out) {
        if (event.getResultCase() != FileEvent.ResultCase.CHUNK) {
            return;
        }
        FileChunk chunk = event.getChunk();
        try {
            chunk.getData().writeTo(out);
            out.flush();
        } catch (IOException gone) {
            // The browser closed the download. Unchecked, so it unwinds through the gRPC
            // sink and DispatchFileRequest cancels the read on the node.
            throw new UncheckedIOException("The download's client has gone", gone);
        }
    }
}
