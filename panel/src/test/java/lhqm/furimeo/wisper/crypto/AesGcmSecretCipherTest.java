package lhqm.furimeo.wisper.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The cipher that stands between a customer's credentials and anyone holding a database
 * dump.
 *
 * <p>The tests that matter are the ones about tampering. Confidentiality is AES's problem
 * and testing it here would only be testing the JDK; what this class has to get right is
 * refusing a value that has been altered, refusing one it has no key for, and never
 * producing the same ciphertext twice for the same input.
 */
class AesGcmSecretCipherTest {

    private static final String KEY_ONE = base64Key((byte) 0x11);
    private static final String KEY_TWO = base64Key((byte) 0x22);

    private final AesGcmSecretCipher cipher =
            new AesGcmSecretCipher(new CryptoSettings(1, Map.of(1, KEY_ONE)));

    @Test
    void aValueSurvivesTheRoundTripExactly() {
        String secret = "hunter2\nwith a newline and ünïcøde";

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void anEmptyValueIsAllowedAndComesBackEmpty() {
        // A customer who clears an optional secret stores an empty string, not a null, and
        // the column's CHECK still demands an envelope.
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
    }

    @Test
    void whatIsStoredHasTheShapeTheColumnConstraintsDemand() {
        assertThat(SecretEnvelope.looksLikeEnvelope(cipher.encrypt("value"))).isTrue();
    }

    @Test
    void theSameSecretTwiceGivesTwoDifferentEnvelopes() {
        // Otherwise "these two customers have the same password" is visible to anyone with
        // read access to the table, without breaking anything at all.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void anAlteredCiphertextIsRefusedRatherThanDecrypted() {
        SecretEnvelope stored = SecretEnvelope.parse(cipher.encrypt("transfer 10"));
        byte[] tampered = stored.ciphertext();
        tampered[0] ^= 0x01;
        String altered = new SecretEnvelope(stored.keyVersion(), stored.nonce(), tampered).text();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cipher.decrypt(altered))
                .withMessageContaining("did not");
    }

    @Test
    void aValueWrittenByAKeyThatIsNoLongerConfiguredSaysSo() {
        String underTwo = new AesGcmSecretCipher(new CryptoSettings(2, Map.of(2, KEY_TWO)))
                .encrypt("written by the new key");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cipher.decrypt(underTwo))
                .withMessageContaining("version 2");
    }

    @Test
    void aPlaintextValueInAnEncryptedColumnIsARefusalAndNotAGuess() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> cipher.decrypt("hunter2"))
                .withMessageContaining("plaintext value");
    }

    @Test
    void anOlderKeyKeepsWorkingAfterRotation() {
        String old = cipher.encrypt("written before the rotation");
        AesGcmSecretCipher rotated =
                new AesGcmSecretCipher(new CryptoSettings(2, Map.of(1, KEY_ONE, 2, KEY_TWO)));

        assertThat(rotated.decrypt(old)).isEqualTo("written before the rotation");
        assertThat(SecretEnvelope.parse(rotated.encrypt("new")).keyVersion()).isEqualTo(2);
    }

    @Test
    void aKeyOfTheWrongLengthIsRefusedAtStartupAndNotAtFirstUse() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AesGcmSecretCipher(new CryptoSettings(1, Map.of(1, tooShort))))
                .withMessageContaining("AES-256 needs exactly 32");
    }

    @Test
    void aMissingCurrentKeyNamesTheVariableToSet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CryptoSettings(1, Map.of(1, "  ")))
                .withMessageContaining("WISPER_CRYPTO_KEY_1");
    }

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return Base64.getEncoder().encodeToString(key);
    }
}
