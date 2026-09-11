package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Scheduling a command inside a container - which a static site does not have.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateScheduledTaskTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private CronTaskRepository tasks;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private CreateScheduledTask createTask;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.DEVELOPER);

    private final Membership viewer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.VIEWER);

    @BeforeEach
    void anAppWithNoScheduleYet() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.app(SERVICE, PROJECT)));
        given(tasks.existsByServiceIdAndName(any(), any())).willReturn(false);
        given(tasks.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void anAppGetsTheEntryWithItsArgvItsZoneAndAComputedDueTime() {
        CronTask created = createTask.create(actor, developer, SERVICE, "Nightly Import",
                "  0   3 * * * ", "Asia/Ho_Chi_Minh", List.of("php", "artisan", "import"), 600,
                ConcurrencyPolicy.FORBID, true);

        assertThat(created.name()).isEqualTo("nightly-import");
        assertThat(created.schedule()).isEqualTo("0 3 * * *");
        assertThat(created.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(created.command()).containsExactly("php", "artisan", "import");
        assertThat(created.nextRunAt()).isNotNull().isAfter(Instant.now());
        assertThat(created.lastRunAt()).isNull();
        verify(specs).forService(eq(SERVICE), anyString());
    }

    @Test
    void aStaticSiteCannotScheduleAnythingBecauseThereIsNoContainerToRunItIn() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.site(SERVICE, PROJECT)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of("echo", "hi"), 60, ConcurrencyPolicy.FORBID,
                        true))
                .withMessageContaining("no container to run a command in");
        verify(tasks, never()).save(any());
        verify(specs, never()).forService(any(), anyString());
    }

    @Test
    void anEntryCostsOneAgainstThePlan() {
        createTask.create(actor, developer, SERVICE, "nightly", "0 3 * * *", "UTC",
                List.of("echo", "hi"), 60, ConcurrencyPolicy.FORBID, true);

        verify(quotas).require(ORGANIZATION, QuotaResource.CRON_TASK, 1);
    }

    @Test
    void anEmptyCommandIsRefusedBecauseThereWouldBeNothingToRun() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of(), 60, ConcurrencyPolicy.FORBID, true))
                .matches(rejected -> "command".equals(rejected.field()));
    }

    @Test
    void aScheduleTheNodeCouldNotParseIsRefusedWithTheFieldNamed() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 99 * * *", "UTC", List.of("echo"), 60, ConcurrencyPolicy.FORBID, true))
                .matches(rejected -> "schedule".equals(rejected.field()));
        verify(tasks, never()).save(any());
    }

    @Test
    void aTimeoutIsMandatoryAndBounded() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of("echo"), 0, ConcurrencyPolicy.FORBID, true))
                .matches(rejected -> "timeoutSeconds".equals(rejected.field()));
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of("echo"), 90_000, ConcurrencyPolicy.FORBID,
                        true));
    }

    @Test
    void aDisabledEntryIsStillStoredSoItsScheduleSurvivesBeingSwitchedOff() {
        CronTask created = createTask.create(actor, developer, SERVICE, "nightly", "0 3 * * *",
                "UTC", List.of("echo"), 60, ConcurrencyPolicy.FORBID, false);

        assertThat(created.enabled()).isFalse();
        assertThat(created.schedule()).isEqualTo("0 3 * * *");
    }

    @Test
    void aMissingPolicyDefaultsToSkippingRatherThanOverlapping() {
        CronTask created = createTask.create(actor, developer, SERVICE, "nightly", "0 3 * * *",
                "UTC", List.of("echo"), 60, null, true);

        assertThat(created.concurrencyPolicy()).isEqualTo(ConcurrencyPolicy.FORBID);
        assertThat(created.concurrencyPolicy().allowsOverlap()).isFalse();
    }

    @Test
    void aNameAlreadyInUseIsRefused() {
        given(tasks.existsByServiceIdAndName(SERVICE, "nightly")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createTask.create(actor, developer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of("echo"), 60, ConcurrencyPolicy.FORBID, true))
                .matches(rejected -> "name".equals(rejected.field()));
    }

    @Test
    void aViewerIsRefusedBeforeTheServiceIsLoaded() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> createTask.create(actor, viewer, SERVICE, "nightly",
                        "0 3 * * *", "UTC", List.of("echo"), 60, ConcurrencyPolicy.FORBID, true));
        verify(services, never()).findOwnedBy(any(), any());
    }
}
