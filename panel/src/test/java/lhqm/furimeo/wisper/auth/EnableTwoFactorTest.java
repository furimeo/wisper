package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;

/**
 * The two-step enrolment handshake, which exists so that a mistyped secret cannot lock
 * anybody out.
 *
 * <p>The property under test is the gap between the two steps: after
 * {@link BeginTwoFactorEnrolment} the account holds a secret and is still <em>not</em>
 * protected by it, so no sign-in challenges for a code no phone can produce. Only
 * {@link EnableTwoFactor}, and only with a code that verifies, closes the gap - and it
 * hands over the recovery codes in the same breath, because a second factor with no way
 * past it is how an account becomes unrecoverable.
 *
 * <p>{@link DisableTwoFactor} is exercised here too rather than in a file of its own:
 * enabling and disabling are one state machine, and the assertion that matters about
 * disabling - that the recovery codes go with it - can only be written against a set that
 * enabling produced.
 */
class EnableTwoFactorTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "correct horse battery staple";
    private static final int RECOVERY_CODE_COUNT = 8;

    private static final AuthSettings SETTINGS = new AuthSettings(
            5, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60),
            1, RECOVERY_CODE_COUNT, "wisper", Duration.ofDays(30), "admin@wisper.local");

    private static final AuditActor ACTOR = new AuditActor(AuditActorKind.ACCOUNT,
            java.util.UUID.randomUUID(), null, null, EMAIL, "203.0.113.7", null, null);

    /** Cost 4: the lowest BCrypt allows. Nothing here is testing the hash. */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountRecoveryCodeRepository recoveryCodes =
            mock(AccountRecoveryCodeRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final ReversibleCipher cipher = new ReversibleCipher();

    private final BeginTwoFactorEnrolment begin =
            new BeginTwoFactorEnrolment(accounts, cipher, SETTINGS);
    private final VerifyTwoFactorCode verify =
            new VerifyTwoFactorCode(accounts, cipher, SETTINGS);
    private final RegenerateRecoveryCodes regenerate =
            new RegenerateRecoveryCodes(accounts, recoveryCodes, SETTINGS, auditTrail);
    private final EnableTwoFactor enable =
            new EnableTwoFactor(accounts, verify, regenerate, auditTrail);
    private final DisableTwoFactor disable =
            new DisableTwoFactor(accounts, recoveryCodes, passwordEncoder, auditTrail);

    private final AtomicReference<Account> stored = new AtomicReference<>();
    private final List<AccountRecoveryCode> issued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stored.set(Account.create(EMAIL, "Someone", passwordEncoder.encode(PASSWORD),
                PlatformRole.CUSTOMER, Instant.now()));

        when(accounts.findById(any())).thenAnswer(invocation -> Optional.of(stored.get()));
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
        when(recoveryCodes.saveAll(any())).thenAnswer(invocation -> {
            List<AccountRecoveryCode> rows = invocation.getArgument(0);
            issued.addAll(rows);
            return rows;
        });
        when(recoveryCodes.deleteAllFor(any())).thenAnswer(invocation -> {
            int removed = issued.size();
            issued.clear();
            return removed;
        });
    }

    @Test
    @DisplayName("beginning enrolment stores an envelope, not the secret in the clear")
    void storesAnEnvelope() {
        begin.run(accountId());

        assertThat(stored.get().totpSecret()).isNotNull();
        assertThat(SecretEnvelope.looksLikeEnvelope(stored.get().totpSecret())).isTrue();
        assertThat(stored.get().totpSecret()).doesNotContain(secretOf(stored.get()).base32());
    }

    @Test
    @DisplayName("an enrolment that was started and never confirmed challenges nobody")
    void unconfirmedEnrolmentIsNotASecondFactor() {
        begin.run(accountId());

        // The whole reason totp_confirmed_at is a separate column: someone who closes the
        // tab half way through must still be able to sign in with their password alone.
        assertThat(stored.get().totpConfirmedAt()).isNull();
        assertThat(stored.get().hasSecondFactor()).isFalse();
    }

    @Test
    @DisplayName("the provisioning URI carries the issuer and the address the app displays")
    void provisioningUriIdentifiesTheAccount() {
        BeginTwoFactorEnrolment.Enrolment enrolment = begin.run(accountId());

        assertThat(enrolment.provisioningUri())
                .startsWith("otpauth://totp/wisper:someone%40example.com")
                .contains("secret=" + secretOf(stored.get()).base32())
                .contains("digits=6")
                .contains("period=30");
        // Grouped in fours for the person who cannot make their camera focus.
        assertThat(enrolment.secret()).contains(" ");
    }

    @Test
    @DisplayName("a wrong code leaves the enrolment unconfirmed and issues no recovery codes")
    void refusesAWrongCode() {
        begin.run(accountId());

        assertThatThrownBy(() -> enable.run(accountId(), "000000", ACTOR))
                .isInstanceOf(CredentialRejected.class)
                .satisfies(thrown ->
                        assertThat(((CredentialRejected) thrown).field()).isEqualTo("code"));
        assertThat(stored.get().hasSecondFactor()).isFalse();
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("confirming with a working code turns it on and hands over the recovery codes")
    void confirmsAndIssuesRecoveryCodes() {
        begin.run(accountId());

        List<String> codes = enable.run(accountId(), currentCode(), ACTOR);

        assertThat(stored.get().hasSecondFactor()).isTrue();
        assertThat(stored.get().totpConfirmedAt()).isNotNull();
        assertThat(codes).hasSize(RECOVERY_CODE_COUNT).doesNotHaveDuplicates();
        assertThat(issued).hasSize(RECOVERY_CODE_COUNT);
        // Stored hashed, printed in the grouped form; the two must correspond.
        assertThat(issued).extracting(AccountRecoveryCode::codeHash)
                .containsExactlyInAnyOrderElementsOf(
                        codes.stream().map(code -> RecoveryCode.parse(code).orElseThrow().hash())
                                .toList());
    }

    @Test
    @DisplayName("a code from one step ago is still accepted, because clocks differ")
    void acceptsACodeFromWithinTheDriftWindow() {
        begin.run(accountId());
        TotpSecret secret = secretOf(stored.get());
        String previousStep = TimeBasedOneTimePassword.codeForStep(secret,
                TimeBasedOneTimePassword.stepAt(Instant.now()) - 1);

        assertThat(enable.run(accountId(), previousStep, ACTOR)).hasSize(RECOVERY_CODE_COUNT);
    }

    @Test
    @DisplayName("starting again before confirming replaces the secret, which is not an error")
    void restartingReplacesTheSecret() {
        begin.run(accountId());
        String firstSecret = secretOf(stored.get()).base32();

        begin.run(accountId());

        assertThat(secretOf(stored.get()).base32()).isNotEqualTo(firstSecret);
    }

    @Test
    @DisplayName("starting again once it is on is refused, so a working phone is never orphaned")
    void refusesToRestartWhenAlreadyOn() {
        begin.run(accountId());
        enable.run(accountId(), currentCode(), ACTOR);

        assertThatThrownBy(() -> begin.run(accountId()))
                .isInstanceOf(CredentialRejected.class);
    }

    @Test
    @DisplayName("confirming twice is refused rather than issuing a second batch")
    void refusesToConfirmTwice() {
        begin.run(accountId());
        enable.run(accountId(), currentCode(), ACTOR);
        issued.clear();

        assertThatThrownBy(() -> enable.run(accountId(), currentCode(), ACTOR))
                .isInstanceOf(CredentialRejected.class);
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("confirming a code before any enrolment was started says so")
    void refusesToConfirmWithoutAnEnrolment() {
        assertThatThrownBy(() -> enable.run(accountId(), "123456", ACTOR))
                .isInstanceOf(CredentialRejected.class);
    }

    @Test
    @DisplayName("turning it off needs the current password")
    void disablingRequiresThePassword() {
        begin.run(accountId());
        enable.run(accountId(), currentCode(), ACTOR);

        assertThatThrownBy(() -> disable.run(accountId(), "not the password", ACTOR))
                .isInstanceOf(CredentialRejected.class)
                .satisfies(thrown -> assertThat(((CredentialRejected) thrown).field())
                        .isEqualTo("currentPassword"));
        assertThat(stored.get().hasSecondFactor()).isTrue();
        assertThat(issued).hasSize(RECOVERY_CODE_COUNT);
    }

    @Test
    @DisplayName("turning it off clears the secret and discards the recovery codes with it")
    void disablingClearsEverything() {
        begin.run(accountId());
        enable.run(accountId(), currentCode(), ACTOR);

        disable.run(accountId(), PASSWORD, ACTOR);

        assertThat(stored.get().totpSecret()).isNull();
        assertThat(stored.get().totpConfirmedAt()).isNull();
        assertThat(stored.get().hasSecondFactor()).isFalse();
        // The batch enabling produced is gone: codes that get past a challenge which no
        // longer happens are a printout somebody keeps in a drawer for nothing.
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("recovery codes cannot be issued for an account with no second factor")
    void refusesRecoveryCodesWithoutASecondFactor() {
        assertThatThrownBy(() -> regenerate.run(accountId(), ACTOR))
                .isInstanceOf(CredentialRejected.class);
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("regenerating replaces the batch rather than topping it up")
    void regeneratingReplacesTheBatch() {
        begin.run(accountId());
        List<String> first = enable.run(accountId(), currentCode(), ACTOR);

        List<String> second = regenerate.run(accountId(), ACTOR);

        assertThat(second).hasSize(RECOVERY_CODE_COUNT).doesNotContainAnyElementsOf(first);
        // The old rows were deleted, not left alongside: a printout that was replaced must
        // stop working, or replacing it accomplished nothing.
        assertThat(issued).hasSize(RECOVERY_CODE_COUNT);
    }

    private java.util.UUID accountId() {
        return stored.get().id();
    }

    /** The code the phone would be showing right now. */
    private String currentCode() {
        return TimeBasedOneTimePassword.codeAt(secretOf(stored.get()), Instant.now());
    }

    private TotpSecret secretOf(Account account) {
        return TotpSecret.ofBase32(cipher.decrypt(account.totpSecret()));
    }
}
