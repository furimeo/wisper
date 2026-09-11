package lhqm.furimeo.wisper.backup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;

/**
 * A real, reversible {@link SecretCipher} for the tests in this package.
 *
 * <p>A Mockito stub returning {@code "encrypted"} would pass every assertion here while
 * proving none of them. The two properties that matter are that what is stored is
 * <em>shaped like</em> an envelope - the same shape the
 * {@code backup_destination_secret_is_envelope} CHECK enforces - and that it decrypts back
 * to exactly what went in. So this does both, with a keystream nobody would mistake for
 * security.
 */
final class RecordingCipher implements SecretCipher {

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
