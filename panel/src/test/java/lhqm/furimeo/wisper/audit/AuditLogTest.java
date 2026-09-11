package lhqm.furimeo.wisper.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The row, and the four CHECK constraints it has to satisfy before it reaches PostgreSQL.
 *
 * <p>The point of validating in Java is that the failure names the mistake. A constraint
 * violation from {@code audit_log_actor_consistent} arriving three frames away from the
 * call that caused it is how the predecessor's audit trail ended up with entries nobody
 * could explain.
 */
class AuditLogTest {

    private static final Instant AT = Instant.parse("2026-02-11T09:15:00Z");

    @Test
    void anAccountEntryCarriesTheAccountAndNeitherTheTokenNorTheNode() {
        UUID accountId = UUID.randomUUID();
        AuditActor actor = new AuditActor(AuditActorKind.ACCOUNT, accountId, null, null,
                "someone@example.com", "203.0.113.9", "Firefox", "req-1");

        AuditLog row = AuditLog.of(AuditEntry.succeeded(actor, "service.start",
                AuditTarget.of("service", UUID.randomUUID(), "api"), null, ""), AT);

        assertThat(row.actorKind()).isEqualTo(AuditActorKind.ACCOUNT);
        assertThat(row.actorAccountId()).isEqualTo(accountId);
        assertThat(row.apiTokenId()).isNull();
        assertThat(row.nodeId()).isNull();
        assertThat(row.occurredAt()).isEqualTo(AT);
        // A null version is what tells Spring Data JDBC this is an insert of a row whose
        // id the panel already chose (schema.md §1).
        assertThat(row.version()).isNull();
        assertThat(row.id()).isNotNull();
    }

    @Test
    void aNodeActorMayNotAlsoCarryAnAccount() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new AuditActor(AuditActorKind.NODE, UUID.randomUUID(), null, UUID.randomUUID(),
                        "node-1", "198.51.100.4", null, null));
    }

    @Test
    void anActionThatIsProseIsRefusedBeforeItReachesTheShapeConstraint() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                AuditEntry.succeeded(AuditActor.system("test"), "Started the service",
                        AuditTarget.of("service", UUID.randomUUID(), "api"), null, ""));
    }

    @Test
    void aVeryLongDetailIsClippedRatherThanStoredWhole() {
        String essay = "x".repeat(AuditLog.DETAIL_LIMIT * 2);

        AuditLog row = AuditLog.of(AuditEntry.failed(AuditActor.system("test"), "deployment.start",
                AuditTarget.of("deployment", UUID.randomUUID(), "#7"), null, essay), AT);

        assertThat(row.detail()).hasSize(AuditLog.DETAIL_LIMIT);
        assertThat(row.detail()).endsWith("…");
    }

    @Test
    void aTargetWithNoRowStillNamesWhatWasAttempted() {
        AuditLog row = AuditLog.of(AuditEntry.denied(AuditActor.system("test"), "account.sign_in",
                AuditTarget.unidentified("account", "nobody@example.com"), null,
                "no such address"), AT);

        assertThat(row.targetId()).isNull();
        assertThat(row.targetKind()).isEqualTo("account");
        assertThat(row.targetLabel()).isEqualTo("nobody@example.com");
    }
}
