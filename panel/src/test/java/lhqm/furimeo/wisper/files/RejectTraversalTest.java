package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditOutcome;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.proto.v1.FileError;
import lhqm.furimeo.wisper.proto.v1.FileErrorCode;
import lhqm.furimeo.wisper.proto.v1.FileRequest;
import lhqm.furimeo.wisper.proto.v1.StatPath;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * What happens when a path tries to leave its root.
 *
 * <p>The file manager is the largest attack surface in v1 (AGENTS.md §5), and the panel's
 * answer to a traversal has three parts that are each easy to get subtly wrong:
 *
 * <ol>
 * <li>It is refused, whether the panel caught it or the node did.</li>
 * <li>It produces exactly one {@code files.path_escape} entry with
 *     {@link AuditOutcome#DENIED} - one, not zero and not two. Two paths write that entry
 *     ({@link DispatchFileRequest} when the node refuses, {@link ReportFileFailure} when
 *     the panel does) and a request that goes through both must not double the trail.</li>
 * <li>The answer says nothing about what would have worked. The browser sends paths it was
 *     given, so a customer cannot type one by accident, and the only reader of a helpful
 *     message is somebody probing.</li>
 * </ol>
 *
 * <p>{@link RelativePathTest} covers which strings are refused. This covers what the panel
 * then does about it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RejectTraversalTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private static final FilesSettings SETTINGS = new FilesSettings(
            DataSize.ofMegabytes(8), Duration.ofHours(24), 200, 1000, DataSize.ofMegabytes(2),
            Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(15),
            Duration.ofSeconds(20), Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofSeconds(20));

    @Mock
    private NodeFiles nodeFiles;

    @Mock
    private AuditTrail audit;

    private DispatchFileRequest dispatch;
    private ReportFileFailure failures;
    private FileAccess access;
    private RedirectAttributes flash;

    @BeforeEach
    void setUp() {
        dispatch = new DispatchFileRequest(nodeFiles, audit, SETTINGS);
        failures = new ReportFileFailure(audit);
        flash = new RedirectAttributesModelMap();
        access = new FileAccess(
                new ServiceLocation(SERVICE, UUID.randomUUID(), ORGANIZATION, NODE, "api", "API",
                        ServiceKind.APP),
                new Membership(ORGANIZATION, ACCOUNT, MemberRole.DEVELOPER),
                FileRootRef.volume(UUID.randomUUID(), "data (/data)", false, 0),
                AuditActor.system("test"));
    }

    @AfterEach
    void tearDown() {
        dispatch.close();
    }

    @Test
    void theNodeRefusingAPathWritesOneDeniedEntryNamingTheRoot() {
        given(nodeFiles.call(any(), any())).willThrow(traversal("../../etc/shadow"));

        assertThatExceptionOfType(FileOperationFailed.class)
                .isThrownBy(() -> dispatch.call(access, statOf("logs/app.log")))
                .matches(FileOperationFailed::isTraversalAttempt);

        AuditEntry entry = recorded();
        assertThat(entry.action()).isEqualTo("files.path_escape");
        assertThat(entry.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(entry.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(entry.detail()).contains("data (/data)").contains("../../etc/shadow");
    }

    @Test
    void aTraversalTheNodeRefusedIsNotRecordedASecondTimeByTheController() {
        FileOperationFailed refused = new FileOperationFailed(
                traversal("../../etc/shadow").error());

        failures.of(access, "files.delete", refused, flash);

        // DispatchFileRequest already wrote it. A second entry here would double every
        // attempt in the trail and make the count meaningless.
        verify(audit, never()).record(any());
        assertThat(flashFailure()).isEqualTo("That path was refused.");
    }

    @Test
    void aTraversalThePanelRefusedIsRecordedBecauseNothingElseHas() {
        failures.of(access, "files.delete", PathRejected.traversal("../../etc/shadow"), flash);

        AuditEntry entry = recorded();
        assertThat(entry.action()).isEqualTo("files.path_escape");
        assertThat(entry.outcome()).isEqualTo(AuditOutcome.DENIED);
    }

    @Test
    void theCustomerIsToldNothingAboutWhatWouldHaveWorked() {
        failures.of(access, "files.delete", PathRejected.traversal("../../etc/shadow"), flash);

        assertThat(flashFailure())
                .isEqualTo("That path leaves its root and was refused.")
                .doesNotContain("..")
                .doesNotContain("etc");
    }

    @Test
    void aRefusedRoleIsADeniedEntryUnderTheActionThatWasAttempted() {
        failures.of(access, "files.delete",
                new PermissionDenied("files.delete", MemberRole.VIEWER, "DEVELOPER"), flash);

        AuditEntry entry = recorded();
        assertThat(entry.action()).isEqualTo("files.delete");
        assertThat(entry.outcome()).isEqualTo(AuditOutcome.DENIED);
    }

    @Test
    void aPlatformFailureIsNotADenial() {
        // A node that is away is not somebody being refused. Recording it as one buries the
        // handful of entries that mean "a person tried to do something they may not".
        failures.of(access, "files.delete", new NodeOffline(NODE), flash);

        verify(audit, never()).record(any());
        assertThat(flashFailure()).contains("not reachable");
    }

    @Test
    void anOrdinaryFileErrorIsNeitherAuditedNorMistakenForATraversal() {
        given(nodeFiles.call(any(), any())).willThrow(new FileOperationFailed(FileError.newBuilder()
                .setCode(FileErrorCode.FILE_ERROR_CODE_NOT_FOUND)
                .setDetail("There is no file called app.log here.")
                .setPath("logs/app.log")
                .build()));

        assertThatExceptionOfType(FileOperationFailed.class)
                .isThrownBy(() -> dispatch.call(access, statOf("logs/app.log")))
                .matches(failure -> !failure.isTraversalAttempt());

        verify(audit, never()).record(any());
    }

    private static FileRequest.Builder statOf(String path) {
        return FileRequest.newBuilder()
                .setStat(StatPath.newBuilder().setPath(RelativePath.of(path).value()));
    }

    private static FileOperationFailed traversal(String path) {
        return new FileOperationFailed(FileError.newBuilder()
                .setCode(FileErrorCode.FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
                .setDetail("The resolved path left its root.")
                .setPath(path)
                .build());
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> written = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(written.capture());
        return written.getValue();
    }

    private String flashFailure() {
        Object messages = flash.getFlashAttributes().get("flash");
        assertThat(messages).isNotNull();
        return String.valueOf(((java.util.Map<?, ?>) messages).get("error"));
    }
}
