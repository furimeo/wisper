package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Setting a secret, and the round trip that has to survive it.
 *
 * <p>Three properties are worth a test each and are the reason {@code secret} is its own
 * table rather than a flag on {@code env_var}:
 *
 * <ol>
 * <li>what reaches the column is an envelope, never the value the customer typed;</li>
 * <li>what comes back out of the cipher is byte-for-byte what went in, trailing newline
 *     and all - a key that is trimmed on the way through is a key that fails to
 *     authenticate somewhere with no stack trace;</li>
 * <li>nothing on the way back to a screen carries the value at all.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SetSecretTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAA
            -----END OPENSSH PRIVATE KEY-----
            """;

    @Mock
    private ServiceRepository services;

    @Mock
    private SecretRepository secrets;

    @Mock
    private EnvVarRepository envVars;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    private final InMemoryCipher cipher = new InMemoryCipher();

    private SetSecret setSecret;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.DEVELOPER);

    private final Membership viewer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.VIEWER);

    @BeforeEach
    void anAppWithNothingSetOnItYet() {
        setSecret = new SetSecret(services, secrets, envVars, cipher, specs, audit);
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(app()));
        given(secrets.findByServiceIdAndName(any(), any())).willReturn(Optional.empty());
        given(envVars.existsByServiceIdAndName(any(), any())).willReturn(false);
        given(secrets.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void whatIsStoredIsAnEnvelopeAndDecryptsBackToExactlyWhatWasTyped() {
        setSecret.set(actor, developer, SERVICE, "DEPLOY_KEY", PRIVATE_KEY, false);

        Secret saved = savedSecret();
        assertThat(saved.value()).doesNotContain("BEGIN OPENSSH");
        assertThat(SecretEnvelope.looksLikeEnvelope(saved.value())).isTrue();
        assertThat(cipher.decrypt(saved.value())).isEqualTo(PRIVATE_KEY);
    }

    @Test
    void aTrailingNewlineSurvivesTheRoundTripBecauseItIsPartOfTheKey() {
        setSecret.set(actor, developer, SERVICE, "PEM", "value\n", false);

        assertThat(cipher.decrypt(savedSecret().value())).isEqualTo("value\n");
    }

    @Test
    void theSamePlaintextTwiceProducesTwoDifferentEnvelopes() {
        setSecret.set(actor, developer, SERVICE, "ONE", "same", false);
        Secret first = savedSecret();
        setSecret.set(actor, developer, SERVICE, "TWO", "same", false);

        ArgumentCaptor<Secret> captor = ArgumentCaptor.forClass(Secret.class);
        verify(secrets, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).value())
                .isNotEqualTo(captor.getAllValues().get(1).value());
        assertThat(cipher.decrypt(first.value())).isEqualTo("same");
    }

    @Test
    void whatComesBackToTheCallerCarriesNoValueAtAll() {
        SecretView view = setSecret.set(actor, developer, SERVICE, "TOKEN", "hunter2", false);

        assertThat(view.name()).isEqualTo("TOKEN");
        assertThat(SecretView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("value");
    }

    @Test
    void settingAnExistingSecretRotatesItRatherThanFailing() {
        Secret existing = Secret.of(UUID.randomUUID(), SERVICE, "TOKEN", cipher.encrypt("old"),
                false);
        given(secrets.findByServiceIdAndName(SERVICE, "TOKEN")).willReturn(Optional.of(existing));

        setSecret.set(actor, developer, SERVICE, "TOKEN", "new", false);

        Secret saved = savedSecret();
        assertThat(saved.id()).isEqualTo(existing.id());
        assertThat(saved.lastRotatedAt()).isNotNull();
        assertThat(cipher.decrypt(saved.value())).isEqualTo("new");
    }

    @Test
    void theAuditDetailNamesTheSecretAndNeverQuotesIt() {
        setSecret.set(actor, developer, SERVICE, "TOKEN", "hunter2", false);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("secret.set");
        assertThat(entry.getValue().detail()).contains("TOKEN").doesNotContain("hunter2");
    }

    @Test
    void theNodeIsToldSoTheSpecStopsBeingStale() {
        setSecret.set(actor, developer, SERVICE, "TOKEN", "hunter2", false);

        verify(specs).forService(eq(SERVICE), anyString());
    }

    @Test
    void aNameAPlainVariableAlreadyHoldsIsRefusedBecauseBothLandInOneEnvironment() {
        given(envVars.existsByServiceIdAndName(SERVICE, "DATABASE_URL")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "DATABASE_URL", "x",
                        false))
                .matches(rejected -> "name".equals(rejected.field()));
        verify(secrets, never()).save(any());
    }

    @Test
    void aReservedNameIsRefusedInEitherCase() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "WISPER_HOME", "x",
                        false));
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "wisper_home", "x",
                        false));
    }

    @Test
    void aMalformedNameIsRefusedAgainstTheSameShapeTheDatabaseEnforces() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "database-url", "x",
                        false))
                .withMessageContaining("DATABASE_URL");
    }

    @Test
    void anEmptyValueIsRefusedBecauseItIsNotASecret() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "TOKEN", "", false))
                .matches(rejected -> "value".equals(rejected.field()));
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "TOKEN", null, false));
    }

    @Test
    void aRuntimeSecretOnAStaticSiteIsRefusedBecauseNothingWouldEverReadIt() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(site()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> setSecret.set(actor, developer, SERVICE, "TOKEN", "x", false))
                .matches(rejected -> "buildTime".equals(rejected.field()));

        // The same secret marked build-time is exactly what a static site does want.
        assertThat(setSecret.set(actor, developer, SERVICE, "TOKEN", "x", true).buildTime())
                .isTrue();
    }

    @Test
    void aViewerIsRefusedBeforeAnythingIsEncrypted() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> setSecret.set(actor, viewer, SERVICE, "TOKEN", "x", false));
        assertThat(cipher.encryptions()).isZero();
        verify(secrets, never()).save(any());
    }

    private Secret savedSecret() {
        ArgumentCaptor<Secret> captor = ArgumentCaptor.forClass(Secret.class);
        verify(secrets, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static Service app() {
        return ServiceFixture.app(SERVICE, UUID.randomUUID());
    }

    private static Service site() {
        return ServiceFixture.site(SERVICE, UUID.randomUUID());
    }
}
