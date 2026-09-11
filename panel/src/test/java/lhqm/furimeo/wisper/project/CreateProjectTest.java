package lhqm.furimeo.wisper.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaAllowance;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;

/** Opening a project: who may, how many, and what the address ends up being. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateProjectTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Mock
    private ProjectRepository projects;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private CreateProject createProject;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership owner = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private final Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

    @BeforeEach
    void theSlugIsFreeAndSavesEcho() {
        given(projects.existsByOrganizationIdAndSlug(any(), any())).willReturn(false);
        given(projects.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void anEmptySlugIsDerivedFromTheName() {
        Project created = createProject.create(actor, owner, "  Acme  Web   Shop ", "  ", "");

        assertThat(created.slug()).isEqualTo("acme-web-shop");
        assertThat(created.name()).isEqualTo("Acme Web Shop");
        assertThat(created.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(created.isArchived()).isFalse();
    }

    @Test
    void aTypedSlugIsNormalisedRatherThanTakenLiterally() {
        Project created = createProject.create(actor, owner, "Acme", "  My Project! ", "");

        assertThat(created.slug()).isEqualTo("my-project");
    }

    @Test
    void aNameThatNormalisesToNothingUsableIsRefusedAgainstTheDatabaseShape() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createProject.create(actor, owner, "!", null, ""))
                .matches(rejected -> "slug".equals(rejected.field()));
        verify(projects, never()).save(any());
    }

    @Test
    void aBlankNameIsRefusedBeforeAnythingElseHappens() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createProject.create(actor, owner, "   ", "acme", ""))
                .matches(rejected -> "name".equals(rejected.field()));
        verify(quotas, never()).require(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aTakenAddressIsRefusedBeforeTheInsert() {
        given(projects.existsByOrganizationIdAndSlug(ORGANIZATION, "acme")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createProject.create(actor, owner, "Acme", "acme", ""))
                .withMessageContaining("already uses that address");
        verify(projects, never()).save(any());
    }

    @Test
    void aViewerIsRefusedAndNoQuotaIsSpent() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> createProject.create(actor, viewer, "Acme", "acme", ""));
        verify(quotas, never()).require(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(projects, never()).save(any());
    }

    @Test
    void theQuotaIsCheckedInsideTheTransactionAndBeforeTheInsert() {
        willThrow(new QuotaExceeded(ORGANIZATION, QuotaResource.PROJECT, 1,
                new QuotaAllowance(QuotaResource.PROJECT, 3, 3,
                        QuotaAllowance.QuotaSource.PLAN)))
                .given(quotas).require(eq(ORGANIZATION), eq(QuotaResource.PROJECT), eq(1L));

        assertThatExceptionOfType(QuotaExceeded.class)
                .isThrownBy(() -> createProject.create(actor, owner, "Acme", "acme", ""));
        verify(projects, never()).save(any());
    }

    @Test
    void theTrailRecordsTheCreation() {
        createProject.create(actor, owner, "Acme", "acme", "The shop");

        verify(audit).record(any());
    }

    @Test
    void aDescriptionIsTrimmedAndNeverNull() {
        Project created = createProject.create(actor, owner, "Acme", "acme", "  a shop  ");

        assertThat(created.description()).isEqualTo("a shop");
        assertThat(createProject.create(actor, owner, "Acme", "acme", null).description())
                .isEmpty();
    }
}
