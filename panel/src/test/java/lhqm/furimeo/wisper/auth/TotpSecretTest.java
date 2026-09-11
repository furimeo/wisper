package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The base32 codec and the provisioning URI.
 *
 * <p>Both are wire formats read by software this project does not control - an
 * authenticator app on somebody's phone - so the tests check against a known encoding
 * rather than against a round trip. A codec that is wrong in both directions round-trips
 * perfectly and still produces a QR code no app can use.
 */
class TotpSecretTest {

    /** RFC 4648 base32 of the ASCII string "12345678901234567890". */
    private static final String KNOWN_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final TotpSecret KNOWN =
            new TotpSecret("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("encodes to the RFC 4648 alphabet, upper case, with no padding")
    void encodesToKnownBase32() {
        assertThat(KNOWN.base32()).isEqualTo(KNOWN_BASE32);
        assertThat(KNOWN.base32()).doesNotContain("=");
    }

    @Test
    @DisplayName("decodes what it encoded")
    void roundTrips() {
        TotpSecret generated = TotpSecret.generate();
        assertThat(TotpSecret.ofBase32(generated.base32()).value()).isEqualTo(generated.value());
    }

    @Test
    @DisplayName("accepts what a person types: lower case, spaces, hyphens, padding")
    void toleratesHumanInput() {
        String messy = "gezd gnbv-gy3t qojq gezdgnbvgy3tqojq==";
        assertThat(TotpSecret.ofBase32(messy).value()).isEqualTo(KNOWN.value());
    }

    @Test
    @DisplayName("refuses a character that is not in the alphabet, rather than skipping it")
    void refusesRubbish() {
        assertThatThrownBy(() -> TotpSecret.ofBase32("GEZD1NBV"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base32");
    }

    @Test
    @DisplayName("a generated secret is 160 bits and no two are alike")
    void generatesFullLengthSecrets() {
        TotpSecret first = TotpSecret.generate();
        TotpSecret second = TotpSecret.generate();

        assertThat(first.value()).hasSize(TotpSecret.LENGTH_BYTES);
        assertThat(first.value()).isNotEqualTo(second.value());
    }

    @Test
    @DisplayName("the record holds a copy, so a caller cannot alter the stored secret")
    void defensivelyCopies() {
        byte[] raw = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        TotpSecret secret = new TotpSecret(raw);

        raw[0] = 0;

        assertThat(secret.base32()).isEqualTo(KNOWN_BASE32);
    }

    @Test
    @DisplayName("the provisioning URI spells out every parameter an app might default")
    void provisioningUriIsExplicit() {
        String uri = KNOWN.provisioningUri("wisper", "someone@example.com");

        assertThat(uri).startsWith("otpauth://totp/wisper:someone%40example.com?");
        assertThat(uri).contains("secret=" + KNOWN_BASE32);
        assertThat(uri).contains("issuer=wisper");
        assertThat(uri).contains("algorithm=SHA1");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }

    @Test
    @DisplayName("an issuer with a space in it survives being put in a URI")
    void escapesTheIssuer() {
        String uri = KNOWN.provisioningUri("wisper staging", "a@b.example");

        assertThat(uri).contains("issuer=wisper%20staging");
        assertThat(uri).doesNotContain(" ");
    }

    @Test
    @DisplayName("the printed form is grouped in fours and says the same thing")
    void groupsForTyping() {
        assertThat(KNOWN.base32Grouped()).startsWith("GEZD GNBV GY3T QOJQ");
        assertThat(KNOWN.base32Grouped().replace(" ", "")).isEqualTo(KNOWN_BASE32);
    }

    @Test
    @DisplayName("an empty secret is refused, because it would be a usable HMAC key of nothing")
    void refusesEmpty() {
        assertThatThrownBy(() -> new TotpSecret(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TotpSecret.ofBase32("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
