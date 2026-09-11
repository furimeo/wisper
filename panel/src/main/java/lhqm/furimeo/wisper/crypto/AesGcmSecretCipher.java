package lhqm.furimeo.wisper.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM over the {@link SecretEnvelope} format, using the JDK and nothing else.
 *
 * <h2>Why GCM and not something simpler</h2>
 *
 * <p>Every value this protects is one an attacker would rather change than read: a
 * webhook secret decides whether a deployment is authentic, a repository credential
 * decides what source is built. GCM authenticates as well as encrypts, so a tampered
 * ciphertext fails to decrypt instead of decrypting to something else. AES-CBC without a
 * MAC would hand back attacker-chosen plaintext for the same input.
 *
 * <h2>The nonce</h2>
 *
 * <p>Twelve random bytes per encryption, from {@link SecureRandom}, stored beside the
 * ciphertext. Reusing a nonce under one key is the one mistake GCM does not survive - it
 * leaks the XOR of two plaintexts and, worse, the authentication subkey - so it is
 * generated fresh on every call and never derived from anything about the value. At 96
 * bits the birthday bound is far beyond the number of secrets a hosting panel will ever
 * hold.
 *
 * <h2>Key versions</h2>
 *
 * <p>The version that encrypted a value travels in the envelope, so rotation is additive:
 * new writes use {@link CryptoSettings#currentKeyVersion()}, old values keep decrypting
 * under whichever key made them for as long as that key is still configured.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

    /** GCM's tag, in bits. 128 is the full tag; truncating it weakens forgery resistance. */
    private static final int TAG_BITS = 128;

    /** AES-256. Anything shorter is a configuration mistake rather than a choice. */
    private static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKeySpec> keys;
    private final int currentVersion;

    public AesGcmSecretCipher(CryptoSettings settings) {
        this.currentVersion = settings.currentKeyVersion();
        Map<Integer, SecretKeySpec> parsed = new HashMap<>();
        settings.keys().forEach((version, encoded) -> parsed.put(version, keyOf(version, encoded)));
        this.keys = Map.copyOf(parsed);
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Cannot encrypt null; an empty string is allowed");
        }
        byte[] nonce = new byte[SecretEnvelope.NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(currentVersion),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new SecretEnvelope(currentVersion, nonce, ciphertext).text();
        } catch (GeneralSecurityException failure) {
            // Not a bad input: the algorithm is fixed and the key was validated at startup,
            // so reaching here means the JVM's provider is not what it claimed to be.
            throw new IllegalStateException("AES-GCM encryption failed", failure);
        }
    }

    @Override
    public String decrypt(String envelope) {
        SecretEnvelope parsed = SecretEnvelope.parse(envelope);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyFor(parsed.keyVersion()),
                    new GCMParameterSpec(TAG_BITS, parsed.nonce()));
            return new String(cipher.doFinal(parsed.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            // The tag did not verify. Either the stored bytes were altered or the wrong key
            // is configured for this version, and returning something plausible for either
            // is worse than refusing.
            throw new IllegalStateException(
                    "A value encrypted with key version " + parsed.keyVersion() + " did not "
                            + "authenticate. It has been altered, or the key configured for that "
                            + "version is not the one that wrote it.", failure);
        }
    }

    @Override
    public int currentKeyVersion() {
        return currentVersion;
    }

    private SecretKeySpec keyFor(int version) {
        SecretKeySpec key = keys.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    "No key is configured for version " + version + ". Set "
                            + "wisper.crypto.keys." + version + " to the key that wrote these "
                            + "values; without it their contents are unrecoverable.");
        }
        return key;
    }

    private static SecretKeySpec keyOf(int version, String encoded) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(
                    "wisper.crypto.keys." + version + " is not base64. Generate one with "
                            + "`openssl rand -base64 32`.", notBase64);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "wisper.crypto.keys." + version + " decodes to " + raw.length + " bytes; "
                            + "AES-256 needs exactly " + KEY_BYTES + ".");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
