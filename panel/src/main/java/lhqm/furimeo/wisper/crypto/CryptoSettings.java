package lhqm.furimeo.wisper.crypto;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The keys that encrypt every reversible secret the panel stores.
 *
 * <p>Held as a map of version to key rather than as one key, because
 * {@link SecretEnvelope} stamps the version it used into every stored value. Rotating
 * means adding version 2, pointing {@code currentKeyVersion} at it, and leaving version 1
 * configured until the last row encrypted with it has been rewritten. Dropping the old key
 * before then does not lose the ciphertext - it loses the plaintext, permanently.
 *
 * <p>Keys are base64 of 32 raw bytes: AES-256. They are read from the environment and
 * never from a file in the repository. Generate one with:
 *
 * <pre>
 *   openssl rand -base64 32
 * </pre>
 *
 * <p>There is deliberately no default and no key generated at startup. A panel that
 * invented a key on boot would encrypt a customer's database password with it and then
 * be unable to read that password back after a restart, and the failure would arrive
 * weeks later as a service that cannot be redeployed.
 *
 * @param currentKeyVersion the version new values are encrypted with. Every older version
 *                          still listed in {@code keys} remains readable
 * @param keys              version to base64 of 32 bytes
 */
@ConfigurationProperties("wisper.crypto")
public record CryptoSettings(
        @DefaultValue("1") int currentKeyVersion,
        @DefaultValue Map<Integer, String> keys) {

    public CryptoSettings {
        if (currentKeyVersion < 1) {
            throw new IllegalArgumentException(
                    "wisper.crypto.current-key-version starts at 1, not " + currentKeyVersion);
        }
        // An unset environment variable binds to an empty string rather than to nothing,
        // so a blank value has to be treated as absent or the failure moves from this
        // message to "decodes to 0 bytes" somewhere less helpful.
        keys = keys == null ? Map.of() : keys.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!keys.containsKey(currentKeyVersion)) {
            throw new IllegalArgumentException(
                    "wisper.crypto.keys." + currentKeyVersion + " is not set, so nothing can be "
                            + "encrypted. Generate one with `openssl rand -base64 32` and pass it "
                            + "as WISPER_CRYPTO_KEY_" + currentKeyVersion + ". Do not put it in a "
                            + "file that is committed.");
        }
    }
}
