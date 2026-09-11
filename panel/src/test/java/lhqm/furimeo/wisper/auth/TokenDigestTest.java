package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hash behind {@code session.session_id_hash}, {@code api_token.token_hash} and
 * {@code account_recovery_code.code_hash}.
 *
 * <p>Checked against the published SHA-256 vectors rather than against itself. The column
 * is written by this code and read by this code, so a wrong-but-consistent digest would
 * work perfectly until the day somebody had to match a value against one produced
 * anywhere else - a leak search, a migration, a second panel.
 */
class TokenDigestTest {

    @Test
    @DisplayName("is SHA-256, in lower-case hex")
    void matchesPublishedVectors() {
        assertThat(TokenDigest.of("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(TokenDigest.of("")).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("is sixty-four hex characters, whatever the input length")
    void hasAFixedShape() {
        assertThat(TokenDigest.of("short")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(TokenDigest.of("x".repeat(10_000))).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the same input always hashes the same way - there is no salt here")
    void isDeterministic() {
        // Deliberate: the values hashed are 256-bit randoms the panel generated, so
        // there is nothing to guess, and a salted hash could not be indexed - which
        // would make the per-request session lookup a table scan.
        assertThat(TokenDigest.of("wsp_a7Kd93Lm_value"))
                .isEqualTo(TokenDigest.of("wsp_a7Kd93Lm_value"));
    }

    @Test
    @DisplayName("one different character changes the digest completely")
    void isSensitiveToInput() {
        assertThat(TokenDigest.of("token-a")).isNotEqualTo(TokenDigest.of("token-b"));
        assertThat(TokenDigest.of("Token")).isNotEqualTo(TokenDigest.of("token"));
    }

    @Test
    @DisplayName("matches compares a value against a stored digest")
    void matchesStoredDigest() {
        String stored = TokenDigest.of("the-secret");

        assertThat(TokenDigest.matches("the-secret", stored)).isTrue();
        assertThat(TokenDigest.matches("the-secre", stored)).isFalse();
        assertThat(TokenDigest.matches("the-secret ", stored)).isFalse();
    }

    @Test
    @DisplayName("a null on either side is not a match, and is not an exception either")
    void handlesNulls() {
        assertThat(TokenDigest.matches(null, TokenDigest.of("x"))).isFalse();
        assertThat(TokenDigest.matches("x", null)).isFalse();
        assertThat(TokenDigest.matches(null, null)).isFalse();
    }

    @Test
    @DisplayName("a digest with the right length but wrong content does not match")
    void refusesALookalike() {
        String stored = TokenDigest.of("the-secret");
        String nearly = stored.substring(0, 63) + (stored.endsWith("a") ? "b" : "a");

        assertThat(TokenDigest.matches("the-secret", nearly)).isFalse();
    }
}
