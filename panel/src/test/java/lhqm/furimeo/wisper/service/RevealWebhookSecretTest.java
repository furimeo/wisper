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
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Reading back the secret a git provider signs its deliveries with.
 *
 * <p>The reason this use-case exists is covered by the first test: without it the
 * customer holds one half of an HMAC and can never learn the other, so deploy-on-push is
 * unusable no matter how correct the verification code is.
 *
 * <p>The rest pin the two things that make handing out a stored secret defensible - only
 * an administrator, and never without a record.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevealWebhookSecretTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private AuditTrail audit;

    private final InMemoryCipher cipher = new InMemoryCipher();

    private final AuditActor actor = AuditActor.system("test");

    private final Membership owner = new Membership(ORGANIZATION, UUID.randomUUID(),
            MemberRole.OWNER);
    private final Membership developer = new Membership(ORGANIZATION, UUID.randomUUID(),
            MemberRole.DEVELOPER);

    private RevealWebhookSecret revealWebhookSecret() {
        return new RevealWebhookSecret(services, cipher, audit);
    }

    @Test
    void theSecretComesBackAsTheProviderWillNeedToTypeIt() {
        String stored = cipher.encrypt("s3cret-value");
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.app(SERVICE, PROJECT).rotatedTo(stored)));

        assertThat(revealWebhookSecret().of(actor, owner, SERVICE)).isEqualTo("s3cret-value");
    }

    @Test
    void aDeveloperIsRefusedBeforeAnythingIsRead() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> revealWebhookSecret().of(actor, developer, SERVICE));

        verify(services, never()).findOwnedBy(any(), any());
        verify(audit, never()).record(any());
    }

    @Test
    void aServiceInAnotherOrganizationIsSimplyNotThere() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> revealWebhookSecret().of(actor, owner, SERVICE));
    }

    @Test
    void everyRevealLeavesARecord() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.app(SERVICE, PROJECT)
                        .rotatedTo(cipher.encrypt("s3cret-value"))));

        revealWebhookSecret().of(actor, owner, SERVICE);

        verify(audit).record(any(AuditEntry.class));
    }
}
