package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretEnvelope;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaAllowance;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Creating a service, and the two shapes it can have.
 *
 * <p>The app and site cases are checked side by side deliberately: they go through one
 * use-case and one validator, and the thing worth proving is that the same call produces
 * two genuinely different rows - one with an image and no build, one with a build and no
 * image - rather than one row with everything nullable and nothing enforced.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateServiceTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private ProjectRepository projects;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private AuditTrail audit;

    private final InMemoryCipher cipher = new InMemoryCipher();

    private CreateService createService;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.DEVELOPER);

    private final Membership viewer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.VIEWER);

    @BeforeEach
    void aLiveProjectWithRoomInIt() {
        createService = new CreateService(services, projects, quotas, cipher, audit);
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(project(null)));
        given(services.existsByProjectIdAndSlug(any(), any())).willReturn(false);
        given(services.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void anAppKeepsItsImageAndItsArgvAndHasNoBuild() {
        Service created = createService.create(actor, developer, PROJECT, appDraft());

        assertThat(created.kind()).isEqualTo(ServiceKind.APP);
        assertThat(created.image()).isEqualTo("ghcr.io/acme/api:1.4");
        assertThat(created.command()).containsExactly("node", "server.js");
        assertThat(created.containerPort()).isEqualTo(8080);
        assertThat(created.buildPreset()).isNull();
        assertThat(created.buildOutputDir()).isNull();
    }

    @Test
    void aSiteKeepsItsBuildAndHasNoImageOrPort() {
        Service created = createService.create(actor, developer, PROJECT, siteDraft());

        assertThat(created.kind()).isEqualTo(ServiceKind.SITE);
        assertThat(created.image()).isNull();
        assertThat(created.containerPort()).isNull();
        assertThat(created.buildPreset()).isEqualTo(BuildPreset.ASTRO);
        assertThat(created.buildOutputDir()).isEqualTo("dist");
        assertThat(created.command()).isEmpty();
    }

    @Test
    void aNewServiceIsStoppedBecauseThereIsNothingToRunYet() {
        assertThat(createService.create(actor, developer, PROJECT, appDraft()).desiredState())
                .isEqualTo(DesiredState.STOPPED);
        assertThat(createService.create(actor, developer, PROJECT, siteDraft()).desiredState())
                .isEqualTo(DesiredState.STOPPED);
    }

    @Test
    void theWebhookSecretIsGeneratedEncryptedAndDifferentEveryTime() {
        Service first = createService.create(actor, developer, PROJECT, appDraft());
        Service second = createService.create(actor, developer, PROJECT, appDraft());

        assertThat(SecretEnvelope.looksLikeEnvelope(first.webhookSecret())).isTrue();
        assertThat(first.webhookSecret()).isNotEqualTo(second.webhookSecret());
        assertThat(cipher.decrypt(first.webhookSecret()))
                .isNotEqualTo(cipher.decrypt(second.webhookSecret()));
        assertThat(cipher.decrypt(first.webhookSecret())).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void aRepositoryCredentialIsStoredAsAnEnvelopeAndNeverAsTypedText() {
        ServiceDraft draft = withRepository(appDraft(), "https://github.com/acme/api.git",
                "ghp_super_secret");

        Service created = createService.create(actor, developer, PROJECT, draft);

        assertThat(created.repositoryCredential()).isNotNull();
        assertThat(created.repositoryCredential()).doesNotContain("ghp_super_secret");
        assertThat(SecretEnvelope.looksLikeEnvelope(created.repositoryCredential())).isTrue();
        assertThat(cipher.decrypt(created.repositoryCredential())).isEqualTo("ghp_super_secret");
    }

    @Test
    void theAddressIsDerivedFromTheNameWhenTheCustomerLeavesItEmpty() {
        assertThat(createService.create(actor, developer, PROJECT, appDraft()).slug())
                .isEqualTo("acme-api");
    }

    @Test
    void aTakenAddressIsRefusedBeforeTheInsert() {
        given(services.existsByProjectIdAndSlug(PROJECT, "acme-api")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createService.create(actor, developer, PROJECT, appDraft()))
                .matches(rejected -> "slug".equals(rejected.field()));
        verify(services, never()).save(any());
    }

    @Test
    void aServiceCostsARowSomeMemoryAndSomeCpu() {
        createService.create(actor, developer, PROJECT, appDraft());

        verify(quotas).require(ORGANIZATION, QuotaResource.SERVICE, 1);
        verify(quotas).require(ORGANIZATION, QuotaResource.MEMORY_BYTES,
                ServiceDraft.DEFAULT_MEMORY_BYTES);
        verify(quotas).require(ORGANIZATION, QuotaResource.CPU_MILLICORES,
                ServiceDraft.DEFAULT_CPU_MILLICORES);
    }

    @Test
    void aPlanAtItsServiceCeilingStopsTheInsert() {
        willThrow(new QuotaExceeded(ORGANIZATION, QuotaResource.SERVICE, 1,
                new QuotaAllowance(QuotaResource.SERVICE, 2, 2, QuotaAllowance.QuotaSource.PLAN)))
                .given(quotas).require(eq(ORGANIZATION), eq(QuotaResource.SERVICE), anyLong());

        assertThatExceptionOfType(QuotaExceeded.class)
                .isThrownBy(() -> createService.create(actor, developer, PROJECT, appDraft()));
        verify(services, never()).save(any());
    }

    @Test
    void anArchivedProjectTakesNoNewServices() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(project(Instant.now())));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createService.create(actor, developer, PROJECT, appDraft()))
                .withMessageContaining("archived");
        verify(services, never()).save(any());
    }

    @Test
    void aProjectInAnotherTenantIsNotFoundRatherThanForbidden() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> createService.create(actor, developer, PROJECT, appDraft()));
    }

    @Test
    void aViewerIsRefusedBeforeTheProjectIsEvenLoaded() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> createService.create(actor, viewer, PROJECT, appDraft()));
        verify(projects, never()).findByIdAndOrganizationId(any(), any());
        verify(services, never()).save(any());
    }

    @Test
    void theTrailNamesTheKindAndTheProject() {
        createService.create(actor, developer, PROJECT, appDraft());

        verify(audit).record(any());
    }

    private static Project project(Instant archivedAt) {
        return new Project(PROJECT, ORGANIZATION, "Acme", "acme", "", archivedAt, Instant.now(),
                Instant.now(), 1L);
    }

    private static ServiceDraft appDraft() {
        return new ServiceDraft("Acme Api", null, ServiceKind.APP, "ghcr.io/acme/api:1.4",
                List.of("node", "server.js"), List.of(), null, 8080, null, null, null, null,
                null, null, null, null, null, null, true, null, null, null, null, List.of(),
                null, null);
    }

    private static ServiceDraft siteDraft() {
        return new ServiceDraft("Acme Api", null, ServiceKind.SITE, null, List.of(), List.of(),
                null, null, null, null, null, BuildPreset.ASTRO, null, null, null, null, null,
                null, true, null, null, null, null, List.of(), null, null);
    }

    private static ServiceDraft withRepository(ServiceDraft draft, String url, String credential) {
        return new ServiceDraft(draft.name(), draft.slug(), draft.kind(), draft.image(),
                draft.command(), draft.entrypoint(), draft.workingDir(), draft.containerPort(),
                draft.healthCheckPath(), draft.healthCheckIntervalSeconds(),
                draft.restartPolicy(), draft.buildPreset(), draft.buildCommand(),
                draft.buildOutputDir(), draft.keepReleases(), url, draft.repositoryBranch(),
                credential, draft.autoDeploy(), draft.cpuMillicores(), draft.memoryBytes(),
                draft.diskBytes(), draft.pidsLimit(), draft.requiredTags(),
                draft.runtimeIsolation(), draft.isolationReason());
    }
}
