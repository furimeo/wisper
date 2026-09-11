package lhqm.furimeo.wisper.auth;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;

/**
 * A real, reversible {@link SecretCipher} for the tests in this package.
 *
 * <p>A Mockito stub that returned {@code "encrypted"} and echoed it back would pass every
 * assertion about two-factor enrolment while proving none of them: the enrolment writes
 * {@code account.totp_secret}, which has an {@code *_is_envelope} CHECK on it, and the
 * challenge reads that column back and expects base32 out. So this does both properties
 * for real - the stored text is shaped like an envelope, and it decrypts to exactly what
 * went in - with a keystream nobody could mistake for security.
 */
final class ReversibleCipher implements SecretCipher {

    private static final int KEY_VERSION = 1;

    /** Stands in for GCM's authentication tag, so an empty plaintext still has a body. */
    private static final byte MARKER = 0x2A;

    private final AtomicInteger nonceCounter = new AtomicInteger();

    @Override
    public String encrypt(String plaintext) {
        byte[] nonce = new byte[SecretEnvelope.NONCE_BYTES];
        int counter = nonceCounter.incrementAndGet();
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (counter + i);
        }
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = new byte[bytes.length + 1];
        ciphertext[0] = MARKER;
        for (int i = 0; i < bytes.length; i++) {
            ciphertext[i + 1] = (byte) (bytes[i] ^ nonce[i % nonce.length]);
        }
        return new SecretEnvelope(KEY_VERSION, nonce, ciphertext).text();
    }

    @Override
    public String decrypt(String envelope) {
        SecretEnvelope parsed = SecretEnvelope.parse(envelope);
        byte[] nonce = parsed.nonce();
        byte[] ciphertext = parsed.ciphertext();
        byte[] bytes = new byte[ciphertext.length - 1];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (ciphertext[i + 1] ^ nonce[i % nonce.length]);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int currentKeyVersion() {
        return KEY_VERSION;
    }
}
