package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.proto.v1.ByteRange;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.OperationDone;
import lhqm.furimeo.wisper.proto.v1.UploadAck;
import lhqm.furimeo.wisper.proto.v1.UploadState;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * A phone loses signal half way through a 24 MB upload and comes back.
 *
 * <p>This is the scenario the design calls out by name (§8.2, §11.7): most customers are on
 * mobile data, and an upload that has to start again is an upload that never finishes. The
 * properties that make it work are all here.
 *
 * <p>The one that is a security property as well: <strong>the offset is derived from the
 * chunk index and the session, never taken from the client.</strong> A resumed upload that
 * trusts a client-supplied offset can be made to write anywhere in the destination file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeUploadAfterDropTest {

    private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");
    private static final int CHUNK = 8 * 1024 * 1024;
    private static final long TOTAL = 3L * CHUNK;
    private static final String SESSION = "b3f1c2d4-aaaa-bbbb-cccc-0123456789ab";
    private static final String CHECKSUM = "a".repeat(64);

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private static final FilesSettings SETTINGS = new FilesSettings(
            DataSize.ofBytes(CHUNK), Duration.ofHours(24), 200, 1000, DataSize.ofMegabytes(2),
            Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(15),
            Duration.ofSeconds(20), Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofSeconds(20), Duration.ofMinutes(5));

    @Mock
    private DispatchFileRequest dispatch;

    private UploadSessions sessions;
    private BeginUpload begin;
    private ReceiveUploadChunk receive;
    private FileAccess access;

    @BeforeEach
    void setUp() {
        sessions = new UploadSessions();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        begin = new BeginUpload(dispatch, sessions, SETTINGS, clock);
        receive = new ReceiveUploadChunk(dispatch, sessions, clock);
        access = new FileAccess(
                new ServiceLocation(SERVICE, UUID.randomUUID(), ORGANIZATION, NODE, "api", "API",
                        ServiceKind.APP),
                new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER),
                FileRootRef.volume(UUID.randomUUID(), "data (/data)", false, 5L * 1024 * 1024 * 1024),
                AuditActor.system("test"));
    }

    @Test
    void aFreshUploadStartsAtZeroAndIsToldTheChunkSize() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));

        UploadProgress progress = begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL,
                CHECKSUM, false);

        assertThat(progress.known()).isFalse();
        assertThat(progress.nextOffset()).isZero();
        assertThat(progress.chunkSize()).isEqualTo(CHUNK);
        assertThat(sessions.tracked()).isEqualTo(1);
    }

    @Test
    void eachChunkIsWrittenAtAnOffsetDerivedFromItsIndex() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);
        given(dispatch.call(any(), any())).willReturn(answer(ack(1, 2L * CHUNK)));

        receive.accept(access, SESSION, 1, null, new byte[CHUNK]);

        FileRequest written = captureRequest();
        assertThat(written.getWrite().getChunk().getOffset()).isEqualTo((long) CHUNK);
        assertThat(written.getWrite().getChunk().getChunkIndex()).isEqualTo(1);
        assertThat(written.getWrite().getChunk().getLast()).isFalse();
        // The session travels with every chunk: the node may have restarted between two of
        // them and has to be able to rebuild it from what is on disk plus this message.
        assertThat(written.getWrite().getSession().getPath()).isEqualTo("dump.sql");
        assertThat(written.getWrite().getSession().getTotalBytes()).isEqualTo(TOTAL);
        assertThat(written.getWrite().getSession().getContentSha256()).isEqualTo(CHECKSUM);
    }

    @Test
    void theLastChunkIsMarkedSoTheNodeKnowsTheTransferIsOver() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);
        given(dispatch.call(any(), any())).willReturn(answer(ack(2, TOTAL)));

        receive.accept(access, SESSION, 2, null, new byte[CHUNK]);

        assertThat(captureRequest().getWrite().getChunk().getLast()).isTrue();
    }

    @Test
    void afterTheConnectionDropsTheSessionIsReopenedAndResumesAtTheFirstGap() {
        // Two chunks landed, the third was in flight when the lift doors closed.
        given(dispatch.call(any(), any()))
                .willReturn(answer(knownState(range(0, 2L * CHUNK), TOTAL)));

        UploadProgress resumed = begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL,
                CHECKSUM, false);

        assertThat(resumed.known()).isTrue();
        assertThat(resumed.nextOffset()).isEqualTo(2L * CHUNK);
        assertThat(resumed.nextChunkIndex()).isEqualTo(2);
        // The browser's own id comes back, not the prefixed one the node was given.
        assertThat(resumed.sessionId()).isEqualTo(SESSION);
        assertThat(resumed.complete()).isFalse();
    }

    @Test
    void theNodeIsAddressedByAnIdNamespacedToTheServiceSoTwoTenantsCannotCollide() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));

        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);

        assertThat(captureRequest().getResume().getSessionId())
                .isEqualTo(SERVICE.toString().substring(0, 8) + "_" + SESSION);
    }

    @Test
    void aChunkForASessionThisPanelHasForgottenTellsTheClientToStartTheSessionAgain() {
        assertThatExceptionOfType(UploadSessionUnknown.class)
                .isThrownBy(() -> receive.accept(access, SESSION, 0, null, new byte[16]));
    }

    @Test
    void aChunkIndexPastTheEndOfTheFileIsRefusedBeforeItReachesTheNode() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);

        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> receive.accept(access, SESSION, 9, null, new byte[16]));
    }

    @Test
    void aChunkLargerThanTheAgreedSizeIsRefused() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);

        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> receive.accept(access, SESSION, 0, null, new byte[CHUNK + 1]));
    }

    @Test
    void theBrowsersOwnChunkChecksumIsForwardedRatherThanRecomputed() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);
        given(dispatch.call(any(), any())).willReturn(answer(ack(0, CHUNK)));
        String fromBrowser = "b".repeat(64);

        receive.accept(access, SESSION, 0, fromBrowser, new byte[64]);

        // Recomputing here would make the check cover only the panel-to-node hop and hide
        // corruption on the hop that actually corrupts things.
        assertThat(captureRequest().getWrite().getChunk().getSha256()).isEqualTo(fromBrowser);
    }

    @Test
    void aChunkWithNoChecksumFromTheClientGetsOneFromThePanelRatherThanNone() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);
        given(dispatch.call(any(), any())).willReturn(answer(ack(0, CHUNK)));

        receive.accept(access, SESSION, 0, null, new byte[64]);

        assertThat(captureRequest().getWrite().getChunk().getSha256())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void aSessionIdThatCouldNotBeAFilenameIsRefused() {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> UploadTicket.requireSessionId("../../etc/passwd"));
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> UploadTicket.requireSessionId("short"));
    }

    @Test
    void anIdleSessionIsAbandonedSoItsPartsStopCountingAgainstTheQuota() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);

        SweepStaleUploads sweep = new SweepStaleUploads(dispatch, sessions, SETTINGS,
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        given(dispatch.callOnNode(eq(NODE), any(), any())).willReturn(answer(done()));

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(sessions.tracked()).isZero();
    }

    @Test
    void aSessionThatIsStillMovingIsLeftAlone() {
        given(dispatch.call(any(), any())).willReturn(answer(unknownState()));
        begin.start(access, SESSION, RelativePath.of("dump.sql"), TOTAL, CHECKSUM, false);

        SweepStaleUploads sweep = new SweepStaleUploads(dispatch, sessions, SETTINGS,
                Clock.fixed(NOW.plus(Duration.ofMinutes(1)), ZoneOffset.UTC));

        assertThat(sweep.sweep()).isZero();
        assertThat(sessions.tracked()).isEqualTo(1);
    }

    private FileRequest captureRequest() {
        ArgumentCaptor<FileRequest.Builder> request =
                ArgumentCaptor.forClass(FileRequest.Builder.class);
        verify(dispatch, atLeastOnce()).call(any(), request.capture());
        List<FileRequest.Builder> all = request.getAllValues();
        return all.get(all.size() - 1).build();
    }

    private static FileAnswer answer(FileEvent event) {
        return FileAnswer.of(event);
    }

    private static FileEvent unknownState() {
        return FileEvent.newBuilder()
                .setState(UploadState.newBuilder().setSessionId(SESSION).setKnown(false))
                .build();
    }

    private static FileEvent knownState(ByteRange received, long total) {
        return FileEvent.newBuilder()
                .setState(UploadState.newBuilder()
                        .setSessionId(SERVICE.toString().substring(0, 8) + "_" + SESSION)
                        .setKnown(true)
                        .setTotalBytes(total)
                        .addReceived(received)
                        .setReceivedBytes(received.getEndExclusive() - received.getStart()))
                .build();
    }

    private static FileEvent ack(long index, long received) {
        return FileEvent.newBuilder()
                .setAck(UploadAck.newBuilder()
                        .setSessionId(SERVICE.toString().substring(0, 8) + "_" + SESSION)
                        .setChunkIndex(index)
                        .setReceivedBytes(received)
                        .setNextOffset(received))
                .build();
    }

    private static FileEvent done() {
        return FileEvent.newBuilder()
                .setDone(OperationDone.newBuilder().setAffected(0))
                .build();
    }

    private static ByteRange range(long start, long endExclusive) {
        return ByteRange.newBuilder().setStart(start).setEndExclusive(endExclusive).build();
    }
}
