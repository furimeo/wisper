package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Bytes in, bytes out, unchanged.
 *
 * <p>The predecessor's terminal broke because a PTY's output was treated as text somewhere in
 * the middle: a resize was indistinguishable from keystrokes and an escape sequence split
 * across a read became mojibake (design §11.5). These are the two cases that would show it.
 */
class TerminalFrameCodecTest {

    @Test
    void anEscapeSequenceSurvivesTheRoundTrip() {
        byte[] clearScreen = {0x1b, '[', '2', 'J', 0x1b, '[', 'H'};

        assertThat(TerminalFrameCodec.decode(TerminalFrameCodec.encode(clearScreen)))
                .isEqualTo(clearScreen);
    }

    @Test
    void bytesThatAreNotValidUtf8SurviveTheRoundTrip() {
        byte[] notText = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41, (byte) 0x80};

        assertThat(TerminalFrameCodec.decode(TerminalFrameCodec.encode(notText)))
                .isEqualTo(notText);
    }

    @Test
    void aPastedMultiByteCharacterSurvives() {
        byte[] pasted = "echo 日本語 🚀".getBytes(StandardCharsets.UTF_8);

        assertThat(TerminalFrameCodec.decode(TerminalFrameCodec.encode(pasted)))
                .isEqualTo(pasted);
    }

    @Test
    void nothingIsAnEmptyFrameRatherThanAFailure() {
        assertThat(TerminalFrameCodec.decode(null)).isEmpty();
        assertThat(TerminalFrameCodec.decode("")).isEmpty();
        assertThat(TerminalFrameCodec.encode(new byte[0])).isEmpty();
    }

    @Test
    void somethingThatIsNotBase64IsRefused() {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> TerminalFrameCodec.decode("not base64 at all!!"));
    }

    @Test
    void aFrameLargerThanAPasteIsRefusedRatherThanBuffered() {
        String enormous = "A".repeat(TerminalFrameCodec.MAX_INPUT_BYTES * 2);

        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> TerminalFrameCodec.decode(enormous));
    }
}
