package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Organization;
import lhqm.furimeo.wisper.org.OrganizationRepository;
import lhqm.furimeo.wisper.org.QuotaAllowance;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;

/**
 * Creating a customer database: the name it ends up with, the two refusals that must happen
 * before anything is written, and the fact that the password never reaches the row in the
 * clear.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProvisionDatabaseTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ENGINE = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    @Mock
    private ManagedDatabaseRepository databases;

    @Mock
    private ChooseDatabaseEngine chooseEngine;

    @Mock
    private ProjectRepository projects;

    @Mock
    private OrganizationRepository organizations;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private SecretCipher cipher;

    @Mock
    private JobQueue jobs;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    private final DatabaseSettings settings = new DatabaseSettings(
            "postgres:17-alpine", "17", "mysql:8.4", "8.4",
            DataSize.ofGigabytes(1), DataSize.ofGigabytes(100), 15432, 15999,
            Duration.ofMinutes(5), Duration.ofSeconds(45));

    private final AuditActor actor = AuditActor.system("test");
    private final Membership membership = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private ProvisionDatabase provisionDatabase;

    @BeforeEach
    void aLiveProjectOnASharedEngine() {
        provisionDatabase = new ProvisionDatabase(databases, chooseEngine, projects, organizations,
                quotas, cipher, jobs, specs, settings, audit);

        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(Project.opened(PROJECT, ORGANIZATION, "Acme", "acme",
                        "")));
        given(organizations.findById(ORGANIZATION))
                .willReturn(Optional.of(Organization.opened(ORGANIZATION, "Acme Ltd", "acme",
                        PLAN)));
        given(chooseEngine.forNewDatabase(any(), eq(ORGANIZATION), any(), eq(false)))
                .willReturn(sharedEngine());
        given(databases.existsOnEngineNamed(any(), anyString())).willReturn(false);
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));
        given(cipher.encrypt(anyString())).willAnswer(call -> "v1.nonce." + call.getArgument(0));
    }

    @Test
    void theStoredNameCarriesTheTenantAndTheLoginIsDerivedFromIt() {
        ManagedDatabase created = provisionDatabase.create(actor, membership, PROJECT,
                EngineKind.POSTGRES, "app", 0, false);

        assertThat(created.name()).isEqualTo("acme_app");
        assertThat(created.dbUsername()).startsWith("acme_app_")
                .matches(DatabaseIdentifier.USERNAME_PATTERN);
        assertThat(created.state()).isEqualTo(ManagedDatabaseState.PENDING);
        assertThat(created.quotaBytes()).isEqualTo(DataSize.ofGigabytes(1).toBytes());
    }

    @Test
    void aSecondDatabaseWithTheSameNameOnOneEngineIsRefusedBeforeAnythingIsWritten() {
        given(databases.existsOnEngineNamed(ENGINE, "acme_app")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", 0, false))
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("name"));

        verify(databases, never()).save(any());
        verify(jobs, never()).enqueue(any(), anyString(), any());
        verify(specs, never()).toNode(any(), anyString());
    }

    @Test
    void theCollisionIsCheckedBeforeTheQuotaSoTheMessageNamesTheRealProblem() {
        // Both are true: the customer is at their limit and has already used the name.
        // Being told about the limit would send them to the billing page for a problem a
        // different word in the box would have solved.
        given(databases.existsOnEngineNamed(ENGINE, "acme_app")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", 0, false));

        verify(quotas, never()).require(any(), any(), anyLong());
    }

    @Test
    void anOrganizationAtItsDatabaseLimitGetsNothingWritten() {
        willThrow(new QuotaExceeded(ORGANIZATION, QuotaResource.MANAGED_DATABASE, 1,
                new QuotaAllowance(QuotaResource.MANAGED_DATABASE, 2, 2,
                        QuotaAllowance.QuotaSource.PLAN)))
                .given(quotas).require(ORGANIZATION, QuotaResource.MANAGED_DATABASE, 1);

        assertThatExceptionOfType(QuotaExceeded.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", 0, false));

        verify(databases, never()).save(any());
        verify(jobs, never()).enqueue(any(), anyString(), any());
    }

    @Test
    void thePasswordIsEncryptedBeforeItIsStoredAndTheRowNeverHoldsThePlaintext() {
        ManagedDatabase created = provisionDatabase.create(actor, membership, PROJECT,
                EngineKind.POSTGRES, "app", 0, false);

        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(plaintext.capture());
        assertThat(DatabasePassword.isSafe(plaintext.getValue())).isTrue();
        assertThat(created.dbPassword())
                .isEqualTo("v1.nonce." + plaintext.getValue())
                .doesNotContain("\n");
        assertThat(created.dbPassword()).isNotEqualTo(plaintext.getValue());
    }

    @Test
    void theGrantReachesTheNodesSpecAndTheJobQueueInTheSameTransaction() {
        ManagedDatabase created = provisionDatabase.create(actor, membership, PROJECT,
                EngineKind.POSTGRES, "app", 0, false);

        verify(specs).toNode(eq(NODE), anyString());
        verify(jobs).enqueue(eq(DatabaseTasks.PROVISION), eq(created.id().toString()),
                eq(new ProvisionJob(created.id())));
        verify(audit).record(any());
    }

    @Test
    void aQuotaLargerThanOneDatabaseMayHaveIsRefusedRatherThanQuietlyClamped() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", DataSize.ofGigabytes(500).toBytes(), false))
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("quotaBytes"));
    }

    @Test
    void aQuotaSmallerThanAnEmptyDatabaseIsRefused() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", 1024, false));
    }

    @Test
    void anArchivedProjectTakesNoNewDatabases() {
        given(projects.findByIdAndOrganizationId(PROJECT, ORGANIZATION))
                .willReturn(Optional.of(Project.opened(PROJECT, ORGANIZATION, "Acme", "acme", "")
                        .archived(Instant.parse("2026-01-01T00:00:00Z"))));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "app", 0, false));

        verify(databases, never()).save(any());
    }

    @Test
    void aNameWithNothingUsableInItIsRefusedAtTheField() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> provisionDatabase.create(actor, membership, PROJECT,
                        EngineKind.POSTGRES, "!!!", 0, false))
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("name"));
    }

    @Test
    void aViewerCannotCreateOne() {
        Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

        assertThatExceptionOfType(lhqm.furimeo.wisper.org.PermissionDenied.class)
                .isThrownBy(() -> provisionDatabase.create(actor, viewer, PROJECT,
                        EngineKind.POSTGRES, "app", 0, false));

        verify(databases, never()).save(any());
    }

    private static DatabaseEngine sharedEngine() {
        return DatabaseEngine.shared(ENGINE, NODE, EngineKind.POSTGRES, "17",
                "postgres:17-alpine", 5432, "v1.nonce.admin");
    }
}
