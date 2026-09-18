package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
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

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.DatabaseStatus;

/**
 * Writing back what a node measured, and - the part that matters - refusing to write back
 * what it did not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordDatabaseStatusTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ENGINE = UUID.randomUUID();
    private static final UUID DATABASE = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSES = UUID.randomUUID();
    private static final Instant OBSERVED = Instant.parse("2026-02-01T09:15:30Z");
    private static final long QUOTA = 1_000_000L;

    @Mock
    private DatabaseEngineRepository engines;

    @Mock
    private ManagedDatabaseRepository databases;

    @Mock
    private TrackDatabaseSize sizes;

    @Mock
    private EnforceDatabaseQuota quotas;

    @InjectMocks
    private RecordDatabaseStatus statuses;

    @BeforeEach
    void oneDatabaseOnThisNode() {
        given(databases.findAllOnNode(NODE)).willReturn(List.of(ready()));
        given(sizes.record(any(), any(), anyLong(), any())).willReturn(true);
    }

    @Test
    void aMeasurementIsWrittenAgainstTheDatabaseTheNodeActuallyHolds() {
        statuses.accept(NODE, List.of(status(DATABASE, true, 400_000L).build()), OBSERVED);

        verify(sizes).record(NODE, DATABASE, 400_000L, OBSERVED);
    }

    @Test
    void theNodesOwnMeasurementTimeIsKeptRatherThanTheArrivalTime() {
        Instant measured = Instant.parse("2026-02-01T09:15:00Z");
        statuses.accept(NODE, List.of(status(DATABASE, true, 400_000L)
                .setMeasuredAt(Timestamp.newBuilder().setSeconds(measured.getEpochSecond()))
                .build()), OBSERVED);

        verify(sizes).record(NODE, DATABASE, 400_000L, measured);
    }

    @Test
    void aGrantThisNodeDoesNotHoldIsIgnoredRatherThanWritten() {
        // A node authenticates as itself and then names ids. Without the check it could
        // report on a database on somebody else's machine.
        statuses.accept(NODE, List.of(status(SOMEBODY_ELSES, true, 999L).build()), OBSERVED);

        verify(sizes, never()).record(any(), any(), anyLong(), any());
        verify(quotas, never()).apply(any(), anyBoolean(), anyLong());
    }

    @Test
    void anIdThatIsNotEvenAUuidDoesNotFailTheWholeBatch() {
        statuses.accept(NODE, List.of(
                DatabaseStatus.newBuilder().setId("not-a-uuid").setExists(true).build(),
                status(DATABASE, true, 400_000L).build()), OBSERVED);

        verify(sizes).record(NODE, DATABASE, 400_000L, OBSERVED);
    }

    @Test
    void aDatabaseTheNodeCannotSeeIsLeftExactlyAsItWas() {
        // The engine container is restarting, or a restore is half finished. "Cannot see
        // it" is not "does not exist": nothing is written and nothing is concluded.
        statuses.accept(NODE, List.of(status(DATABASE, false, 0L).build()), OBSERVED);

        verify(sizes, never()).record(any(), any(), anyLong(), any());
        verify(quotas, never()).apply(any(), anyBoolean(), anyLong());
    }

    @Test
    void aQuotaDecisionIsOnlyAskedForWhenTheStateCouldActuallyMove() {
        statuses.accept(NODE, List.of(status(DATABASE, true, QUOTA / 2).build()), OBSERVED);

        verify(quotas, never()).apply(any(), anyBoolean(), anyLong());
    }

    @Test
    void aMeasurementOverTheLimitReachesThePolicy() {
        statuses.accept(NODE, List.of(status(DATABASE, true, QUOTA + 1)
                .setOverQuota(true).build()), OBSERVED);

        verify(quotas).apply(DATABASE, true, QUOTA + 1);
    }

    @Test
    void anEngineThatAnsweredAboutSomethingIsRecordedAsRunning() {
        statuses.accept(NODE, List.of(status(DATABASE, true, 400_000L).build()), OBSERVED);

        verify(sizes).recordEngine(eq(NODE), eq(ENGINE), eq("RUNNING"), eq(OBSERVED),
                eq(400_000L), eq((String) null));
    }

    @Test
    void anEngineWhoseEveryDatabaseCarriesAnErrorIsRecordedAsFailed() {
        statuses.accept(NODE, List.of(status(DATABASE, false, 0L)
                .setLastError("connection refused").build()), OBSERVED);

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(sizes).recordEngine(eq(NODE), eq(ENGINE), state.capture(), any(), anyLong(),
                eq("connection refused"));
        assertThat(state.getValue()).isEqualTo("FAILED");
    }

    @Test
    void anEngineNothingWasSaidAboutIsNotTouched() {
        statuses.accept(NODE, List.of(), OBSERVED);

        verify(sizes, never()).recordEngine(any(), any(), anyString(), any(), anyLong(), any());
    }

    private static DatabaseStatus.Builder status(UUID id, boolean exists, long sizeBytes) {
        return DatabaseStatus.newBuilder()
                .setId(id.toString())
                .setEngine(lhqm.furimeo.wisper.proto.v1.DatabaseEngine.DATABASE_ENGINE_POSTGRES)
                .setExists(exists)
                .setSizeBytes(sizeBytes)
                .setQuotaBytes(QUOTA);
    }

    private static ManagedDatabase ready() {
        return ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, "acme_app", "acme_app_abcdef",
                        "v1.nonce.secret", "UTF8", null, QUOTA)
                .ready(Instant.parse("2026-02-01T09:00:00Z"));
    }
}
