package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The names a customer's typing turns into, and the two shapes the database itself
 * enforces.
 */
class DatabaseIdentifierTest {

    private static final UUID ID = UUID.fromString("3f2a9c1b-4d5e-4f60-8a71-2b3c4d5e6f70");

    @Test
    void theTenantIsThePrefixSoTwoOrganizationsCanBothHaveOneCalledApp() {
        String mine = DatabaseIdentifier.nameFor("acme", "app");
        String theirs = DatabaseIdentifier.nameFor("globex", "app");

        assertThat(mine).isEqualTo("acme_app");
        assertThat(theirs).isEqualTo("globex_app");
        assertThat(mine).isNotEqualTo(theirs);
    }

    @Test
    void everyProducedNameMatchesTheCheckConstraint() {
        assertThat(DatabaseIdentifier.nameFor("acme", "Reports 2024"))
                .matches(DatabaseIdentifier.NAME_PATTERN)
                .isEqualTo("acme_reports_2024");
        assertThat(DatabaseIdentifier.nameFor("a-very-long-organization-slug-indeed",
                "an-equally-long-database-name-that-somebody-typed"))
                .matches(DatabaseIdentifier.NAME_PATTERN)
                .hasSizeLessThanOrEqualTo(63);
    }

    @Test
    void aSlugStartingWithADigitStillProducesANameStartingWithALetter() {
        // organization_slug_shape permits a leading digit; managed_database_name_shape
        // does not, and losing the digit would rename somebody's tenant.
        assertThat(DatabaseIdentifier.nameFor("2024tenant", "app"))
                .matches(DatabaseIdentifier.NAME_PATTERN)
                .isEqualTo("d2024tenant_app");
    }

    @Test
    void punctuationCollapsesInsteadOfLeavingRunsOfUnderscores() {
        assertThat(DatabaseIdentifier.nameFor("acme", "my...app!!"))
                .isEqualTo("acme_my_app");
    }

    @Test
    void aRequestWithNothingUsableInItBecomesADefaultRatherThanAnInvalidName() {
        assertThat(DatabaseIdentifier.nameFor("acme", "   "))
                .isEqualTo("acme_db")
                .matches(DatabaseIdentifier.NAME_PATTERN);
        assertThat(DatabaseIdentifier.isUsableRequest("!!!")).isFalse();
        assertThat(DatabaseIdentifier.isUsableRequest("app")).isTrue();
    }

    @Test
    void anOrganizationSlugThatSanitisesToNothingIsAFailureRatherThanANamelessDatabase() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> DatabaseIdentifier.nameFor("", "app"));
    }

    @Test
    void theLoginFitsThirtyOneCharactersEvenWhenTheNameDoesNot() {
        String name = DatabaseIdentifier.nameFor("a-very-long-organization-slug-indeed",
                "and-a-very-long-database-name-too");
        String username = DatabaseIdentifier.usernameFor(name, ID);

        assertThat(username)
                .matches(DatabaseIdentifier.USERNAME_PATTERN)
                .hasSizeLessThanOrEqualTo(31);
    }

    @Test
    void twoDatabasesWithTheSameNameStillGetDifferentLogins() {
        // The suffix comes from the row's own id, so a login cannot collide even when two
        // organizations have chosen names that truncate to the same stem.
        String first = DatabaseIdentifier.usernameFor("acme_app", ID);
        String second = DatabaseIdentifier.usernameFor("acme_app",
                UUID.fromString("99887766-5544-4332-a110-0011223344ff"));

        assertThat(first).startsWith("acme_app_").isNotEqualTo(second);
    }
}
