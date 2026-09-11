package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two opaque strings a node holds. Both columns hold a digest and never the value, so
 * the properties worth pinning down are that the digest is what the column gets and the
 * text is never derivable from it.
 */
class NodeSecretTest {

    @Test
    void aBootstrapTokenIsRecognisableAndCarriesRealEntropy() {
        NodeSecret first = NodeSecret.bootstrapToken();
        NodeSecret second = NodeSecret.bootstrapToken();

        assertThat(first.text()).startsWith(NodeSecret.BOOTSTRAP_PREFIX);
        // The prefix is there so a token found in a shell history says what it is without
        // anybody having to try it.
        assertThat(first.text()).isNotEqualTo(second.text());
        assertThat(first.text().length()).isGreaterThan(40);
    }

    @Test
    void aCredentialIsRecognisableAndDifferentEveryTime() {
        assertThat(NodeSecret.credential().text()).startsWith(NodeSecret.CREDENTIAL_PREFIX);
        assertThat(NodeSecret.credential().text())
                .isNotEqualTo(NodeSecret.credential().text());
    }

    @Test
    void theHashIsWhatTheColumnHoldsAndItIsDerivedFromTheText() {
        NodeSecret secret = NodeSecret.credential();

        assertThat(secret.hash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(secret.hash()).isEqualTo(NodeSecret.hashOf(secret.text()));
    }

    @Test
    void aMalformedCredentialIsRejectedWithoutADatabaseRoundTrip() {
        assertThat(NodeSecret.looksLikeCredential(NodeSecret.credential().text())).isTrue();
        assertThat(NodeSecret.looksLikeCredential(NodeSecret.bootstrapToken().text())).isFalse();
        assertThat(NodeSecret.looksLikeCredential("wsn_short")).isFalse();
        assertThat(NodeSecret.looksLikeCredential(null)).isFalse();
        assertThat(NodeSecret.looksLikeCredential("")).isFalse();
    }

    @Test
    void printingASecretDoesNotPrintIt() {
        NodeSecret secret = NodeSecret.credential();

        assertThat(secret.toString()).doesNotContain(secret.text());
    }
}
