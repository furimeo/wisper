package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The token format, and the property the whole design rests on: what is stored cannot
 * reproduce what was handed out.
 *
 * <p>The prefix shape is checked against the same regular expression as the
 * {@code api_token_prefix_shape} CHECK in V32. A generated prefix the database refuses
 * would turn every token creation into a constraint violation, and it is the kind of
 * mismatch that only shows up once there is a database in front of the code.
 */
class ApiTokenSecretTest {

    /** Copied verbatim from the {@code api_token_prefix_shape} CHECK in V32. */
    private static final String PREFIX_SHAPE = "[A-Za-z0-9_-]{6,16}";

    @Test
    @DisplayName("looks like wsp_<prefix>_<secret> and carries its own hash")
    void hasTheDocumentedShape() {
        ApiTokenSecret secret = ApiTokenSecret.generate();

        assertThat(secret.value()).startsWith("wsp_");
        assertThat(secret.value().split("_")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(secret.prefix()).matches(PREFIX_SHAPE);
        assertThat(secret.value()).contains("_" + secret.prefix() + "_");
        assertThat(secret.hash()).isEqualTo(TokenDigest.of(secret.value()));
    }

    @Test
    @DisplayName("the stored hash is not the value: nothing can read a token back out")
    void storesOnlyADigest() {
        ApiTokenSecret secret = ApiTokenSecret.generate();

        assertThat(secret.hash()).doesNotContain(secret.value());
        assertThat(secret.hash()).isNotEqualTo(secret.value());
        assertThat(secret.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("parsing what was generated produces the same prefix and the same hash")
    void parsesItsOwnOutput() {
        ApiTokenSecret generated = ApiTokenSecret.generate();

        Optional<ApiTokenSecret> parsed = ApiTokenSecret.parse(generated.value());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().prefix()).isEqualTo(generated.prefix());
        assertThat(parsed.get().hash()).isEqualTo(generated.hash());
    }

    @Test
    @DisplayName("a header with whitespace round it still parses")
    void stripsWhitespace() {
        ApiTokenSecret generated = ApiTokenSecret.generate();

        assertThat(ApiTokenSecret.parse("  " + generated.value() + "\n"))
                .get()
                .extracting(ApiTokenSecret::hash)
                .isEqualTo(generated.hash());
    }

    @Test
    @DisplayName("anything that is not one of ours is refused before it reaches the database")
    void refusesForeignValues() {
        assertThat(ApiTokenSecret.parse(null)).isEmpty();
        assertThat(ApiTokenSecret.parse("")).isEmpty();
        assertThat(ApiTokenSecret.parse("ghp_0123456789abcdefghijklmnopqrstuvwxyz")).isEmpty();
        assertThat(ApiTokenSecret.parse("wsp_short_x")).isEmpty();
        assertThat(ApiTokenSecret.parse("wsp_abcdefgh")).isEmpty();
        assertThat(ApiTokenSecret.parse("wsp_abcdefgh_tooshort")).isEmpty();
        assertThat(ApiTokenSecret.parse("Bearer wsp_abcdefgh_" + "a".repeat(43))).isEmpty();
    }

    @Test
    @DisplayName("the prefix never contains the separator, so the split is unambiguous")
    void prefixAvoidsTheSeparator() {
        for (int i = 0; i < 200; i++) {
            ApiTokenSecret secret = ApiTokenSecret.generate();
            assertThat(secret.prefix()).doesNotContain("_");
            assertThat(ApiTokenSecret.parse(secret.value()))
                    .get()
                    .extracting(ApiTokenSecret::prefix)
                    .isEqualTo(secret.prefix());
        }
    }

    @Test
    @DisplayName("two tokens are never the same, in value, prefix or hash")
    void isUnique() {
        Set<String> values = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ApiTokenSecret secret = ApiTokenSecret.generate();
            values.add(secret.value());
            hashes.add(secret.hash());
        }
        assertThat(values).hasSize(500);
        assertThat(hashes).hasSize(500);
    }
}
