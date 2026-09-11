package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the panel will and will not accept as a password.
 *
 * <p>The seventy-two byte ceiling is the one worth a test of its own. BCrypt hashes the
 * first 72 bytes and ignores the rest, so accepting a longer passphrase would mean the
 * owner could sign in with a truncation of it and never know - a silent weakening of the
 * exact people who were trying hardest.
 */
class PasswordPolicyTest {

    @Test
    @DisplayName("a phrase of a reasonable length is accepted with no composition rules")
    void acceptsAPassphrase() {
        assertThat(PasswordPolicy.rejectionReason("correct horse battery staple",
                "someone@example.com")).isEmpty();
        // No capital, no digit, no symbol required: length is what costs an attacker
        // something, and composition rules push people towards "Password1!".
        assertThat(PasswordPolicy.rejectionReason("aaaaaaaaaaaa", "a@b.example")).isEmpty();
    }

    @Test
    @DisplayName("eleven characters is refused, twelve is not")
    void enforcesTheMinimum() {
        assertThat(PasswordPolicy.rejectionReason("a".repeat(11), "a@b.example")).isPresent();
        assertThat(PasswordPolicy.rejectionReason("a".repeat(12), "a@b.example")).isEmpty();
    }

    @Test
    @DisplayName("seventy-three bytes is refused rather than silently truncated by BCrypt")
    void enforcesTheBcryptCeiling() {
        assertThat(PasswordPolicy.rejectionReason("a".repeat(72), "a@b.example")).isEmpty();
        assertThat(PasswordPolicy.rejectionReason("a".repeat(73), "a@b.example"))
                .get()
                .asString()
                .contains("72 bytes");
    }

    @Test
    @DisplayName("the ceiling is bytes, not characters, because that is what BCrypt counts")
    void measuresInUtf8Bytes() {
        // Nineteen four-byte emoji is 76 bytes and only 38 chars: a passphrase that
        // looks well short of the limit and is not. Built from the code point rather
        // than written as a literal, so the test does not depend on the source encoding.
        String emoji = new String(Character.toChars(0x1F600)).repeat(19);
        assertThat(emoji).hasSizeLessThan(PasswordPolicy.MAXIMUM_BYTES);
        assertThat(PasswordPolicy.rejectionReason(emoji, "a@b.example")).isPresent();
    }

    @Test
    @DisplayName("the address itself is the first thing anybody guesses")
    void refusesTheEmailAddress() {
        assertThat(PasswordPolicy.rejectionReason("someone@example.com", "someone@example.com"))
                .isPresent();
        assertThat(PasswordPolicy.rejectionReason("SomeOne@Example.com", "someone@example.com"))
                .isPresent();
    }

    @Test
    @DisplayName("nothing at all is refused with a sentence rather than an exception")
    void refusesEmpty() {
        assertThat(PasswordPolicy.rejectionReason(null, "a@b.example")).isPresent();
        assertThat(PasswordPolicy.rejectionReason("", "a@b.example")).isPresent();
        assertThat(PasswordPolicy.rejectionReason("            ", "a@b.example")).isPresent();
    }
}
