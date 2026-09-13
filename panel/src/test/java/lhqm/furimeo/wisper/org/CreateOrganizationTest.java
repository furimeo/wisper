package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

/**
 * Opening a tenant, and the two things that must be true the instant it exists: it is on
 * a plan, and somebody owns it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateOrganizationTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();

    @Mock
    private OrganizationRepository organizations;

    @Mock
    private MemberRepository members;

    @Mock
    private PlanRepository plans;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private CreateOrganization createOrganization;

    private final AuditActor actor = AuditActor.system("test");

    @BeforeEach
    void aDefaultPlanExistsAndTheSlugIsFree() {
        given(plans.findDefault()).willReturn(Optional.of(plan(PLAN_ID, "free", false, null)));
        given(organizations.existsBySlug(any())).willReturn(false);
        given(organizations.save(any())).willAnswer(call -> call.getArgument(0));
        given(members.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void theCreatorBecomesAnAcceptedOwnerInTheSameTransaction() {
        Organization created = createOrganization.create(actor, OWNER, "Acme Ltd", "acme", null);

        assertThat(created.slug()).isEqualTo("acme");
        assertThat(created.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(created.planId()).isEqualTo(PLAN_ID);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().role()).isEqualTo(MemberRole.OWNER);
        assertThat(saved.getValue().accountId()).isEqualTo(OWNER);
        assertThat(saved.getValue().isAccepted()).isTrue();
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().organizationId()).isNull();
        assertThat(entry.getValue().target().id()).isEqualTo(created.id());
    }

    @Test
    void theSlugIsLowerCasedAndTrimmedBeforeItIsChecked() {
        Organization created = createOrganization.create(actor, OWNER, " Acme ", "  ACME  ", null);

        assertThat(created.slug()).isEqualTo("acme");
        assertThat(created.name()).isEqualTo("Acme");
    }

    @Test
    void aMalformedSlugIsRefusedAgainstTheSameShapeTheDatabaseEnforces() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createOrganization.create(actor, OWNER, "Acme", "-nope", null))
                .matches(rejected -> "slug".equals(rejected.field()));
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createOrganization.create(actor, OWNER, "Acme", "a", null));
        verify(organizations, never()).save(any());
    }

    @Test
    void aTakenSlugIsRefusedBeforeTheInsert() {
        given(organizations.existsBySlug("acme")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createOrganization.create(actor, OWNER, "Acme", "acme", null))
                .withMessageContaining("already taken");
        verify(organizations, never()).save(any());
    }

    @Test
    void withNoDefaultPlanNothingIsCreatedAndTheMessageSaysWhy() {
        given(plans.findDefault()).willReturn(Optional.empty());

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createOrganization.create(actor, OWNER, "Acme", "acme", null))
                .withMessageContaining("No default plan");
        verify(organizations, never()).save(any());
        verify(members, never()).save(any());
    }

    @Test
    void anArchivedPlanIsRefusedRatherThanQuietlySwappedForTheDefault() {
        UUID archivedId = UUID.randomUUID();
        given(plans.findById(archivedId))
                .willReturn(Optional.of(plan(archivedId, "legacy", false, Instant.now())));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() ->
                        createOrganization.create(actor, OWNER, "Acme", "acme", archivedId))
                .withMessageContaining("no longer available");
        verify(organizations, never()).save(any());
    }

    @Test
    void aChosenPlanIsUsedInsteadOfTheDefault() {
        UUID chosen = UUID.randomUUID();
        given(plans.findById(chosen)).willReturn(Optional.of(plan(chosen, "pro", false, null)));

        Organization created = createOrganization.create(actor, OWNER, "Acme", "acme", chosen);

        assertThat(created.planId()).isEqualTo(chosen);
    }

    private static Plan plan(UUID id, String code, boolean isDefault, Instant archivedAt) {
        return new Plan(id, code, code, "", isDefault, archivedAt, Instant.now(), Instant.now(),
                1L);
    }
}
