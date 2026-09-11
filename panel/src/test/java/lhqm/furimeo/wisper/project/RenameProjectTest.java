package lhqm.furimeo.wisper.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import lhqm.furimeo.wisper.audit.AuditOutcome;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/** Renaming a project, and the address that deliberately does not follow the name. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RenameProjectTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    @Mock
    private ProjectRepository projects;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private RenameProject renameProject;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership owner = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private final Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

    private final Project existing =
            Project.opened(PROJECT, ORGANIZATION, "Acme Web Shop", "acme-web-shop", "The shop");

    @BeforeEach
    void theProjectExistsAndSavesEcho() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(existing));
        given(projects.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void theAddressDoesNotFollowTheName() {
        // A bookmark, a link in a ticket and a colleague's tab all keep working. This is
        // the whole reason there is no route that changes a slug.
        Project renamed = renameProject.rename(actor, owner, PROJECT, "Northwind", "Now this");

        assertThat(renamed.name()).isEqualTo("Northwind");
        assertThat(renamed.slug()).isEqualTo("acme-web-shop");
        assertThat(renamed.id()).isEqualTo(PROJECT);
        assertThat(renamed.organizationId()).isEqualTo(ORGANIZATION);
    }

    @Test
    void theNameIsTidiedTheSameWayCreationTidiesIt() {
        Project renamed = renameProject.rename(actor, owner, PROJECT, "  North   wind ", null);

        assertThat(renamed.name()).isEqualTo("North wind");
        assertThat(renamed.description()).isEmpty();
    }

    @Test
    void theDescriptionIsTrimmedAndAnEmptyOneClearsIt() {
        assertThat(renameProject.rename(actor, owner, PROJECT, "Acme", "  a shop  ")
                .description()).isEqualTo("a shop");
        assertThat(renameProject.rename(actor, owner, PROJECT, "Acme", "  ").description())
                .isEmpty();
    }

    @Test
    void aBlankNameIsRefusedBeforeTheProjectIsEvenLoaded() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> renameProject.rename(actor, owner, PROJECT, "   ", "x"))
                .matches(rejected -> "name".equals(rejected.field()));

        verify(projects, never()).save(any());
        verify(projects, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void aViewerIsRefused() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> renameProject.rename(actor, viewer, PROJECT, "Acme", ""));

        verify(projects, never()).save(any());
    }

    @Test
    void anotherTenantsProjectIsNotFoundRatherThanForbidden() {
        // Answering 403 here would tell an attacker which ids exist (panel-http.md).
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> renameProject.rename(actor, owner, PROJECT, "Acme", ""));

        verify(projects, never()).save(any());
    }

    @Test
    void theTrailSaysWhatTheOldNameWas() {
        renameProject.rename(actor, owner, PROJECT, "Northwind", "");

        AuditEntry entry = recorded();
        assertThat(entry.action()).isEqualTo("project.update");
        assertThat(entry.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(entry.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(entry.target().id()).isEqualTo(PROJECT);
        assertThat(entry.detail()).isEqualTo("Renamed from Acme Web Shop");
    }

    @Test
    void aDescriptionOnlyEditIsNotReportedAsARename() {
        renameProject.rename(actor, owner, PROJECT, "Acme Web Shop", "a different sentence");

        assertThat(recorded().detail()).isEqualTo("Description changed");
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
