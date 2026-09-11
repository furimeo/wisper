package lhqm.furimeo.wisper.service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The shared secret a git provider signs its webhook deliveries with.
 *
 * <p>It lives here rather than inside the use-case that first needed it because two of
 * them need the same value to be generated the same way: a service is given one when it
 * is created, and given a new one when a customer rotates it after a leak. Two
 * generators would eventually disagree about length or alphabet, and the one that
 * produced the shorter secret would be the one nobody noticed.
 *
 * <p>256 bits, because this value is the whole of the authentication on
 * {@code POST /webhooks/**} - anyone holding it can queue a deployment.
 *
 * <p>URL-safe and unpadded so it survives being pasted into a provider's form, a shell
 * and a YAML file without anybody quoting it. It is encrypted rather than hashed before
 * it is stored, because verifying an HMAC needs the value back and a digest would be
 * useless for that.
 */
final class WebhookSecret {

    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecret() {
    }

    /** A fresh secret, in plaintext. The caller encrypts it before it is stored. */
    static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
