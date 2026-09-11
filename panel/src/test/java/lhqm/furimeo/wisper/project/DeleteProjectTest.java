package lhqm.furimeo.wisper.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
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
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.BuildPreset;
import lhqm.furimeo.wisper.service.DeleteService;
import lhqm.furimeo.wisper.service.DesiredState;
import lhqm.furimeo.wisper.service.ListServicesInProject;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceSummary;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Deleting a project, which is the one write in this package that cannot be undone.
 *
 * <p>Two things are being defended. The confirmation is checked in the use-case rather
 * than in the browser, because a confirmation enforced only in the browser is not
 * enforced. And the services go one at a time through {@code DeleteService} rather than
 * through the {@code ON DELETE CASCADE} on {@code service.project_id}: the cascade would
 * drop the placements without telling the nodes holding the containers, so a deleted
 * project would keep serving traffic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeleteProjectTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID API = UUID.randomUUID();
    private static final UUID SITE = UUID.randomUUID();

    @Mock
    private ProjectRepository projects;

    @Mock
    private ListServicesInProject servicesInProject;

    @Mock
    private DeleteService deleteService;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private DeleteProject deleteProject;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership owner = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private final Membership developer =
            new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER);

    private final Project project =
            Project.opened(PROJECT, ORGANIZATION, "Acme", "acme", "The shop");

    @BeforeEach
    void theProjectExistsAndHoldsTwoServices() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(project));
        given(servicesInProject.all(PROJECT)).willReturn(List.of(
                summary(API, "api", ServiceKind.APP),
                summary(SITE, "site", ServiceKind.SITE)));
    }

    @Test
    void everyServiceIsTornDownBeforeTheProjectRowGoes() {
        deleteProject.delete(actor, owner, PROJECT, "acme");

        InOrder order = inOrder(deleteService, projects);
        order.verify(deleteService).delete(actor, owner, API);
        order.verify(deleteService).delete(actor, owner, SITE);
        order.verify(projects).delete(project);
    }

    @Test
    void bothKindsGoTheSameWay() {
        // A site has no container and an app does, but neither is allowed to be left to
        // the database cascade: the difference belongs to DeleteService, not here.
        deleteProject.delete(actor, owner, PROJECT, "acme");

        verify(deleteService).delete(actor, owner, API);
        verify(deleteService).delete(actor, owner, SITE);
    }

    @Test
    void anEmptyProjectIsDeletedWithoutFuss() {
        given(servicesInProject.all(PROJECT)).willReturn(List.of());

        deleteProject.delete(actor, owner, PROJECT, "acme");

        verifyNoInteractions(deleteService);
        verify(projects).delete(project);
        assertThat(recorded().detail()).contains("0 service(s)");
    }

    @Test
    void aConfirmationThatIsNotTheSlugStopsEverything() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> deleteProject.delete(actor, owner, PROJECT, "Acme Web Shop"))
                .matches(rejected -> "confirmation".equals(rejected.field()))
                .withMessageContaining("Type acme to confirm");

        verifyNoInteractions(deleteService);
        verify(projects, never()).delete(any());
    }

    @Test
    void anAbsentConfirmationIsRefusedRatherThanTreatedAsAgreement() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> deleteProject.delete(actor, owner, PROJECT, null));

        verify(projects, never()).delete(any());
    }

    @Test
    void theConfirmationToleratesCaseAndSurroundingSpaceAndNothingElse() {
        // Typed on a phone, where the keyboard capitalises the first letter and a
        // trailing space arrives with the autocomplete.
        deleteProject.delete(actor, owner, PROJECT, "  ACME ");

        verify(projects).delete(project);
    }

    @Test
    void aDeveloperMayBreakAServiceAndMayNotRemoveTheFolderHoldingSix() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> deleteProject.delete(actor, developer, PROJECT, "acme"));

        verify(projects, never()).findByIdAndOrganizationId(any(), any());
        verifyNoInteractions(deleteService);
        verify(projects, never()).delete(any());
    }

    @Test
    void anotherTenantsProjectIsNotFound() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> deleteProject.delete(actor, owner, PROJECT, "acme"));

        verifyNoInteractions(deleteService);
    }

    @Test
    void theTrailKeepsTheNameAndTheCount() {
        deleteProject.delete(actor, owner, PROJECT, "acme");

        AuditEntry entry = recorded();
        assertThat(entry.action()).isEqualTo("project.delete");
        assertThat(entry.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(entry.target().id()).isEqualTo(PROJECT);
        assertThat(entry.target().label()).isEqualTo("Acme");
        assertThat(entry.detail()).isEqualTo("Deleted with 2 service(s)");
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private static ServiceSummary summary(UUID id, String slug, ServiceKind kind) {
        return new ServiceSummary(id, PROJECT, slug, slug, kind, DesiredState.RUNNING, null,
                kind == ServiceKind.APP ? "nginx:1.27" : null,
                kind == ServiceKind.SITE ? BuildPreset.STATIC : null,
                null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
