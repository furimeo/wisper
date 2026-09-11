package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The challenge record: what the page tells the customer to publish, and what the lookup
 * accepts back.
 *
 * <p>Both strings come from this class, and the reason they have to is the failure mode if
 * they did not: a customer who does exactly what the screen says and is told it did not
 * work, with nothing wrong at either end.
 */
class VerificationTokenTest {

    private static final String TOKEN = "abcDEF123_-xyz";

    @Test
    void theRecordNameSitsUnderAnUnderscoreLabelSoItCannotCollideWithAHost() {
        assertThat(VerificationToken.recordName("app.example.com"))
                .isEqualTo("_wisper-challenge.app.example.com");
    }

    @Test
    void theFallbackNameIsOneLevelUp() {
        // Some DNS interfaces refuse to create a record under a name that has none.
        assertThat(VerificationToken.parentRecordName("app.example.com"))
                .isEqualTo("_wisper-challenge.example.com");
    }

    @Test
    void theValueNamesWisperSoOtherVerificationRecordsAreIgnored() {
        assertThat(VerificationToken.recordValue(TOKEN))
                .isEqualTo("wisper-domain-verification=" + TOKEN);
    }

    @Test
    void theRecordIsFoundAmongEverybodyElsesVerificationRecords() {
        List<String> zone = List.of(
                "v=spf1 -all",
                "google-site-verification=somethingelse",
                VerificationToken.recordValue(TOKEN));

        assertThat(VerificationToken.isPresentIn(zone, TOKEN)).isTrue();
    }

    @Test
    void whitespaceAroundTheValueDoesNotBreakIt() {
        assertThat(VerificationToken.isPresentIn(
                List.of("  " + VerificationToken.recordValue(TOKEN) + " "), TOKEN)).isTrue();
    }

    @Test
    void thePrefixIsMatchedWithoutRegardToCaseBecauseDnsPanelsRecaseThings() {
        assertThat(VerificationToken.isPresentIn(
                List.of("WISPER-DOMAIN-VERIFICATION=" + TOKEN), TOKEN)).isTrue();
    }

    @Test
    void theTokenItselfIsCaseSensitiveBecauseItIsBaseSixtyFour() {
        assertThat(VerificationToken.isPresentIn(
                List.of(VerificationToken.recordValue(TOKEN.toLowerCase(java.util.Locale.ROOT))),
                TOKEN)).isFalse();
    }

    @Test
    void somebodyElsesTokenProvesNothing() {
        assertThat(VerificationToken.isPresentIn(
                List.of(VerificationToken.recordValue("a-different-token")), TOKEN)).isFalse();
    }

    @Test
    void anEmptyZoneAndAMissingTokenAreBothJustFalse() {
        assertThat(VerificationToken.isPresentIn(List.of(), TOKEN)).isFalse();
        assertThat(VerificationToken.isPresentIn(List.of("anything"), "")).isFalse();
        assertThat(VerificationToken.isPresentIn(null, TOKEN)).isFalse();
    }

    @Test
    void everyIssuedTokenIsUnguessableAndFitsOneRecord() {
        String first = VerificationToken.issue();
        String second = VerificationToken.issue();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSize(32).matches("[A-Za-z0-9_-]+");
        // A TXT character-string is capped at 255 bytes; the whole value has to fit in one.
        assertThat(VerificationToken.recordValue(first).length()).isLessThan(255);
    }
}
