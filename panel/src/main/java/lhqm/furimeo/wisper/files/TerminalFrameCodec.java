package lhqm.furimeo.wisper.files;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 between the browser and the PTY, in both directions.
 *
 * <p>A PTY carries bytes, not text. What a program writes is routinely an escape sequence
 * split across a read boundary and occasionally not valid UTF-8 at all; what a customer
 * types includes control characters and, on a phone, whatever the clipboard had in it. The
 * predecessor's terminal broke precisely because bytes were treated as a string somewhere in
 * the middle (design §11.5).
 *
 * <p>Server-Sent Events and form fields are both text transports, so the bytes are base64 on
 * the way across and decoded once at each end. That costs a third more bandwidth on terminal
 * output, which is a few kilobytes a second, and removes the entire class of bug.
 *
 * <p>Standard alphabet, with padding: {@code atob} and {@code btoa} in the browser expect it,
 * and xterm.js has no opinion.
 */
public final class TerminalFrameCodec {

    /**
     * Longest single keystroke payload accepted.
     *
     * <p>A paste is one request, and a customer really does paste a 200-line configuration
     * file into a shell. This is generous enough for that and small enough that a request
     * body cannot be used to hold a megabyte in the PTY's write buffer.
     */
    public static final int MAX_INPUT_BYTES = 64 * 1024;

    private TerminalFrameCodec() {
    }

    /** PTY output for an SSE frame. */
    public static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * A keystroke payload from the browser.
     *
     * @throws PathRejected if it is not base64, or is longer than a paste could be. Reusing
     *         the file manager's rejection keeps one shape of "the client sent something it
     *         should not have" in this package rather than two
     */
    public static byte[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new byte[0];
        }
        if (encoded.length() > MAX_INPUT_BYTES / 3 * 4 + 4) {
            throw PathRejected.malformed("",
                    "That is more input than one keystroke frame may carry.");
        }
        try {
            return Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException notBase64) {
            throw PathRejected.malformed("", "Terminal input has to be base64.");
        }
    }
}
