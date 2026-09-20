package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminSetPasswordTest {

    private static final String NEW_PASSWORD = "new-strong-password";

    @Mock
    private AccountRepository accounts;

    @Mock
    private SessionRepository sessions;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditTrail auditTrail;

    private AdminSetPassword adminSetPassword;
    private AuditActor actor;

    @BeforeEach
    void setUp() {
        adminSetPassword = new AdminSetPassword(accounts, sessions, passwordEncoder, auditTrail);
        actor = AuditActor.system("billing-dashboard");
    }

    private Account account() {
        return Account.create("alice@example.com", "Alice", "old-hash",
                PlatformRole.CUSTOMER, Instant.now());
    }

    @Test
    void run_setsHashRevokesSessionsAndAudits() {
        Account account = account();
        given(accounts.findById(account.id())).willReturn(Optional.of(account));
        given(passwordEncoder.encode(NEW_PASSWORD)).willReturn("encoded-hash");
        given(accounts.save(any(Account.class))).willAnswer(inv -> inv.getArgument(0));
        given(sessions.revokeAllFor(eq(account.id()), any(),
                eq(SessionRevocationReason.PASSWORD_CHANGED.name()))).willReturn(3);

        Account result = adminSetPassword.run(account.id(), NEW_PASSWORD, actor);

        assertThat(result.passwordHash()).isEqualTo("encoded-hash");
        verify(passwordEncoder).encode(NEW_PASSWORD);
        verify(sessions).revokeAllFor(eq(account.id()), any(),
                eq(SessionRevocationReason.PASSWORD_CHANGED.name()));
        verify(auditTrail).record(any(AuditEntry.class));
    }

    @Test
    void run_unknownAccountThrowsNotFound() {
        Account account = account();
        given(accounts.findById(account.id())).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminSetPassword.run(account.id(), NEW_PASSWORD, actor))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(passwordEncoder, sessions, auditTrail);
    }

    @Test
    void run_shortPasswordIsRejectedBeforeAnyStateChanges() {
        Account account = account();
        given(accounts.findById(account.id())).willReturn(Optional.of(account));

        assertThatThrownBy(() -> adminSetPassword.run(account.id(), "short", actor))
                .isInstanceOf(CredentialRejected.class)
                .hasFieldOrPropertyWithValue("field", "newPassword");
        verifyNoInteractions(passwordEncoder, sessions, auditTrail);
    }

    @Test
    void run_passwordEqualToEmailIsRejected() {
        Account account = account();
        given(accounts.findById(account.id())).willReturn(Optional.of(account));

        assertThatThrownBy(() -> adminSetPassword.run(account.id(), "alice@example.com", actor))
                .isInstanceOf(CredentialRejected.class)
                .hasFieldOrPropertyWithValue("field", "newPassword");
    }

    @Test
    void runByEmail_normalisesAddressAndDelegatesToRun() {
        Account account = account();
        given(accounts.findByEmail("alice@example.com")).willReturn(Optional.of(account));
        given(accounts.findById(account.id())).willReturn(Optional.of(account));
        given(passwordEncoder.encode(NEW_PASSWORD)).willReturn("encoded-hash");
        given(accounts.save(any(Account.class))).willAnswer(inv -> inv.getArgument(0));
        given(sessions.revokeAllFor(eq(account.id()), any(),
                eq(SessionRevocationReason.PASSWORD_CHANGED.name()))).willReturn(1);

        Account result = adminSetPassword.runByEmail("Alice@Example.COM", NEW_PASSWORD, actor);

        assertThat(result.passwordHash()).isEqualTo("encoded-hash");
        verify(accounts).findByEmail("alice@example.com");
    }

    @Test
    void runByEmail_unknownEmailThrowsNotFound() {
        given(accounts.findByEmail("nobody@example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminSetPassword.runByEmail("nobody@example.com", NEW_PASSWORD, actor))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(passwordEncoder, sessions, auditTrail);
    }
}
