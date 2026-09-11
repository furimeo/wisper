package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The one shape in the panel that carries a decrypted credential, and the two forms of it
 * that must never be confused.
 */
class ConnectionStringTest {

    private static final UUID DATABASE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ENGINE = UUID.fromString("11112222-3333-4444-5555-666677778888");
    private static final UUID NODE = UUID.randomUUID();
    private static final String PASSWORD = "S3cretButUriSafe-value_here";

    @Test
    void aPostgresUriIsWhatAClientLibraryExpects() {
        ConnectionString details = details(EngineKind.POSTGRES);

        assertThat(details.uri())
                .isEqualTo("postgresql://acme_app_abcdef:" + PASSWORD
                        + "@wisper-postgres-111122223333:5432/acme_app");
    }

    @Test
    void aMySqlUriUsesItsOwnSchemeAndPort() {
        ConnectionString details = details(EngineKind.MYSQL);

        assertThat(details.uri()).startsWith("mysql://").endsWith(":3306/acme_app");
    }

    @Test
    void theRedactedFormIsTheOnlyOneSafeForALogLine() {
        ConnectionString details = details(EngineKind.POSTGRES);

        assertThat(details.redactedUri())
                .doesNotContain(PASSWORD)
                .contains("acme_app_abcdef:********@");
    }

    @Test
    void theJdbcUrlCarriesNoCredentialAtAll() {
        ConnectionString details = details(EngineKind.POSTGRES);

        assertThat(details.jdbcUrl())
                .startsWith("jdbc:postgresql://")
                .doesNotContain(PASSWORD)
                .doesNotContain("acme_app_abcdef");
    }

    @Test
    void aGeneratedPasswordNeverNeedsEscapingInTheUri() {
        // The alphabet in DatabasePassword excludes every character a URI treats
        // specially, which is why ConnectionString does no escaping. If that ever stops
        // being true, this is the test that says so.
        for (int attempt = 0; attempt < 200; attempt++) {
            String password = DatabasePassword.generate();
            assertThat(password).doesNotContain("@").doesNotContain(":").doesNotContain("/")
                    .doesNotContain("?").doesNotContain("#").doesNotContain("%")
                    .doesNotContain("\\").doesNotContain("'").doesNotContain("\"")
                    .doesNotContain(" ");
            assertThat(DatabasePassword.isSafe(password)).isTrue();
        }
    }

    @Test
    void theCharsetFallsBackToTheEnginesDefaultRatherThanBeingBlank() {
        ManagedDatabase database = ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, "acme_app",
                "acme_app_abcdef", "v1.nonce.x", null, null, 1024 * 1024);

        ConnectionString details = ConnectionString.of(database, engine(EngineKind.MYSQL),
                PASSWORD);

        assertThat(details.charset()).isEqualTo("utf8mb4");
    }

    private static ConnectionString details(EngineKind kind) {
        ManagedDatabase database = ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, "acme_app",
                "acme_app_abcdef", "v1.nonce.x", kind.defaultEncoding(), null, 1024 * 1024);
        return ConnectionString.of(database, engine(kind), PASSWORD);
    }

    private static DatabaseEngine engine(EngineKind kind) {
        return DatabaseEngine.shared(ENGINE, NODE, kind, "17", "image", kind.defaultPort(),
                "v1.nonce.admin");
    }
}
