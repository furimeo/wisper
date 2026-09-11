package lhqm.furimeo.wisper.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The property every other package is relying on without knowing it: writing the trail
 * cannot break the action being audited.
 *
 * <p>This is the only place in the codebase where a failed write is swallowed, so it is
 * worth a test that says so out loud. If somebody later "fixes" the swallow, a panel that
 * cannot reach {@code audit_log} stops being able to stop containers - and the failure
 * arrives during whatever incident made the database slow in the first place.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogRecorderTest {

    private static final Instant NOW = Instant.parse("2026-02-11T09:15:00Z");

    @Mock
    private AppendAuditLog append;

    private AuditLogRecorder recorder() {
        return new AuditLogRecorder(append, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anEntryIsWrittenWithTheInstantItHappened() {
        AuditEntry entry = AuditEntry.succeeded(AuditActor.system("backup-prune"), "backup.delete",
                AuditTarget.of("backup", UUID.randomUUID(), "nightly"), null,
                "retention removed 3 snapshots");

        recorder().record(entry);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(append).write(any(AuditEntry.class), at.capture());
        assertThat(at.getValue()).isEqualTo(NOW);
    }

    @Test
    void aDatabaseFailureIsSwallowedSoTheActionItAuditsStillCompletes() {
        given(append.write(any(), any()))
                .willThrow(new IllegalStateException("the pool is exhausted"));

        assertThatNoException().isThrownBy(() -> recorder().record(
                AuditEntry.succeeded(AuditActor.system("test"), "service.stop",
                        AuditTarget.of("service", UUID.randomUUID(), "api"), null, "")));
    }

    @Test
    void aNullEntryIsAMistakeInTheCallerAndNotAFailureOfTheAction() {
        assertThatNoException().isThrownBy(() -> recorder().record(null));
    }

    @Test
    void aRefusalIsRecordedJustLikeASuccess() {
        AuditEntry denied = AuditEntry.denied(
                AuditActor.system("test"), "files.path_escape",
                AuditTarget.of("service", UUID.randomUUID(), "api"), null,
                "the resolved path left its root");

        recorder().record(denied);

        ArgumentCaptor<AuditEntry> written = ArgumentCaptor.forClass(AuditEntry.class);
        verify(append).write(written.capture(), any());
        assertThat(written.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
    }
}
