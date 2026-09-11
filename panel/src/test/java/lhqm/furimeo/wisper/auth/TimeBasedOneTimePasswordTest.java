package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 6238 conformance and the drift window.
 *
 * <p>The vectors are the ones in RFC 6238 Appendix B, truncated from eight digits to six.
 * That truncation is not an approximation: HOTP takes the dynamic binary value modulo
 * {@code 10^digits}, so the six-digit code is the last six digits of the eight-digit one.
 * Checking against a published vector rather than against this implementation's own
 * output is the point - a self-consistent TOTP that no authenticator app agrees with
 * passes every test one could write about it.
 */
class TimeBasedOneTimePasswordTest {

    /** RFC 6238 Appendix B: the ASCII string "12345678901234567890" as an HMAC-SHA1 key. */
    private static final TotpSecret RFC_SECRET =
            new TotpSecret("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @ParameterizedTest(name = "T={0} produces {1}")
    @CsvSource({
        "59,            287082",
        "1111111109,    081804",
        "1111111111,    050471",
        "1234567890,    005924",
        "2000000000,    279037",
        "20000000000,   353130"
    })
    @DisplayName("matches the RFC 6238 test vectors")
    void matchesRfcVectors(long epochSeconds, String expected) {
        assertThat(TimeBasedOneTimePassword.codeAt(RFC_SECRET, Instant.ofEpochSecond(epochSeconds)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("keeps the leading zero, because 005924 and 5924 are different codes")
    void padsToSixDigits() {
        assertThat(TimeBasedOneTimePassword.codeAt(RFC_SECRET, Instant.ofEpochSecond(1234567890)))
                .hasSize(6)
                .startsWith("00");
    }

    @Test
    @DisplayName("the step advances every thirty seconds, on the half minute")
    void stepBoundary() {
        assertThat(TimeBasedOneTimePassword.stepAt(Instant.ofEpochSecond(29))).isZero();
        assertThat(TimeBasedOneTimePassword.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(TimeBasedOneTimePassword.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
        assertThat(TimeBasedOneTimePassword.stepAt(Instant.ofEpochSecond(60))).isEqualTo(2);
    }

    @Test
    @DisplayName("with one step of drift, the previous and next codes are accepted")
    void acceptsOneStepEitherSide() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String previous = codeAtStepOffset(now, -1);
        String current = TimeBasedOneTimePassword.codeAt(RFC_SECRET, now);
        String next = codeAtStepOffset(now, 1);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, previous, now, 1)).isTrue();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, current, now, 1)).isTrue();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, next, now, 1)).isTrue();
    }

    @Test
    @DisplayName("two steps away is outside a one-step window")
    void refusesBeyondTheWindow() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, codeAtStepOffset(now, -2), now, 1))
                .isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, codeAtStepOffset(now, 2), now, 1))
                .isFalse();
    }

    @Test
    @DisplayName("a window of zero accepts only the current step")
    void zeroDriftIsExact() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET,
                TimeBasedOneTimePassword.codeAt(RFC_SECRET, now), now, 0)).isTrue();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, codeAtStepOffset(now, -1), now, 0))
                .isFalse();
    }

    @Test
    @DisplayName("a wider window accepts what a narrow one refused")
    void widerWindowAcceptsMore() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String threeStepsAgo = codeAtStepOffset(now, -3);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, threeStepsAgo, now, 1)).isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, threeStepsAgo, now, 3)).isTrue();
    }

    @Test
    @DisplayName("a code typed with the spaces an app shows still verifies")
    void toleratesSpacingAndHyphens() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = TimeBasedOneTimePassword.codeAt(RFC_SECRET, now);
        String spaced = code.substring(0, 3) + " " + code.substring(3);
        String hyphenated = code.substring(0, 3) + "-" + code.substring(3);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, spaced, now, 1)).isTrue();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, hyphenated, now, 1)).isTrue();
    }

    @Test
    @DisplayName("anything that is not six digits is refused without reaching the HMAC")
    void refusesMalformedInput() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, null, now, 1)).isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, "", now, 1)).isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, "12345", now, 1)).isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, "1234567", now, 1)).isFalse();
        assertThat(TimeBasedOneTimePassword.matches(RFC_SECRET, "abcdef", now, 1)).isFalse();
    }

    @Test
    @DisplayName("two different secrets do not produce the same code")
    void secretsAreIndependent() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        TotpSecret other = TotpSecret.generate();

        assertThat(TimeBasedOneTimePassword.codeAt(other, now))
                .isNotEqualTo(TimeBasedOneTimePassword.codeAt(RFC_SECRET, now));
    }

    private static String codeAtStepOffset(Instant now, int steps) {
        return TimeBasedOneTimePassword.codeForStep(RFC_SECRET,
                TimeBasedOneTimePassword.stepAt(now) + steps);
    }
}
