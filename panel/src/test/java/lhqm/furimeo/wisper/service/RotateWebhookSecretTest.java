package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;

/**
 * Replacing the secret after a leak.
 *
 * <p>Two properties matter more than the new value itself. What lands in the column is an
 * envelope, because a plaintext secret in an encrypted column is a breach that passes
 * every other test. And nothing else on the row moves: rotation is done mid-incident, and
 * an operation that also restarted the customer's application is one nobody would dare
 * reach for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RotateWebhookSecretTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private AuditTrail audit;

    @Captor
    private ArgumentCaptor<Service> saved;

    private final InMemoryCipher cipher = new InMemoryCipher();

    private final AuditActor actor = AuditActor.system("test");

    private final Membership admin = new Membership(ORGANIZATION, UUID.randomUUID(),
            MemberRole.ADMIN);
    private final Membership developer = new Membership(ORGANIZATION, UUID.randomUUID(),
            MemberRole.DEVELOPER);

    private RotateWebhookSecret rotateWebhookSecret() {
        return new RotateWebhookSecret(services, cipher, audit);
    }

    private Service running() {
        return ServiceFixture.running(SERVICE, PROJECT, ServiceKind.APP);
    }

    @Test
    void whatIsStoredIsAnEnvelopeThatDecryptsToWhatTheCustomerWasShown() {
        Service before = running();
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(before));

        String shown = rotateWebhookSecret().of(actor, admin, SERVICE);

        verify(services).save(saved.capture());
        assertThat(saved.getValue().webhookSecret()).isNotEqualTo(shown);
        assertThat(cipher.decrypt(saved.getValue().webhookSecret())).isEqualTo(shown);
    }

    @Test
    void theOldSecretStopsBeingTheOne() {
        Service before = running();
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(before));

        rotateWebhookSecret().of(actor, admin, SERVICE);

        verify(services).save(saved.capture());
        assertThat(saved.getValue().webhookSecret()).isNotEqualTo(before.webhookSecret());
    }

    @Test
    void rotatingDuringAnIncidentDoesNotDisturbTheRunningService() {
        Service before = running();
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(before));

        rotateWebhookSecret().of(actor, admin, SERVICE);

        verify(services).save(saved.capture());
        Service after = saved.getValue();
        assertThat(after.desiredState()).isEqualTo(before.desiredState());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.slug()).isEqualTo(before.slug());
        assertThat(after.image()).isEqualTo(before.image());
        assertThat(after.archivedAt()).isEqualTo(before.archivedAt());
    }

    @Test
    void aDeveloperIsRefusedBeforeAnythingIsWritten() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> rotateWebhookSecret().of(actor, developer, SERVICE));

        verify(services, never()).save(any());
        verify(audit, never()).record(any());
    }

    @Test
    void theRotationIsRecorded() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(running()));

        rotateWebhookSecret().of(actor, admin, SERVICE);

        verify(audit).record(any(AuditEntry.class));
    }
}
