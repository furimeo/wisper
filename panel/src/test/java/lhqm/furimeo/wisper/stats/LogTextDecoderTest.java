package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * A chunk boundary can fall inside a character, and {@code stats.proto} says so out loud.
 *
 * <p>The symptom of getting this wrong is a replacement character every few hundred bytes
 * in any log that is not pure ASCII, which looks like the application's fault and is not.
 */
class LogTextDecoderTest {

    private final LogTextDecoder decoder = new LogTextDecoder();

    @Test
    void asciiPassesStraightThrough() {
        assertThat(decoder.decode("npm install\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("npm install\n");
    }

    @Test
    void aCharacterSplitAcrossTwoChunksIsHeldBackAndThenCompleted() {
        byte[] whole = "起動しました".getBytes(StandardCharsets.UTF_8);
        // Cut inside the first character, which is three bytes.
        byte[] head = Arrays.copyOfRange(whole, 0, 2);
        byte[] tail = Arrays.copyOfRange(whole, 2, whole.length);

        String first = decoder.decode(head);
        String second = decoder.decode(tail);

        assertThat(first).isEmpty();
        assertThat(first + second).isEqualTo("起動しました");
    }

    @Test
    void aFourByteCharacterSplitInThePenultimateBytePositionSurvives() {
        byte[] whole = "🚀 deploying".getBytes(StandardCharsets.UTF_8);
        String out = decoder.decode(Arrays.copyOfRange(whole, 0, 3))
                + decoder.decode(Arrays.copyOfRange(whole, 3, whole.length));

        assertThat(out).isEqualTo("🚀 deploying");
    }

    @Test
    void aStrayByteIsReplacedRatherThanStoppingTheRestOfTheLine() {
        String out = decoder.decode(new byte[] {'o', 'k', (byte) 0xFF, 'a', 'y'});

        assertThat(out).startsWith("ok").endsWith("ay");
    }

    @Test
    void whateverIsHeldBackAtTheEndIsFlushedRatherThanDropped() {
        byte[] whole = "終".getBytes(StandardCharsets.UTF_8);
        decoder.decode(Arrays.copyOfRange(whole, 0, 1));

        assertThat(decoder.flush()).isNotEmpty();
        assertThat(decoder.flush()).isEmpty();
    }

    @Test
    void anEmptyChunkIsNotAnError() {
        assertThat(decoder.decode(new byte[0])).isEmpty();
        assertThat(decoder.decode(null)).isEmpty();
    }
}
