package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.proto.v1.WorkloadPhase;
import lhqm.furimeo.wisper.proto.v1.WorkloadStatus;

/**
 * Writing back what a node observed, and - more importantly - refusing to write back what
 * it did not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordWorkloadStatusTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final Instant OBSERVED = Instant.parse("2026-02-01T09:15:30Z");

    @Mock
    private PlacementRepository placements;

    @InjectMocks
    private RecordWorkloadStatus statuses;

    @Test
    void aRunningWorkloadIsRecordedAsRunningAndHealthy() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_RUNNING)
                .setContainerId("abc123")
                .setImageDigest("sha256:beef")
                .setRestartCount(2)
                .build()), OBSERVED, false);

        verify(placements).recordReport(NODE, SERVICE, "RUNNING", OBSERVED, "abc123",
                "sha256:beef", 2, null, null, "HEALTHY");
    }

    @Test
    void pullingAndStartingBothReadAsComingUp() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_PULLING).build(),
                status(WorkloadPhase.WORKLOAD_PHASE_STARTING).build()), OBSERVED, false);

        verify(placements, org.mockito.Mockito.times(2)).recordReport(eq(NODE), eq(SERVICE),
                eq("CREATING"), any(), any(), any(), anyInt(), any(), any(), eq("UNKNOWN"));
    }

    @Test
    void aCrashLoopIsCrashedAndUnhealthyAndKeepsItsExitCode() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_CRASH_LOOPING)
                .setExitCode(137)
                .setMessage("OOM killed")
                .build()), OBSERVED, false);

        verify(placements).recordReport(NODE, SERVICE, "CRASHED", OBSERVED, null, null, 0,
                137, "OOM killed", "UNHEALTHY");
    }

    @Test
    void aRunningWorkloadIsNotGivenAnExitCodeItNeverHad() {
        // proto3 cannot tell "did not exit" from "exited zero", and storing that zero would
        // make every running service look like it had just finished successfully.
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_RUNNING).build()),
                OBSERVED, false);

        ArgumentCaptor<Integer> exitCode = ArgumentCaptor.forClass(Integer.class);
        verify(placements).recordReport(any(), any(), anyString(), any(), any(), any(), anyInt(),
                exitCode.capture(), any(), anyString());
        assertThat(exitCode.getValue()).isNull();
    }

    @Test
    void aPartialBatchDoesNotTurnAKnownStateIntoUnknown() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_UNKNOWN).build()),
                OBSERVED, true);

        verify(placements, never()).recordReport(any(), any(), anyString(), any(), any(), any(),
                anyInt(), any(), any(), anyString());
    }

    @Test
    void aCompleteBatchMayReportUnknownBecauseTheNodeLookedAndCouldNotTell() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_UNKNOWN).build()),
                OBSERVED, false);

        verify(placements).recordReport(eq(NODE), eq(SERVICE), eq("UNKNOWN"), any(), any(), any(),
                anyInt(), any(), any(), eq("UNKNOWN"));
    }

    @Test
    void aPartialBatchStillRecordsTheWorkloadsItDidSee() {
        statuses.accept(NODE, List.of(status(WorkloadPhase.WORKLOAD_PHASE_RUNNING).build()),
                OBSERVED, true);

        verify(placements).recordReport(eq(NODE), eq(SERVICE), eq("RUNNING"), any(), any(), any(),
                anyInt(), any(), any(), eq("HEALTHY"));
    }

    @Test
    void aWorkloadIdThatIsNotAServiceIdIsDroppedRatherThanFailingTheBatch() {
        statuses.accept(NODE, List.of(
                WorkloadStatus.newBuilder().setWorkloadId("some-stray-container")
                        .setPhase(WorkloadPhase.WORKLOAD_PHASE_RUNNING).build(),
                status(WorkloadPhase.WORKLOAD_PHASE_RUNNING).build()), OBSERVED, false);

        verify(placements, org.mockito.Mockito.times(1)).recordReport(any(), any(), anyString(),
                any(), any(), any(), anyInt(), any(), any(), anyString());
    }

    @Test
    void anEmptyBatchWritesNothing() {
        statuses.accept(NODE, List.of(), OBSERVED, false);
        statuses.accept(NODE, null, OBSERVED, false);

        verifyNoInteractions(placements);
    }

    private static WorkloadStatus.Builder status(WorkloadPhase phase) {
        return WorkloadStatus.newBuilder().setWorkloadId(SERVICE.toString()).setPhase(phase);
    }
}
