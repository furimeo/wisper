package lhqm.furimeo.wisper.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
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
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.service.ArchiveProjectServices;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Putting a project away and taking it out again.
 *
 * <p>The two properties worth pinning are that archiving <em>stops</em> the work without
 * deleting anything, and that restoring is not symmetrical: the services come back
 * available but stay stopped, so nobody pays for a dozen containers because they clicked
 * "restore" to read a description.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveProjectTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    @Mock
    private ProjectRepository projects;

    @Mock
    private ArchiveProjectServices projectServices;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private ArchiveProject archiveProject;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER);

    private final Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

    private final Project live =
            Project.opened(PROJECT, ORGANIZATION, "Acme", "acme", "The shop");

    @BeforeEach
    void savesEcho() {
        given(projects.save(any())).willAnswer(call -> call.getArgument(0));
        given(projectServices.archiveAll(eq(PROJECT), anyString())).willReturn(3);
        given(projectServices.restoreAll(eq(PROJECT), anyString())).willReturn(3);
    }

    @Test
    void archivingStampsTheProjectAndStopsEverythingInIt() {
        givenStored(live);

        Project archived = archiveProject.archive(actor, developer, PROJECT);

        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.archivedAt()).isNotNull();
        verify(projectServices).archiveAll(PROJECT, "project acme archived");
    }

    @Test
    void archivingDeletesNothing() {
        givenStored(live);

        archiveProject.archive(actor, developer, PROJECT);

        // The row, its services, their volumes and the customer's bytes all stay. The
        // only write is the flag.
        verify(projects, never()).delete(any());
        verify(projects, never()).deleteById(any());
        assertThat(recorded().detail()).contains("stopped and kept");
    }

    @Test
    void archivingATwiceArchivedProjectChangesNothing() {
        Project already = live.archived(Instant.parse("2026-01-02T03:04:05Z"));
        givenStored(already);

        Project answer = archiveProject.archive(actor, developer, PROJECT);

        assertThat(answer.archivedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        verify(projects, never()).save(any());
        verifyNothingWasStoppedOrStarted();
        verifyNoInteractions(audit);
    }

    @Test
    void restoringClearsTheStampAndLeavesTheServicesStopped() {
        givenStored(live.archived(Instant.now()));

        Project restored = archiveProject.restore(actor, developer, PROJECT);

        assertThat(restored.isArchived()).isFalse();
        verify(projectServices).restoreAll(PROJECT, "project acme restored");
        verify(projectServices, never()).archiveAll(any(), anyString());
        assertThat(recorded().detail()).contains("still stopped");
    }

    @Test
    void restoringALiveProjectChangesNothing() {
        givenStored(live);

        Project answer = archiveProject.restore(actor, developer, PROJECT);

        assertThat(answer.isArchived()).isFalse();
        verify(projects, never()).save(any());
        verifyNothingWasStoppedOrStarted();
        verifyNoInteractions(audit);
    }

    @Test
    void bothDirectionsAreOneAuditActionWithADetailSayingWhichWay() {
        // The vocabulary in panel-ports.md §2.4 is a fixed list that is filtered on, and
        // one action covering both directions of one flag reads better in a trail than a
        // second verb meaning "the first one, undone".
        givenStored(live);
        archiveProject.archive(actor, developer, PROJECT);
        assertThat(recorded().action()).isEqualTo("project.archive");
        assertThat(recorded().detail()).startsWith("Archived");
    }

    @Test
    void aViewerIsRefusedInBothDirections() {
        givenStored(live);

        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> archiveProject.archive(actor, viewer, PROJECT));
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> archiveProject.restore(actor, viewer, PROJECT));

        verifyNothingWasStoppedOrStarted();
        verify(projects, never()).save(any());
    }

    @Test
    void anotherTenantsProjectIsNotFound() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> archiveProject.archive(actor, developer, PROJECT));
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> archiveProject.restore(actor, developer, PROJECT));
    }

    private void verifyNothingWasStoppedOrStarted() {
        verify(projectServices, never()).archiveAll(any(), anyString());
        verify(projectServices, never()).restoreAll(any(), anyString());
    }

    private void givenStored(Project project) {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(project));
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
