package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
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

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.web.NotFoundException;

/** Releasing a hostname, and leaving the service with an address afterwards. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemoveDomainTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PRIMARY = UUID.randomUUID();
    private static final UUID ALIAS = UUID.randomUUID();

    private final AuditActor actor = AuditActor.system("test");
    private final Membership developer =
            new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER);

    private final Domain primary =
            DomainFixture.stored(PRIMARY, SERVICE, "shop.example.com", DomainKind.PRIMARY);
    private final Domain alias =
            DomainFixture.stored(ALIAS, SERVICE, "www.example.com", DomainKind.ALIAS);

    @Mock
    private DomainRepository domains;

    @Mock
    private JobQueue jobs;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private RemoveDomain removeDomain;

    @BeforeEach
    void aServiceWithTwoHostnames() {
        given(domains.findOwnedBy(PRIMARY, SERVICE, ORGANIZATION))
                .willReturn(Optional.of(primary));
        given(domains.findOwnedBy(ALIAS, SERVICE, ORGANIZATION)).willReturn(Optional.of(alias));
        given(domains.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void removingThePrimaryPromotesWhatIsLeft() {
        // A service with hostnames and no primary shows the customer no address at all.
        given(domains.findByServiceId(SERVICE)).willReturn(List.of(alias));

        removeDomain.remove(actor, developer, SERVICE, PRIMARY);

        verify(domains).delete(primary);
        ArgumentCaptor<Domain> promoted = ArgumentCaptor.forClass(Domain.class);
        verify(domains).save(promoted.capture());
        assertThat(promoted.getValue().id()).isEqualTo(ALIAS);
        assertThat(promoted.getValue().kind()).isEqualTo(DomainKind.PRIMARY);
    }

    @Test
    void removingTheLastHostnamePromotesNothing() {
        given(domains.findByServiceId(SERVICE)).willReturn(List.of());

        removeDomain.remove(actor, developer, SERVICE, PRIMARY);

        verify(domains).delete(primary);
        verify(domains, never()).save(any());
    }

    @Test
    void removingAnAliasLeavesThePrimaryAlone() {
        removeDomain.remove(actor, developer, SERVICE, ALIAS);

        verify(domains).delete(alias);
        verify(domains, never()).save(any());
        verify(domains, never()).findByServiceId(any());
    }

    @Test
    void thePendingVerificationIsCancelledAndTheNodeIsTold() {
        removeDomain.remove(actor, developer, SERVICE, ALIAS);

        verify(jobs).cancel(DomainVerificationTasks.VERIFY, ALIAS.toString());
        verify(specs).forService(eq(SERVICE), anyString());
    }

    @Test
    void theAuditEntryKeepsTheHostnameTheRowNoLongerHas() {
        // The row is gone; the trail is where a dispute about who held the name is settled.
        removeDomain.remove(actor, developer, SERVICE, ALIAS);

        verify(audit).record(argThat(entry ->
                "domain.remove".equals(entry.action())
                        && "www.example.com".equals(entry.target().label())));
    }

    @Test
    void aViewerMayNotReleaseAHostname() {
        Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

        assertThatThrownBy(() -> removeDomain.remove(actor, viewer, SERVICE, ALIAS))
                .isInstanceOf(PermissionDenied.class);

        verify(domains, never()).delete(any());
        verifyNoInteractions(jobs, specs);
    }

    @Test
    void anotherOrganizationsHostnameDoesNotExist() {
        given(domains.findOwnedBy(ALIAS, SERVICE, ORGANIZATION)).willReturn(Optional.empty());

        assertThatThrownBy(() -> removeDomain.remove(actor, developer, SERVICE, ALIAS))
                .isInstanceOf(NotFoundException.class);

        verify(domains, never()).delete(any());
    }
}
