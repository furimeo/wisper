package lhqm.furimeo.wisper.files;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.springframework.web.socket.BinaryMessage;

/**
 * What a browser and the panel say to each other about a shell, on the wire.
 *
 * <p>Three tags, the same three in both directions, and they are the three in
 * {@code terminal.proto}: <strong>bytes</strong>, <strong>size</strong> and
 * <strong>end</strong>. The payload behind a tag differs by direction because each side
 * knows something the other does not - the panel names the container and reports the exit
 * code, the browser does neither - but the tag never means two things.
 *
 * <pre>
 * byte 0   tag
 *   0x00   bytes    -&gt;  keystrokes            &lt;-  PTY output
 *   0x01   size     -&gt;  u16 cols, u16 rows    &lt;-  u16 cols, u16 rows, UTF-8 container id
 *   0x02   end      -&gt;  (nothing)             &lt;-  i32 exit code, UTF-8 reason
 * </pre>
 *
 * <h2>Binary frames, not text</h2>
 *
 * <p>A PTY carries bytes. What a program writes is routinely an escape sequence split
 * across a read boundary and occasionally not valid UTF-8 at all; what a customer types
 * includes control characters and, on a phone, whatever the clipboard had in it. The
 * predecessor's terminal broke precisely because those bytes were treated as a string
 * somewhere in the middle (design §11.5). A WebSocket has a binary frame type, so the
 * base64 the Server-Sent Events transport needed - a third more bandwidth in both
 * directions, and a second place to get an encoding wrong - is gone with it.
 *
 * <h2>There is no "write whatever" frame</h2>
 *
 * <p>The predecessor pumped a PTY through a socket with an unframed copy, so a resize was
 * indistinguishable from keystrokes and the exit code was lost - the terminal simply
 * stopped. Everything crossing this socket is one of the three below, and a frame that is
 * none of them is refused rather than guessed at.
 */
public sealed interface TerminalSocketFrame {

    /** Keystrokes, a paste, or a key from the phone's extra key bar. */
    record Keystrokes(byte[] data) implements TerminalSocketFrame {
    }

    /**
     * The window changed.
     *
     * <p>Its own frame because a curses application that never hears about a resize draws
     * over itself, and the customer sees a broken screen rather than a wrong one. On a
     * phone this fires every time the software keyboard slides up.
     */
    record Resize(int columns, int rows) implements TerminalSocketFrame {
    }

    /** The customer is finished with this shell. Ends the PTY rather than the socket. */
    record Exit() implements TerminalSocketFrame {
    }

    /** PTY output, and everything the browser may send. */
    byte BYTES = 0x00;

    /** The size of the window, in both directions. */
    byte SIZE = 0x01;

    /** The shell is over: the browser asking, or the panel reporting. */
    byte END = 0x02;

    /**
     * Longest payload accepted in one frame from the browser.
     *
     * <p>A paste is one frame, and a customer really does paste a 200-line configuration
     * file into a shell. Generous enough for that, small enough that a socket cannot be
     * used to park a megabyte in the PTY's write buffer. The client splits anything larger
     * across frames; the container refuses it outright, which is why the limit is set on
     * the session as well as checked here.
     */
    int MAX_INPUT_BYTES = 64 * 1024;

    /** The ceiling the servlet container is given: the payload plus its tag. */
    int MAX_MESSAGE_BYTES = MAX_INPUT_BYTES + 1;

    /**
     * One frame from the browser.
     *
     * @throws IllegalArgumentException if it is empty, carries an unknown tag, is the
     *         wrong length for its tag, or is longer than a paste could be. Every one of
     *         those closes the socket: a client sending frames this side does not
     *         understand is a client whose next frame cannot be trusted either
     */
    static TerminalSocketFrame decode(ByteBuffer payload) {
        ByteBuffer frame = payload.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (!frame.hasRemaining()) {
            throw new IllegalArgumentException("A terminal frame needs at least a tag byte.");
        }
        byte tag = frame.get();
        return switch (tag) {
            case BYTES -> keystrokes(frame);
            case SIZE -> resize(frame);
            case END -> ended(frame);
            default -> throw new IllegalArgumentException(
                    "Unknown terminal frame tag 0x" + Integer.toHexString(tag & 0xFF) + ".");
        };
    }

    /** PTY output on its way to the browser. */
    static BinaryMessage output(byte[] data) {
        ByteBuffer frame = ByteBuffer.allocate(1 + data.length);
        frame.put(BYTES).put(data).flip();
        return new BinaryMessage(frame);
    }

    /**
     * The size the PTY actually took, and where it is.
     *
     * <p>Sent as soon as a browser attaches, including after a reconnect: xterm.js has to
     * be told the size the PTY settled on after clamping, or it wraps every line in the
     * wrong place and the screen redraws itself into nonsense.
     */
    static BinaryMessage ready(int columns, int rows, String containerId) {
        byte[] container = containerId == null
                ? new byte[0]
                : containerId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(5 + container.length).order(ByteOrder.BIG_ENDIAN);
        frame.put(SIZE).putShort((short) columns).putShort((short) rows).put(container).flip();
        return new BinaryMessage(frame);
    }

    /**
     * The shell ended, and how.
     *
     * <p>The code and the reason both travel, because "it stopped" is what the
     * predecessor's terminal told a customer and it is not an answer. 130 reads as Ctrl-C
     * to anyone who has used a shell; a reason is there when the platform ended the
     * session rather than the process.
     */
    static BinaryMessage exit(int code, String reason) {
        byte[] why = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(5 + why.length).order(ByteOrder.BIG_ENDIAN);
        frame.put(END).putInt(code).put(why).flip();
        return new BinaryMessage(frame);
    }

    private static TerminalSocketFrame keystrokes(ByteBuffer frame) {
        if (frame.remaining() > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("That is more input than one terminal frame may "
                    + "carry: " + frame.remaining() + " bytes against a ceiling of "
                    + MAX_INPUT_BYTES + ".");
        }
        byte[] data = new byte[frame.remaining()];
        frame.get(data);
        return new Keystrokes(data);
    }

    private static TerminalSocketFrame resize(ByteBuffer frame) {
        if (frame.remaining() != 4) {
            throw new IllegalArgumentException("A size frame is four bytes of columns and rows, "
                    + "not " + frame.remaining() + ".");
        }
        // Unsigned: a PTY has no negative dimension, and the clamp downstream wants a
        // number it can compare rather than a sign-extended surprise.
        return new Resize(Short.toUnsignedInt(frame.getShort()),
                Short.toUnsignedInt(frame.getShort()));
    }

    private static TerminalSocketFrame ended(ByteBuffer frame) {
        if (frame.hasRemaining()) {
            throw new IllegalArgumentException("An end frame from the browser carries nothing; "
                    + "this one carried " + frame.remaining() + " bytes.");
        }
        return new Exit();
    }
}
