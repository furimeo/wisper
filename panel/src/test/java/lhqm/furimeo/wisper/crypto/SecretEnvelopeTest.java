package lhqm.furimeo.wisper.crypto;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope format is the one thing the database and the panel both parse, so the
 * cases below are exactly the ones where they could disagree: the CHECK constraint's
 * expression, base64url's alphabet, and a plaintext value in an encrypted column.
 */
class SecretEnvelopeTest {

    /** Copied verbatim from the {@code *_is_envelope} CHECK constraints in V21 and V24-V26. */
    private static final Pattern DATABASE_CHECK =
            Pattern.compile("^v[0-9]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static byte[] nonce() {
        byte[] nonce = new byte[SecretEnvelope.NONCE_BYTES];
        for (int i = 0; i < nonce.length; i++) {
            // 0xF8 upwards, so the base64 output uses the two characters standard base64
            // spells "+" and "/" and url-safe base64 spells "-" and "_". If the encoder
            // were ever switched back, this is the test that fails.
            nonce[i] = (byte) (0xF8 + (i % 8));
        }
        return nonce;
    }

    @Test
    void textMatchesTheConstraintTheDatabaseEnforces() {
        SecretEnvelope envelope = new SecretEnvelope(1, nonce(), "ciphertext".getBytes(StandardCharsets.UTF_8));

        assertThat(DATABASE_CHECK.matcher(envelope.text()).matches())
                .as("the envelope the panel writes must satisfy the CHECK the column has")
                .isTrue();
    }

    @Test
    void survivesARoundTrip() {
        byte[] ciphertext = {0, 1, 2, 3, (byte) 0xFE, (byte) 0xFF};
        SecretEnvelope original = new SecretEnvelope(7, nonce(), ciphertext);

        SecretEnvelope parsed = SecretEnvelope.parse(original.text());

        assertThat(parsed.keyVersion()).isEqualTo(7);
        assertThat(parsed.nonce()).isEqualTo(nonce());
        assertThat(parsed.ciphertext()).isEqualTo(ciphertext);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void isUnpaddedSoTheAlphabetStaysInsideTheCheck() {
        // One byte of ciphertext is the case standard base64 pads with "==", which the
        // CHECK rejects outright.
        SecretEnvelope envelope = new SecretEnvelope(1, nonce(), new byte[] {42});

        assertThat(envelope.text()).doesNotContain("=");
        assertThat(DATABASE_CHECK.matcher(envelope.text()).matches()).isTrue();
    }

    @Test
    void rejectsPlaintextInAnEncryptedColumn() {
        assertThat(SecretEnvelope.looksLikeEnvelope("hunter2")).isFalse();

        assertThatThrownBy(() -> SecretEnvelope.parse("hunter2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plaintext value");
    }

    @Test
    void rejectsAnEnvelopeWithTheWrongNonceLength() {
        assertThatThrownBy(() -> new SecretEnvelope(1, new byte[8], new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 bytes");
    }

    @Test
    void rejectsAKeyVersionBelowOne() {
        assertThatThrownBy(() -> new SecretEnvelope(0, nonce(), new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start at 1");
    }

    @Test
    void doesNotShareItsArraysWithTheCaller() {
        byte[] mutable = nonce();
        SecretEnvelope envelope = new SecretEnvelope(1, mutable, new byte[] {1});
        String before = envelope.text();

        mutable[0] = 0;
        envelope.nonce()[1] = 0;

        assertThat(envelope.text()).isEqualTo(before);
    }
}
