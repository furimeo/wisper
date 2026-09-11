package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaAllowance;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;

/**
 * Claiming a hostname: who may, what the platform refuses, and what nobody may take twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddDomainTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final AuditActor actor = AuditActor.system("test");
    private final Membership developer =
            new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER);
    private final Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

    @Mock
    private ServiceRepository services;

    @Mock
    private DomainRepository domains;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private JobQueue jobs;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private AddDomain addDomain;

    @BeforeEach
    void anUnclaimedNameOnALiveService() {
        Service service = DomainFixture.service(SERVICE, PROJECT, "shop");
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(service));
        given(domains.existsByHostname(anyString())).willReturn(false);
        given(domains.findPrimaryOf(SERVICE)).willReturn(Optional.empty());
        given(domains.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void theFirstHostnameOnAServiceBecomesItsPrimary() {
        Domain added = addDomain.add(actor, developer, SERVICE, "Shop.Example.COM",
                DomainTlsMode.ON_DEMAND, null, true, null);

        assertThat(added.hostname()).isEqualTo("shop.example.com");
        assertThat(added.kind()).isEqualTo(DomainKind.PRIMARY);
        assertThat(added.verificationState()).isEqualTo(DomainVerification.PENDING);
        assertThat(added.verificationToken()).isNotBlank();
    }

    @Test
    void everyLaterHostnameIsAnAlias() {
        given(domains.findPrimaryOf(SERVICE)).willReturn(Optional.of(
                DomainFixture.stored(UUID.randomUUID(), SERVICE, "shop.example.com",
                        DomainKind.PRIMARY)));

        Domain added = addDomain.add(actor, developer, SERVICE, "www.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null);

        assertThat(added.kind()).isEqualTo(DomainKind.ALIAS);
    }

    @Test
    void aHostnameSomebodyAlreadyHoldsIsRefused() {
        // The single most important rule in V18: two tenants holding one name means the
        // node's route table answers for whichever it loaded last.
        given(domains.existsByHostname("shop.example.com")).willReturn(true);

        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(HostnameTaken.class)
                .hasMessageContaining("shop.example.com")
                .hasMessageContaining("already in use");

        verify(domains, never()).save(any());
        verifyNoInteractions(specs);
    }

    @Test
    void theRefusalDoesNotSayWhoHoldsTheName() {
        // An error that distinguishes "yours" from "somebody else's" is a way to enumerate
        // which hostnames this platform serves.
        given(domains.existsByHostname("shop.example.com")).willReturn(true);

        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .hasMessageNotContainingAny(ORGANIZATION.toString(), SERVICE.toString(), "shop's");
    }

    @Test
    void aRaceLostAtTheUniqueIndexReadsTheSameWay() {
        // Two requests can both pass existsByHostname. The loser must get the sentence, not
        // a constraint violation.
        willThrow(new DuplicateKeyException("domain_hostname_key")).given(domains).save(any());

        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(HostnameTaken.class);
    }

    @Test
    void aWildcardIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "*.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("Wildcard");

        verify(domains, never()).save(any());
        verify(quotas, never()).require(any(), any(), anyLong());
        verifyNoInteractions(jobs, specs);
    }

    @Test
    void aViewerMayNotClaimAHostname() {
        assertThatThrownBy(() -> addDomain.add(actor, viewer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(PermissionDenied.class);

        verify(domains, never()).save(any());
    }

    @Test
    void anArchivedServiceHasNothingForAHostnameToReach() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(
                Optional.of(DomainFixture.archivedService(SERVICE, PROJECT, "shop")));

        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("archived");
    }

    @Test
    void theQuotaIsChargedBeforeTheRowExists() {
        willThrow(new QuotaExceeded(ORGANIZATION, QuotaResource.DOMAIN, 1,
                new QuotaAllowance(QuotaResource.DOMAIN, 2, 2,
                        QuotaAllowance.QuotaSource.PLAN)))
                .given(quotas).require(ORGANIZATION, QuotaResource.DOMAIN, 1);

        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null))
                .isInstanceOf(QuotaExceeded.class);

        verify(domains, never()).save(any());
    }

    @Test
    void aNewHostnameIsQueuedForVerificationAndPushedToTheNode() {
        Domain added = addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, null);

        verify(jobs).enqueueIfAbsent(eq(DomainVerificationTasks.VERIFY),
                eq(added.id().toString()), eq(new DomainCheckJob(added.id())), any());
        verify(specs).forService(eq(SERVICE), anyString());
    }

    @Test
    void theAuditEntryNamesTheHostnameAndNotTheToken() {
        addDomain.add(actor, developer, SERVICE, "shop.example.com", DomainTlsMode.ON_DEMAND,
                null, true, null);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("domain.add");
        assertThat(entry.getValue().target().label()).isEqualTo("shop.example.com");
        assertThat(entry.getValue().detail()).doesNotContain("tok-");
    }

    @Test
    void aPortOutsideTheLegalRangeIsNamedAsTheFieldThatIsWrong() {
        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, 70_000, true, null))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("65535");
    }

    @Test
    void aHostnameCannotRedirectToItself() {
        assertThatThrownBy(() -> addDomain.add(actor, developer, SERVICE, "shop.example.com",
                DomainTlsMode.ON_DEMAND, null, true, "SHOP.example.com."))
                .isInstanceOf(RequestRejected.class)
                .hasMessageContaining("redirect to itself");
    }

    @Test
    void aRedirectTargetIsStoredInTheSameCanonicalForm() {
        Domain added = addDomain.add(actor, developer, SERVICE, "www.example.com",
                DomainTlsMode.ON_DEMAND, null, true, "Shop.Example.COM.");

        assertThat(added.redirectToHostname()).isEqualTo("shop.example.com");
    }
}
