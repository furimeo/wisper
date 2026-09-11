package lhqm.furimeo.wisper.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * The value of an API token, in the three forms the panel needs at once: what the
 * customer is shown, what goes in {@code api_token.token_prefix}, and what goes in
 * {@code api_token.token_hash}.
 *
 * <h2>The shape</h2>
 *
 * <pre>
 *   wsp_a7Kd93Lm_x9Qv2Nb4TzR8sYpW1cE6uHgJfKmDaZo0iLnBvXsQwE
 *   ---  --------  ---------------------------------------
 *   scheme  prefix                  secret
 * </pre>
 *
 * <p>The scheme prefix is there so a token found in a log or a paste is recognisable as
 * one, which is what makes automated secret scanning possible and what lets a person
 * reading a support ticket know to redact it.
 *
 * <p>The middle group is stored in the clear. It is the only way to answer "somebody
 * pasted <em>this</em> token into a public repository, which row is it" without knowing
 * the token, and it is what distinguishes two entries in a list that the owner named
 * "ci" and "ci-2". Eight characters of the alphabet below is forty-eight bits, which is
 * far too little to guess a token from and far more than enough to be unique in one
 * account's list.
 *
 * <p>The last group is the secret. Two hundred and fifty-six bits from
 * {@link SecureRandom}, base64url so the whole token stays copy-pasteable and matches the
 * {@code api_token_prefix_shape} CHECK's alphabet.
 *
 * @param value  the whole token, which exists in memory for one HTTP response and is
 *               never written anywhere
 * @param prefix the middle group, stored in the clear
 * @param hash   SHA-256 of {@code value}, the only part that is persisted
 */
public record ApiTokenSecret(String value, String prefix, String hash) {

    /** Marks the string as a wisper token wherever it turns up. */
    public static final String SCHEME = "wsp";

    /** Matches the 6-16 character window {@code api_token_prefix_shape} allows. */
    private static final int PREFIX_CHARS = 8;

    /**
     * Letters and digits only for the prefix, even though the CHECK would allow
     * {@code _} and {@code -}.
     *
     * <p>{@code _} is the separator. A prefix that could contain one would make the token
     * ambiguous to split, and the version of that bug which survives review is the one
     * where nine tokens in ten parse correctly.
     */
    private static final String PREFIX_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 256 bits. base64url of 32 bytes is 43 characters with no padding. */
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public ApiTokenSecret {
        if (value == null || prefix == null || hash == null) {
            throw new IllegalArgumentException("An API token secret has three parts, all present");
        }
    }

    /** A brand new token. The caller shows {@link #value()} once and then forgets it. */
    public static ApiTokenSecret generate() {
        StringBuilder prefix = new StringBuilder(PREFIX_CHARS);
        for (int i = 0; i < PREFIX_CHARS; i++) {
            prefix.append(PREFIX_ALPHABET.charAt(RANDOM.nextInt(PREFIX_ALPHABET.length())));
        }
        byte[] secretBytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secretBytes);

        String value = SCHEME + "_" + prefix + "_" + ENCODER.encodeToString(secretBytes);
        return new ApiTokenSecret(value, prefix.toString(), TokenDigest.of(value));
    }

    /**
     * Reads a token off an {@code Authorization} header.
     *
     * <p>Returns empty for anything that is not shaped like one of ours, so the
     * authentication filter can refuse it without hashing it and without a database
     * round trip. That matters: an endpoint anybody can reach would otherwise turn every
     * malformed header into a query.
     */
    public static Optional<ApiTokenSecret> parse(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        String candidate = presented.strip();
        // Limit 3: base64url uses "_", so everything after the second separator is the
        // secret, underscores included.
        String[] parts = candidate.split("_", 3);
        if (parts.length != 3 || !SCHEME.equals(parts[0])) {
            return Optional.empty();
        }
        if (!parts[1].matches("[A-Za-z0-9]{6,16}") || !parts[2].matches("[A-Za-z0-9_-]{32,64}")) {
            return Optional.empty();
        }
        return Optional.of(new ApiTokenSecret(candidate, parts[1], TokenDigest.of(candidate)));
    }
}
