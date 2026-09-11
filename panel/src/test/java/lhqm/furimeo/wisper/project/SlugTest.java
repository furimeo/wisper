package lhqm.furimeo.wisper.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The address rule four tables share.
 *
 * <p>{@code project.slug}, {@code service.slug}, {@code volume.name} and
 * {@code cron_task.name} each have a CHECK constraint of this shape, and this class is
 * the only thing standing between a customer's typing and a constraint violation. The
 * first two tests pin the patterns to the SQL literally, because a slug that Java accepts
 * and PostgreSQL rejects arrives as a 500 on a form submission.
 */
class SlugTest {

    /** Copied character for character from V10 and V14. */
    private static final String SQL_SLUG_SHAPE = "^[a-z0-9][a-z0-9-]{1,62}$";

    /** Copied character for character from V22 and V23. */
    private static final String SQL_NAME_SHAPE = "^[a-z0-9][a-z0-9-]{0,62}$";

    @Test
    void thePatternIsTheOneTheProjectAndServiceTablesEnforce() {
        assertThat(Slug.PATTERN).isEqualTo(SQL_SLUG_SHAPE);
    }

    @Test
    void theShortPatternIsTheOneTheVolumeAndCronTaskTablesEnforce() {
        assertThat(Slug.SHORT_PATTERN).isEqualTo(SQL_NAME_SHAPE);
    }

    @Test
    void aSingleCharacterIsANameButNotAnAddress() {
        assertThat(Slug.isValid("w")).isFalse();
        assertThat(Slug.isShortValid("w")).isTrue();
    }

    @Test
    void whatItProducesIsAlwaysWhatTheConstraintAccepts() {
        String[] awkward = {
                "Acme Web Shop",
                "  leading and trailing  ",
                "dots.and_underscores",
                "many!!!separators???here",
                "ÜBER Café",                       // non-ASCII is dropped, not guessed at
                "42",
                "a".repeat(200),
                "a".repeat(62) + " b",             // the pair that used to straddle 63
                "a".repeat(63) + " b",
                "x".repeat(30) + "   " + "y".repeat(40),
        };
        for (String raw : awkward) {
            String slug = Slug.of(null, raw);
            assertThat(slug)
                    .as("normalising %s", raw)
                    .hasSizeLessThanOrEqualTo(Slug.MAX_LENGTH)
                    .matches(candidate -> candidate.isEmpty() || Slug.isValid(candidate));
        }
    }

    @Test
    void aRunOfAnythingElseBecomesOneDash() {
        assertThat(Slug.normalise("many!!!separators???here")).isEqualTo("many-separators-here");
        assertThat(Slug.normalise("dots.and_underscores")).isEqualTo("dots-and-underscores");
    }

    @Test
    void thereIsNeverALeadingOrTrailingDash() {
        assertThat(Slug.normalise("  !acme!  ")).isEqualTo("acme");
        assertThat(Slug.normalise("---")).isEmpty();
        assertThat(Slug.normalise("-a-")).isEqualTo("a");
    }

    @Test
    void nonAsciiIsDroppedRatherThanTransliterated() {
        // "oe" or "o" is a language guess, and guessing wrong in a URL is worse than
        // asking the customer to type an address.
        assertThat(Slug.normalise("Über Café")).isEqualTo("ber-caf");
    }

    @Test
    void aTypedAddressWinsOverTheNameAndAnEmptyOneFallsBack() {
        assertThat(Slug.of("chosen", "Some Name")).isEqualTo("chosen");
        assertThat(Slug.of("  ", "Some Name")).isEqualTo("some-name");
        assertThat(Slug.of(null, "Some Name")).isEqualTo("some-name");
        assertThat(Slug.of("!!!", "Some Name")).isEqualTo("some-name");
    }

    @Test
    void aTypedAddressIsNormalisedRatherThanTakenLiterally() {
        assertThat(Slug.of("My Project!", "ignored")).isEqualTo("my-project");
    }

    @Test
    void nothingUsableProducesAnEmptyStringRatherThanAnInventedAddress() {
        assertThat(Slug.of("!", "?")).isEmpty();
        assertThat(Slug.isValid("")).isFalse();
        assertThat(Slug.isValid(null)).isFalse();
        assertThat(Slug.isShortValid(null)).isFalse();
    }

    @Test
    void aLongNameIsShortenedRatherThanRefused() {
        // 62 characters, a space, then one more: the dash and the character after it are
        // budgeted together, so the answer fits instead of coming out one over.
        String slug = Slug.of(null, "a".repeat(62) + " b");

        assertThat(slug).hasSize(62).isEqualTo("a".repeat(62));
        assertThat(Slug.isValid(slug)).isTrue();
    }

    @Test
    void exactlyTheMaximumIsKept() {
        String slug = Slug.of(null, "a".repeat(Slug.MAX_LENGTH));

        assertThat(slug).hasSize(Slug.MAX_LENGTH);
        assertThat(Slug.isValid(slug)).isTrue();
    }

    @Test
    void aNameIsTrimmedAndItsInternalWhitespaceCollapsed() {
        assertThat(Slug.tidyName("  Acme   Web \n Shop ")).isEqualTo("Acme Web Shop");
        assertThat(Slug.tidyName("   ")).isEmpty();
        assertThat(Slug.tidyName(null)).isEmpty();
    }

    @Test
    void comparingWhatWasTypedWithWhatIsStoredIgnoresCaseAndSurroundingSpace() {
        assertThat(Slug.lower("  Acme-Web  ")).isEqualTo("acme-web");
        assertThat(Slug.lower(null)).isEmpty();
    }

    @Test
    void theRuleSentenceNamesTheMinimumItIsAbout() {
        assertThat(Slug.rule(2)).contains("2 to 63");
        assertThat(Slug.rule(1)).contains("1 to 63");
    }

    @Test
    void theShortPatternAcceptsEverythingTheLongOneDoes() {
        Pattern longShape = Pattern.compile(Slug.PATTERN);
        Pattern shortShape = Pattern.compile(Slug.SHORT_PATTERN);
        for (String candidate : new String[] {"ab", "a1", "a-b", "0", "z".repeat(63)}) {
            if (longShape.matcher(candidate).matches()) {
                assertThat(shortShape.matcher(candidate).matches())
                        .as("%s is a slug, so it must also be a name", candidate)
                        .isTrue();
            }
        }
    }
}
