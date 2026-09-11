package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frame format, which is the thing that broke the predecessor.
 *
 * <p>Its terminal pumped a PTY through a socket with an unframed copy, so a resize was
 * indistinguishable from keystrokes and an exit was a screen that simply stopped (design
 * §11.5). Every test here is one half of the fix: a tag that means one thing, and a frame
 * that is not one of the three being refused rather than guessed at.
 */
class TerminalSocketFrameTest {

    @Test
    @DisplayName("keystrokes survive bytes that are not text")
    void keystrokesAreBytesAndNotAString() {
        // An escape sequence, a lone continuation byte, and a NUL: all three are things a
        // customer's clipboard produces and none of them are valid UTF-8 on their own.
        byte[] typed = {0x1B, '[', 'A', (byte) 0x80, 0x00, (byte) 0xFF};

        TerminalSocketFrame frame = decode(TerminalSocketFrame.BYTES, typed);

        assertThat(frame).isInstanceOf(TerminalSocketFrame.Keystrokes.class);
        assertThat(((TerminalSocketFrame.Keystrokes) frame).data()).isEqualTo(typed);
    }

    @Test
    @DisplayName("a resize is its own frame, not something typed into the shell")
    void aResizeIsItsOwnFrame() {
        TerminalSocketFrame frame = decode(TerminalSocketFrame.SIZE,
                new byte[] {0, (byte) 132, 0, 43});

        assertThat(frame).isEqualTo(new TerminalSocketFrame.Resize(132, 43));
    }

    @Test
    @DisplayName("a width past 127 columns is not a negative one")
    void sizesAreUnsigned() {
        // 0x01F4 is 500, the widest a PTY here will take. Read as a signed short a wide
        // terminal would arrive as a negative number and be clamped to nothing.
        TerminalSocketFrame frame = decode(TerminalSocketFrame.SIZE,
                new byte[] {0x01, (byte) 0xF4, 0, (byte) 200});

        assertThat(frame).isEqualTo(new TerminalSocketFrame.Resize(500, 200));
    }

    @Test
    @DisplayName("the browser's end frame carries nothing and says everything")
    void anEndFrameIsEmpty() {
        assertThat(decode(TerminalSocketFrame.END, new byte[0]))
                .isEqualTo(new TerminalSocketFrame.Exit());
    }

    @Test
    @DisplayName("output goes out as bytes behind the bytes tag")
    void outputIsTaggedAndUntouched() {
        byte[] printed = {(byte) 0xC3, 0x28, 0x1B, '['};

        ByteBuffer sent = TerminalSocketFrame.output(printed).getPayload();

        assertThat(sent.get()).isEqualTo(TerminalSocketFrame.BYTES);
        byte[] rest = new byte[sent.remaining()];
        sent.get(rest);
        assertThat(rest).isEqualTo(printed);
    }

    @Test
    @DisplayName("the size the PTY took travels with the container it is in")
    void readyCarriesTheSizeAndTheContainer() {
        ByteBuffer sent = TerminalSocketFrame.ready(120, 34, "c0ffee").getPayload();

        assertThat(sent.get()).isEqualTo(TerminalSocketFrame.SIZE);
        assertThat(Short.toUnsignedInt(sent.getShort())).isEqualTo(120);
        assertThat(Short.toUnsignedInt(sent.getShort())).isEqualTo(34);
        assertThat(StandardCharsets.UTF_8.decode(sent).toString()).isEqualTo("c0ffee");
    }

    @Test
    @DisplayName("an exit carries the code and the reason rather than just stopping")
    void exitCarriesTheCodeAndTheReason() {
        // 130 reads as Ctrl-C to anybody who has used a shell, which is the whole point of
        // reporting a code instead of leaving the screen to stop.
        ByteBuffer sent = TerminalSocketFrame.exit(130, "idle timeout").getPayload();

        assertThat(sent.get()).isEqualTo(TerminalSocketFrame.END);
        assertThat(sent.getInt()).isEqualTo(130);
        assertThat(StandardCharsets.UTF_8.decode(sent).toString()).isEqualTo("idle timeout");
    }

    @Test
    @DisplayName("an empty frame is refused rather than read as a keystroke")
    void anEmptyFrameIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> TerminalSocketFrame.decode(ByteBuffer.allocate(0)))
                .withMessageContaining("tag byte");
    }

    @Test
    @DisplayName("a tag this side does not know is refused rather than guessed at")
    void anUnknownTagIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decode((byte) 0x7F, new byte[] {1, 2}))
                .withMessageContaining("0x7f");
    }

    @Test
    @DisplayName("a size frame of the wrong length is refused, not padded")
    void aTruncatedResizeIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decode(TerminalSocketFrame.SIZE, new byte[] {0, 80}))
                .withMessageContaining("four bytes");
    }

    @Test
    @DisplayName("more input than a frame may carry is refused at the ceiling")
    void anOversizedFrameIsRefused() {
        byte[] enormous = new byte[TerminalSocketFrame.MAX_INPUT_BYTES + 1];

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decode(TerminalSocketFrame.BYTES, enormous))
                .withMessageContaining("ceiling");
    }

    @Test
    @DisplayName("a paste right at the ceiling is accepted, because customers do that")
    void aPasteAtTheCeilingIsAccepted() {
        byte[] pasted = new byte[TerminalSocketFrame.MAX_INPUT_BYTES];

        TerminalSocketFrame frame = decode(TerminalSocketFrame.BYTES, pasted);

        assertThat(((TerminalSocketFrame.Keystrokes) frame).data()).hasSize(pasted.length);
    }

    @Test
    @DisplayName("decoding leaves the caller's buffer where it found it")
    void decodingDoesNotConsumeTheCallersBuffer() {
        ByteBuffer message = frame(TerminalSocketFrame.BYTES, "ls\n".getBytes(StandardCharsets.UTF_8));

        TerminalSocketFrame.decode(message);

        // The container hands the same buffer to anything else that looks at the message;
        // a codec that drained it would leave the next reader with an empty frame.
        assertThat(message.position()).isZero();
        assertThat(message.remaining()).isEqualTo(4);
    }

    private static TerminalSocketFrame decode(byte tag, byte[] payload) {
        return TerminalSocketFrame.decode(frame(tag, payload));
    }

    private static ByteBuffer frame(byte tag, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + payload.length);
        buffer.put(tag).put(payload).flip();
        return buffer;
    }
}
