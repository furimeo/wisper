package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditOutcome;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * The way past the TOTP challenge when the phone is gone, and the three ways it must not
 * work.
 *
 * <p>A recovery code is the one credential in this package that is checked <em>after</em>
 * a password has already been accepted, so the properties that matter are not about
 * guessing: they are that a code works exactly once, that it only works for the account
 * it was issued to even though the lookup is by hash alone, and that every outcome is in
 * the audit trail. Somebody using a recovery code has either lost their phone or is in
 * the middle of taking an account over, and the entry is what tells the two apart
 * afterwards.
 */
class ConsumeRecoveryCodeTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();
    private static final String EMAIL = "someone@example.com";

    private static final AuditActor ACTOR = new AuditActor(AuditActorKind.ACCOUNT,
            ACCOUNT, null, null, EMAIL, "203.0.113.7", null, null);

    private final AccountRecoveryCodeRepository recoveryCodes =
            mock(AccountRecoveryCodeRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final ConsumeRecoveryCode consume = new ConsumeRecoveryCode(recoveryCodes, auditTrail);

    /** The stored batch, keyed the way the unique index on {@code code_hash} keys it. */
    private final Map<String, AccountRecoveryCode> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(recoveryCodes.findByCodeHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(recoveryCodes.save(any(AccountRecoveryCode.class))).thenAnswer(invocation -> {
            AccountRecoveryCode saved = invocation.getArgument(0);
            rows.put(saved.codeHash(), saved);
            return saved;
        });
        when(recoveryCodes.countByAccountIdAndUsedAtIsNull(any())).thenAnswer(invocation ->
                rows.values().stream()
                        .filter(row -> row.accountId().equals(invocation.getArgument(0)))
                        .filter(row -> row.usedAt() == null)
                        .count());
    }

    @Test
    @DisplayName("a code that was issued to this account is accepted")
    void acceptsAnIssuedCode() {
        RecoveryCode code = issueTo(ACCOUNT);

        assertThat(consume.run(ACCOUNT, EMAIL, code.display(), ACTOR)).isTrue();
    }

    @Test
    @DisplayName("it works exactly once; the replay finds it spent rather than unknown")
    void refusesTheSecondUse() {
        RecoveryCode code = issueTo(ACCOUNT);

        assertThat(consume.run(ACCOUNT, EMAIL, code.display(), ACTOR)).isTrue();
        assertThat(consume.run(ACCOUNT, EMAIL, code.display(), ACTOR)).isFalse();
        // Marked, not deleted: "you have used three of your eight" has to stay answerable,
        // and a deleted row would make a replay look like a code that never existed.
        assertThat(rows.get(code.hash()).usedAt()).isNotNull();
    }

    @Test
    @DisplayName("a code belonging to somebody else does not satisfy this challenge")
    void refusesAnotherAccountsCode() {
        RecoveryCode theirs = issueTo(SOMEBODY_ELSE);

        assertThat(consume.run(ACCOUNT, EMAIL, theirs.display(), ACTOR)).isFalse();
        // And it is still usable by its actual owner: refusing it here must not spend it.
        assertThat(rows.get(theirs.hash()).usedAt()).isNull();
    }

    @Test
    @DisplayName("the printed form and the bare form are the same code")
    void acceptsEitherTranscription() {
        RecoveryCode code = issueTo(ACCOUNT);

        assertThat(consume.run(ACCOUNT, EMAIL, "  " + code.value().toLowerCase() + " ", ACTOR))
                .isTrue();
    }

    @Test
    @DisplayName("something that is not a code at all is refused without a lookup")
    void refusesGarbageWithoutQuerying() {
        assertThat(consume.run(ACCOUNT, EMAIL, "123456", ACTOR)).isFalse();
        assertThat(consume.run(ACCOUNT, EMAIL, "", ACTOR)).isFalse();
        assertThat(consume.run(ACCOUNT, EMAIL, null, ACTOR)).isFalse();

        verify(recoveryCodes, never()).findByCodeHash(anyString());
    }

    @Test
    @DisplayName("a well-formed code that was never issued is refused")
    void refusesAnUnknownCode() {
        assertThat(consume.run(ACCOUNT, EMAIL, RecoveryCode.generate().display(), ACTOR)).isFalse();
    }

    @Test
    @DisplayName("the count left goes down by one, which is what the security page shows")
    void spendingReducesWhatIsLeft() {
        issueTo(ACCOUNT);
        RecoveryCode second = issueTo(ACCOUNT);

        consume.run(ACCOUNT, EMAIL, second.display(), ACTOR);

        assertThat(recoveryCodes.countByAccountIdAndUsedAtIsNull(ACCOUNT)).isEqualTo(1);
    }

    @Test
    @DisplayName("both the use and the refusal are in the audit trail")
    void recordsBothOutcomes() {
        RecoveryCode code = issueTo(ACCOUNT);
        consume.run(ACCOUNT, EMAIL, code.display(), ACTOR);
        consume.run(ACCOUNT, EMAIL, code.display(), ACTOR);

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail, atLeastOnce()).record(entries.capture());

        assertThat(entries.getAllValues()).extracting(AuditEntry::outcome)
                .containsExactly(AuditOutcome.SUCCEEDED, AuditOutcome.DENIED);
    }

    private RecoveryCode issueTo(UUID accountId) {
        RecoveryCode code = RecoveryCode.generate();
        rows.put(code.hash(), AccountRecoveryCode.issued(accountId, code.hash()));
        return code;
    }
}
