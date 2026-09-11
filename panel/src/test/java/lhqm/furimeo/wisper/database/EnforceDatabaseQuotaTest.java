package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * What happens when a database outgrows its ceiling, and - more importantly - what does
 * not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnforceDatabaseQuotaTest {

    private static final UUID DATABASE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ENGINE = UUID.randomUUID();
    private static final long QUOTA = 1_000_000L;

    @Mock
    private ManagedDatabaseRepository databases;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private EnforceDatabaseQuota enforce;

    @Test
    void aDatabaseOverItsLimitIsSuspendedAndTold() {
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready(null)));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));

        ManagedDatabaseState state = enforce.apply(DATABASE, true, QUOTA + 1);

        assertThat(state).isEqualTo(ManagedDatabaseState.SUSPENDED);
        ArgumentCaptor<ManagedDatabase> saved = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databases).save(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(ManagedDatabaseState.SUSPENDED);
        assertThat(saved.getValue().lastError()).contains("Delete data or raise the quota");
        verify(audit).record(any());
    }

    @Test
    void nothingIsEverDroppedOrRevokedForBeingOverQuota() {
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready(null)));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));

        enforce.apply(DATABASE, true, QUOTA * 10);

        // Neither engine can refuse the write that went over, so the panel's only honest
        // move is to say so. Deleting a customer's data because it grew is not a policy.
        verify(databases, never()).delete(any());
        verify(databases, never()).deleteById(any());
    }

    @Test
    void backUnderTheLimitResumesWithoutAnybodyPressingAnything() {
        given(databases.findById(DATABASE))
                .willReturn(Optional.of(ready(null).suspended("over quota")));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));

        ManagedDatabaseState state = enforce.apply(DATABASE, false, QUOTA / 2);

        assertThat(state).isEqualTo(ManagedDatabaseState.READY);
        ArgumentCaptor<ManagedDatabase> saved = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databases).save(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(ManagedDatabaseState.READY);
        assertThat(saved.getValue().lastError()).isNull();
    }

    @Test
    void aMeasurementThatChangesNothingWritesNothing() {
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready(null)));

        ManagedDatabaseState state = enforce.apply(DATABASE, false, QUOTA / 2);

        assertThat(state).isEqualTo(ManagedDatabaseState.READY);
        verify(databases, never()).save(any());
        verify(audit, never()).record(any());
    }

    @Test
    void thePanelsOwnCeilingWinsOverTheFlagTheNodeComputed() {
        // The customer raised the quota a second ago; the node is still comparing against
        // the old one and says over. Suspending here would undo the fix they just made.
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready(null)));

        ManagedDatabaseState state = enforce.apply(DATABASE, true, QUOTA - 1);

        assertThat(state).isEqualTo(ManagedDatabaseState.READY);
        verify(databases, never()).save(any());
    }

    @Test
    void raisingTheQuotaLiftsTheSuspensionOnTheSameRequest() {
        ManagedDatabase suspended = ready(QUOTA * 2).suspended("over quota").withQuota(QUOTA * 4);
        given(databases.findById(DATABASE)).willReturn(Optional.of(suspended));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));

        assertThat(enforce.reevaluate(DATABASE)).isEqualTo(ManagedDatabaseState.READY);
    }

    @Test
    void aDatabaseNobodyHasMeasuredIsLeftAloneRatherThanTreatedAsEmpty() {
        // Null usage is "not measured", not zero. Resuming on it would clear a suspension
        // for a database that is still over.
        given(databases.findById(DATABASE))
                .willReturn(Optional.of(ready(null).suspended("over quota")));

        assertThat(enforce.reevaluate(DATABASE)).isEqualTo(ManagedDatabaseState.SUSPENDED);
        verify(databases, never()).save(any());
    }

    @Test
    void aDatabaseThatIsGoneIsNotAnError() {
        given(databases.findById(DATABASE)).willReturn(Optional.empty());

        assertThat(enforce.apply(DATABASE, true, QUOTA * 2)).isNull();
        verify(databases, never()).save(any());
    }

    /** A ready database with an optional last measurement. */
    private static ManagedDatabase ready(Long usedBytes) {
        ManagedDatabase database = ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, "acme_app",
                        "acme_app_abcdef", "v1.nonce.secret", "UTF8", null, QUOTA)
                .ready(Instant.parse("2026-02-01T09:00:00Z"));
        if (usedBytes == null) {
            return database;
        }
        return new ManagedDatabase(database.id(), database.projectId(),
                database.databaseEngineId(), database.name(), database.dbUsername(),
                database.dbPassword(), database.dbCharset(), database.dbCollation(),
                database.quotaBytes(), usedBytes, Instant.parse("2026-02-01T09:05:00Z"),
                database.state(), database.lastError(), database.provisionedAt(),
                database.passwordRotatedAt(), database.createdAt(), database.updatedAt(),
                database.version());
    }
}
