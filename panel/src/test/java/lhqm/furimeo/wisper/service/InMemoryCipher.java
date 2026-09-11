package lhqm.furimeo.wisper.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;

/**
 * A real, reversible {@link SecretCipher} for the tests in this package.
 *
 * <p>A Mockito stub returning {@code "encrypted"} would pass every assertion this package
 * cares about while proving none of them: the interesting properties are that what is
 * stored is <em>shaped like</em> an envelope - the same shape the {@code *_is_envelope}
 * CHECK constraints enforce - and that it decrypts back to exactly what went in, including
 * a trailing newline in a PEM block. So this does both, with a keystream nobody would
 * mistake for security.
 *
 * <p>It is not AES and does not pretend to be. It exists so a use-case that writes
 * plaintext into an encrypted column fails a test rather than a constraint.
 */
final class InMemoryCipher implements SecretCipher {

    private static final int KEY_VERSION = 1;

    /** Stands in for GCM's authentication tag, so an empty plaintext still has a body. */
    private static final byte MARKER = 0x2A;

    /** Counts calls, so a test can assert a value was encrypted exactly once. */
    private final AtomicInteger encryptions = new AtomicInteger();

    /** A fresh nonce per call, so the same plaintext twice gives two different envelopes. */
    private final AtomicInteger nonceCounter = new AtomicInteger();

    @Override
    public String encrypt(String plaintext) {
        encryptions.incrementAndGet();
        byte[] nonce = new byte[SecretEnvelope.NONCE_BYTES];
        int counter = nonceCounter.incrementAndGet();
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (counter + i);
        }
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        // GCM's ciphertext is never empty because it carries a tag; the envelope record
        // enforces that, so an empty plaintext still has to produce at least one byte.
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

    int encryptions() {
        return encryptions.get();
    }
}
