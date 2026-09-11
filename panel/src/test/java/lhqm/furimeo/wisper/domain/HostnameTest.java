package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The canonical form, and the four shapes that never become one.
 *
 * <p>Every case here exists because it would otherwise reach {@code domain_hostname_key} as
 * a second spelling of a name somebody already holds, or reach ACME as a name no authority
 * will ever issue for.
 */
class HostnameTest {

    /** {@code domain_hostname_shape} in V18, so a passing string is one the database takes. */
    private static final String DATABASE_SHAPE = "^(\\*\\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?$";

    @Test
    void caseAndTheTrailingDotAreNotPartOfTheName() {
        // Two spellings of one hostname reaching the table as two rows is two customers
        // owning one name, which is the whole thing global uniqueness is for.
        assertThat(Hostname.require("  App.Example.COM.  ")).isEqualTo("app.example.com");
    }

    @Test
    void aUnicodeHostnameIsStoredAsItsAsciiForm() {
        // Caddy matches SNI byte for byte, and SNI carries the A-label.
        assertThat(Hostname.require("münchen.example.org")).isEqualTo("xn--mnchen-3ya.example.org");
    }

    @Test
    void aWildcardIsRefusedAndTheMessageOffersTheAlternative() {
        assertThatThrownBy(() -> Hostname.require("*.example.com"))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("Wildcard")
                .hasMessageContaining("DNS challenge")
                .hasMessageContaining("app.example.com");
    }

    @Test
    void aWildcardIsRefusedBeforeAnythingElseIsEvenChecked() {
        // Not "that has an invalid character": the customer asked for a feature that does
        // not exist, and telling them their typing is wrong sends them round a loop.
        assertThatThrownBy(() -> Hostname.require("*.MÜNCHEN.example.  "))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("Wildcard");
    }

    @Test
    void theRefusalIsAttachedToTheHostnameInput() {
        assertThatThrownBy(() -> Hostname.require("*.example.com"))
                .asInstanceOf(InstanceOfAssertFactories.type(RequestRejected.class))
                .extracting(RequestRejected::field)
                .isEqualTo("hostname");
    }

    @Test
    void anAddressIsNotAName() {
        assertThatThrownBy(() -> Hostname.require("203.0.113.4"))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("IP address");
        assertThatThrownBy(() -> Hostname.require("2001:db8::1"))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("IP address");
    }

    @Test
    void aBareLabelHasNoDomainToIssueAgainst() {
        assertThatThrownBy(() -> Hostname.require("intranet-box"))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("no domain part");
    }

    @Test
    void aNameNoAuthorityIssuesForIsRefusedRatherThanLeftToFailForever() {
        for (String unissuable : new String[] {"box.local", "app.internal", "site.test",
                "thing.example", "printer.lan"}) {
            assertThatThrownBy(() -> Hostname.require(unissuable))
                    .describedAs(unissuable)
                    .isInstanceOf(RequestRejected.class)
                    .hasMessageContaining("certificate authority");
        }
    }

    @Test
    void aLabelLongerThanDnsAllowsIsRefused() {
        String tooLong = "a".repeat(64) + ".example.com";
        assertThatThrownBy(() -> Hostname.require(tooLong))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("63");
    }

    @Test
    void aLabelMayNotStartOrEndWithAHyphen() {
        assertThatThrownBy(() -> Hostname.require("-app.example.com"))
                .isInstanceOf(RequestRejected.class);
        assertThatThrownBy(() -> Hostname.require("app-.example.com"))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("hyphen");
    }

    @Test
    void anEmptyHostnameAsksForOne() {
        assertThatThrownBy(() -> Hostname.require("   "))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("app.example.com");
    }

    @Test
    void everyAcceptedHostnameSatisfiesTheDatabaseCheck() {
        for (String accepted : new String[] {"example.com", "app.example.com",
                "a-b.c-d.example.co.uk", "münchen.example.org", "EXAMPLE.COM."}) {
            assertThat(Hostname.require(accepted))
                    .describedAs(accepted)
                    .matches(DATABASE_SHAPE)
                    .isEqualTo(Hostname.require(accepted).toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void theParentIsTheNameWithoutItsFirstLabel() {
        assertThat(Hostname.parent("app.example.com")).isEqualTo("example.com");
        assertThat(Hostname.parent("example.com")).isEqualTo("com");
    }

    @Test
    void aWildcardIsRecognisedByItsPrefixAndNothingElse() {
        assertThat(Hostname.isWildcard("*.example.com")).isTrue();
        assertThat(Hostname.isWildcard("star.example.com")).isFalse();
        assertThat(Hostname.isWildcard(null)).isFalse();
    }
}
