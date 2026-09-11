package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * An environment value the panel must be able to hand to a node and must never show back
 * to a browser.
 *
 * <p>{@code value} is always an envelope - {@code v1.<nonce>.<ciphertext>}, AES-GCM - and
 * the {@code secret_value_is_envelope} CHECK rejects anything that is not shaped like one.
 * That constraint catches the mistake that actually happens: a plaintext value written
 * into the encrypted column by a code path that forgot the cipher.
 *
 * <p>This record therefore holds ciphertext, and holding ciphertext is the point. Nothing
 * in this package decrypts it: the value is read back exactly once, by the spec builder,
 * on its way to the node that needs it. The screens read {@link SecretView}, which is a
 * projection with no value column in it at all - a shape that cannot leak a secret because
 * it never carries one.
 *
 * @param value         an encrypted envelope, never plaintext
 * @param lastRotatedAt when the value was last replaced; null for one that has never
 *                      changed since it was created. The panel shows this instead of the
 *                      value, because "set three months ago" is the useful half of what a
 *                      customer wanted to know.
 */
public record Secret(
        @Id UUID id,
        UUID serviceId,
        String name,
        String value,
        boolean buildTime,
        Instant lastRotatedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A new secret. {@code envelope} is already encrypted. */
    public static Secret of(UUID id, UUID serviceId, String name, String envelope,
                            boolean buildTime) {
        return new Secret(id, serviceId, name, envelope, buildTime, null, null, null, null);
    }

    /** The same secret with a new value, stamped as rotated. */
    public Secret rotated(String envelope, boolean newBuildTime, Instant at) {
        return new Secret(id, serviceId, name, envelope, newBuildTime, at, createdAt, updatedAt,
                version);
    }

    /**
     * Deliberately overridden.
     *
     * <p>{@code toString} on a record prints every component, and a record holding a
     * secret ends up in a log line, an exception message or a debugger the day something
     * goes wrong. The ciphertext is not the plaintext, but a value that must not be shown
     * back to a browser has no business being printed anywhere either.
     */
    @Override
    public String toString() {
        return "Secret[" + name + " on service " + serviceId + "]";
    }
}
