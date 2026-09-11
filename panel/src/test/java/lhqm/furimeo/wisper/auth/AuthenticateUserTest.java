package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditOutcome;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * The sign-in throttle, which is the whole reason this use-case is not transactional.
 *
 * <p>Every failing path writes the counter and then throws. Under {@code @Transactional}
 * the throw would roll the write back, so the lock would never arrive however many wrong
 * passwords were tried - and the test that proves it is the sequence below, not a reading
 * of the code.
 */
class AuthenticateUserTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    /** Three, so the sequence under test is short; production defaults to five. */
    private static final int MAX_ATTEMPTS = 3;

    private static final AuthSettings SETTINGS = new AuthSettings(
            MAX_ATTEMPTS, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60),
            1, 10, "wisper", Duration.ofDays(30), "admin@wisper.local");

    /** Cost 4: the lowest BCrypt allows. This tests the throttle, not the hash. */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final AuthenticateUser authenticateUser =
            new AuthenticateUser(accounts, passwordEncoder, SETTINGS, auditTrail);

    private final AtomicReference<Account> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        stored.set(Account.create(EMAIL, "Someone", passwordEncoder.encode(PASSWORD),
                PlatformRole.CUSTOMER, Instant.now()));
        when(accounts.findByEmail(EMAIL))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
    }

    @Test
    @DisplayName("the right password signs in and clears whatever the counter held")
    void acceptsTheRightPassword() {
        stored.set(stored.get().withFailedSignIn(2));

        SignedInAccount signedIn = authenticateUser.run(EMAIL, PASSWORD, "203.0.113.7");

        assertThat(signedIn.email()).isEqualTo(EMAIL);
        assertThat(signedIn.role()).isEqualTo(PlatformRole.CUSTOMER);
        assertThat(stored.get().failedLoginCount()).isZero();
        assertThat(stored.get().lockedUntil()).isNull();
        assertThat(stored.get().lastLoginAt()).isNotNull();
        assertThat(stored.get().lastLoginAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("an address is matched however it was capitalised or padded")
    void normalisesTheAddress() {
        assertThat(authenticateUser.run("  SomeOne@Example.COM ", PASSWORD, null).email())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("each wrong password counts, and the count survives the exception")
    void countsFailures() {
        assertThatThrownBy(() -> authenticateUser.run(EMAIL, "wrong", null))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(stored.get().failedLoginCount()).isEqualTo(1);
        assertThat(stored.get().lockedUntil()).isNull();

        assertThatThrownBy(() -> authenticateUser.run(EMAIL, "wrong again", null))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(stored.get().failedLoginCount()).isEqualTo(2);
        assertThat(stored.get().lockedUntil()).isNull();
    }

    @Test
    @DisplayName("the configured number of failures locks the account")
    void locksAfterRepeatedFailures() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> authenticateUser.run(EMAIL, "wrong", null))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(stored.get().lockedUntil()).isAfter(Instant.now());
        // Zeroed on purpose: a fresh budget after the lock expires, so an attacker who
        // has given up is not still holding the owner out one guess at a time.
        assertThat(stored.get().failedLoginCount()).isZero();
    }

    @Test
    @DisplayName("while it is locked, even the right password is refused")
    void refusesTheRightPasswordWhileLocked() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> authenticateUser.run(EMAIL, "wrong", null))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThatThrownBy(() -> authenticateUser.run(EMAIL, PASSWORD, null))
                .isInstanceOf(LockedException.class);
        assertThat(stored.get().lastLoginAt()).isNull();
    }

    @Test
    @DisplayName("once the lock expires the right password works again")
    void lockLiftsWhenItExpires() {
        stored.set(stored.get().lockedUntil(Instant.now().minusSeconds(1)));

        assertThat(authenticateUser.run(EMAIL, PASSWORD, null).email()).isEqualTo(EMAIL);
        assertThat(stored.get().lockedUntil()).isNull();
    }

    @Test
    @DisplayName("a locked account is refused before the password is even compared")
    void doesNotCheckThePasswordWhileLocked() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthenticateUser guarded =
                new AuthenticateUser(accounts, encoder, SETTINGS, auditTrail);
        stored.set(stored.get().lockedUntil(Instant.now().plusSeconds(600)));

        assertThatThrownBy(() -> guarded.run(EMAIL, PASSWORD, null))
                .isInstanceOf(LockedException.class);
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("a suspended account cannot sign in, and is told apart from a locked one")
    void refusesSuspendedAccounts() {
        stored.set(stored.get().withStatus(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> authenticateUser.run(EMAIL, PASSWORD, null))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    @DisplayName("an unknown address answers the same as a wrong password, and writes nothing")
    void refusesUnknownAddresses() {
        when(accounts.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticateUser.run("nobody@example.com", PASSWORD, null))
                .isInstanceOf(BadCredentialsException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("an unknown address still costs a password comparison, so timing says nothing")
    void spendsTheSameTimeOnAnUnknownAddress() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthenticateUser timed = new AuthenticateUser(accounts, encoder, SETTINGS, auditTrail);
        when(accounts.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> timed.run("nobody@example.com", PASSWORD, null))
                .isInstanceOf(BadCredentialsException.class);
        // Against a fixed hash of a value nobody knows: without it, "no such account"
        // returns in microseconds and the form becomes an account-enumeration oracle.
        verify(encoder).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("every refusal is in the audit trail, and so is the sign-in that worked")
    void recordsBothOutcomes() {
        assertThatThrownBy(() -> authenticateUser.run(EMAIL, "wrong", null))
                .isInstanceOf(BadCredentialsException.class);
        authenticateUser.run(EMAIL, PASSWORD, null);

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail, atLeastOnce()).record(entries.capture());

        List<AuditEntry> recorded = entries.getAllValues();
        assertThat(recorded).extracting(AuditEntry::action).containsOnly("account.sign_in");
        assertThat(recorded).extracting(AuditEntry::outcome)
                .containsExactly(AuditOutcome.DENIED, AuditOutcome.SUCCEEDED);
    }
}
