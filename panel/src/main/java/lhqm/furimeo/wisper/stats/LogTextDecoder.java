package lhqm.furimeo.wisper.stats;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns a stream of byte chunks into text without cutting a character in half.
 *
 * <p>{@code LogChunk.data} is bytes, and {@code stats.proto} says why: a container writes
 * whatever it likes, a chunk ends wherever the node's buffer filled, and that boundary
 * routinely lands in the middle of a multi-byte character. Decoding each chunk on its own
 * with {@code new String(bytes, UTF_8)} turns every such boundary into a replacement
 * character, which in a log of Japanese or of box-drawing output is one per screenful.
 *
 * <p>So the decoder is kept across chunks and the trailing incomplete sequence is held
 * back until the bytes that finish it arrive. That is the entire class.
 *
 * <p>Invalid bytes are replaced rather than raising: a customer's application really can
 * write a stray {@code 0xFF}, and a log viewer that refuses to render the rest of the line
 * because of it is worse than a visible replacement character.
 *
 * <p>Not thread-safe, and does not need to be: one instance belongs to one subscription,
 * and chunks for a subscription arrive in order on one thread.
 */
public class LogTextDecoder {

    /**
     * Longest incomplete sequence UTF-8 can produce. Anything larger than this held back
     * would mean the stream is not UTF-8 at all, in which case the decoder replaces and
     * moves on rather than growing a buffer forever.
     */
    private static final int MAX_PENDING_BYTES = 4;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    private byte[] pending = new byte[0];

    /**
     * Decodes what can be decoded and keeps the rest for the next chunk.
     *
     * @return the text so far, possibly empty when the whole chunk was the first bytes of a
     *         character
     */
    public String decode(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return "";
        }
        ByteBuffer input = ByteBuffer.allocate(pending.length + chunk.length);
        input.put(pending).put(chunk).flip();

        CharBuffer output = CharBuffer.allocate(input.remaining() + 1);
        decoder.decode(input, output, false);
        output.flip();

        int remaining = input.remaining();
        if (remaining > MAX_PENDING_BYTES) {
            // Not the tail of a character: the stream is not UTF-8. Flush what is left as
            // replacement characters rather than accumulating bytes nothing will complete.
            byte[] stuck = new byte[remaining];
            input.get(stuck);
            pending = new byte[0];
            return output + new String(stuck, StandardCharsets.UTF_8);
        }
        pending = new byte[remaining];
        input.get(pending);
        return output.toString();
    }

    /**
     * Whatever is still held back, as replacement characters.
     *
     * <p>Called when the source ends. A truncated character at the very end of a log is
     * real information - the process was killed mid-write - and silently dropping it hides
     * that.
     */
    public String flush() {
        if (pending.length == 0) {
            return "";
        }
        String tail = new String(pending, StandardCharsets.UTF_8);
        pending = new byte[0];
        return tail;
    }
}
