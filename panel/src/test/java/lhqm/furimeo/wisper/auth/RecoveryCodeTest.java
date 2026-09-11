package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The code somebody reads off a printout a year after they printed it.
 *
 * <p>Most of what is tested here is about that person rather than about cryptography: the
 * hyphen they type or leave out, the lower case they use, the space they add. Every one
 * of those failing to match is a customer locked out of their own account while holding
 * the correct piece of paper.
 */
class RecoveryCodeTest {

    @Test
    @DisplayName("is ten characters of an alphabet with no look-alikes in it")
    void avoidsAmbiguousCharacters() {
        for (int i = 0; i < 500; i++) {
            assertThat(RecoveryCode.generate().value())
                    .hasSize(10)
                    .matches("[23456789A-HJ-NP-Z]{10}")
                    .doesNotContain("O")
                    .doesNotContain("I")
                    .doesNotContain("0")
                    .doesNotContain("1");
        }
    }

    @Test
    @DisplayName("prints as two groups of five")
    void printsGrouped() {
        RecoveryCode code = RecoveryCode.generate();

        assertThat(code.display()).hasSize(11).contains("-");
        assertThat(code.display().replace("-", "")).isEqualTo(code.value());
    }

    @Test
    @DisplayName("parses the printed form, the bare form, and what fingers add to both")
    void parsesWhatPeopleType() {
        RecoveryCode code = RecoveryCode.generate();
        String printed = code.display();

        assertThat(RecoveryCode.parse(printed)).contains(code);
        assertThat(RecoveryCode.parse(code.value())).contains(code);
        assertThat(RecoveryCode.parse(printed.toLowerCase())).contains(code);
        assertThat(RecoveryCode.parse("  " + printed + "  ")).contains(code);
        assertThat(RecoveryCode.parse(printed.replace("-", " "))).contains(code);
    }

    @Test
    @DisplayName("refuses anything that is not a code, without a database lookup")
    void refusesRubbish() {
        assertThat(RecoveryCode.parse(null)).isEmpty();
        assertThat(RecoveryCode.parse("")).isEmpty();
        assertThat(RecoveryCode.parse("ABCDE-FGH")).isEmpty();
        assertThat(RecoveryCode.parse("ABCDE-FGHJKL")).isEmpty();
        // O, I, 0 and 1 are not in the alphabet, so they are a transcription error with
        // no single obvious repair. Guessing would silently turn one code into another.
        assertThat(RecoveryCode.parse("ABCDE-FGH0J")).isEmpty();
        assertThat(RecoveryCode.parse("ABCDE-FGHIJ")).isEmpty();
    }

    @Test
    @DisplayName("hashes with the same digest the column holds")
    void hashesForStorage() {
        RecoveryCode code = RecoveryCode.generate();

        assertThat(code.hash()).isEqualTo(TokenDigest.of(code.value()));
        assertThat(code.hash()).matches("[0-9a-f]{64}");
        assertThat(code.hash()).doesNotContain(code.value());
    }

    @Test
    @DisplayName("the printed and the bare form hash the same, so either may be typed")
    void normalisationReachesTheHash() {
        RecoveryCode code = RecoveryCode.generate();

        assertThat(RecoveryCode.parse(code.display()).orElseThrow().hash())
                .isEqualTo(code.hash());
    }

    @Test
    @DisplayName("a batch of codes has no repeats")
    void isUnique() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generated.add(RecoveryCode.generate().value());
        }
        assertThat(generated).hasSize(1000);
    }
}
