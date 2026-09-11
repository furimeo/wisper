package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

import com.google.protobuf.ByteString;

import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.DoctorCheck;
import lhqm.furimeo.wisper.proto.v1.DoctorOutcome;
import lhqm.furimeo.wisper.proto.v1.DoctorReport;
import lhqm.furimeo.wisper.proto.v1.DoctorSeverity;
import lhqm.furimeo.wisper.proto.v1.EnrollRequest;
import lhqm.furimeo.wisper.proto.v1.EnrollResponse;
import lhqm.furimeo.wisper.proto.v1.MachineFacts;

/**
 * Enrolment is the one unauthenticated call in the panel, so these are the refusals that
 * matter: a token that expired, a token somebody already used, a token that was never
 * issued, and a protocol the panel cannot speak (design §7.1, §7.3, §7.5).
 *
 * <p>Each one also asserts that <strong>nothing was written</strong>. A refusal that still
 * spends the token would cost an operator a reinstall for a request that was rejected.
 *
 * <p>Over 300 lines and not split, because the eleven cases share one request builder and
 * one doctor report and every one of them is a different way the same method says no.
 * Splitting them would duplicate the fixture and hide the fact that the order of the
 * checks - shape, protocol, token, record, proof, preflight, fingerprint - is itself part
 * of what is being asserted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrolNodeTest {

    private static final UUID NODE_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final String TOKEN_TEXT = "wsp_a-single-use-bootstrap-token-for-a-test";
    private static final String FINGERPRINT =
            "3d0f7a1b5c2e9d8476a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f607";
    private static final String ADDRESS = "198.51.100.7:52344";

    @Mock
    private NodeRepository nodes;

    @Mock
    private NodeEnrollmentTokenRepository tokens;

    @Mock
    private DetectClonedNode clones;

    @Mock
    private RecordMachineFacts machineFacts;

    @Mock
    private AuditTrail audit;

    private final EnrolmentIdentity identity = EnrolmentIdentity.generate();
    private EnrolNode enrolNode;

    @BeforeEach
    void aFreshRecordAndALiveToken() {
        NodeSettings settings = new NodeSettings(Duration.ofSeconds(60), Duration.ofSeconds(20),
                Duration.ofMinutes(15), Duration.ofSeconds(15), 15, 15, 20, "panel.example:9090",
                Path.of("./var/dist"), "");
        enrolNode = new EnrolNode(nodes, tokens, new VerifyEnrolmentProof(), clones,
                machineFacts, audit, settings);

        given(nodes.findById(NODE_ID)).willReturn(Optional.of(
                Node.created(NODE_ID, "node-a", "", "203.0.113.9", List.of())));
        given(nodes.save(any())).willAnswer(call -> call.getArgument(0));
        given(tokens.save(any())).willAnswer(call -> call.getArgument(0));
        givenToken(liveToken());
    }

    @Test
    void aValidEnrolmentIssuesACredentialAndSpendsTheTokenInTheSameGo() {
        EnrollResponse response = enrolNode.enrol(request(), ADDRESS, "abc123");

        assertThat(response.getNodeId()).isEqualTo(NODE_ID.toString());
        assertThat(response.getCredential()).startsWith(NodeSecret.CREDENTIAL_PREFIX);
        assertThat(response.getNodeName()).isEqualTo("node-a");
        assertThat(response.getProtocolVersion()).isEqualTo(NodeProtocol.SUPPORTED);
        assertThat(response.getPanelCertificateSha256()).isEqualTo("abc123");
        assertThat(response.getHeartbeatIntervalSeconds()).isEqualTo(20);

        ArgumentCaptor<Node> saved = ArgumentCaptor.forClass(Node.class);
        verify(nodes).save(saved.capture());
        assertThat(saved.getValue().lifecycle()).isEqualTo(NodeLifecycle.ENROLLED);
        assertThat(saved.getValue().fingerprint()).isEqualTo(FINGERPRINT);
        // The credential is hashed, never stored: the panel only ever compares it.
        assertThat(saved.getValue().credentialHash())
                .isEqualTo(NodeSecret.hashOf(response.getCredential()));

        ArgumentCaptor<NodeEnrollmentToken> spent =
                ArgumentCaptor.forClass(NodeEnrollmentToken.class);
        verify(tokens).save(spent.capture());
        assertThat(spent.getValue().isSpent()).isTrue();
        assertThat(spent.getValue().usedFromAddress()).isEqualTo(ADDRESS);
    }

    @Test
    void anExpiredTokenIsRefusedAndNothingIsWritten() {
        givenToken(new NodeEnrollmentToken(TOKEN_ID, NODE_ID, NodeSecret.hashOf(TOKEN_TEXT), null,
                Instant.now().minusSeconds(1), null, null, null, Instant.now(), 1L));

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.TOKEN_EXPIRED)
                .withMessageContaining("Issue a fresh one");

        verify(nodes, never()).save(any());
        verify(tokens, never()).save(any());
    }

    @Test
    void aTokenThatHasAlreadyBeenUsedIsRefusedAndNamesWhereItWasUsedFrom() {
        givenToken(liveToken().spent(Instant.parse("2026-01-02T03:04:05Z"), "203.0.113.44"));

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.TOKEN_SPENT)
                .withMessageContaining("203.0.113.44");

        verify(nodes, never()).save(any());
        verify(tokens, never()).save(any());
    }

    @Test
    void aTokenThisPanelNeverIssuedIsRefused() {
        given(tokens.findByTokenHash(any())).willReturn(Optional.empty());

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.TOKEN_UNKNOWN);

        verify(nodes, never()).save(any());
    }

    @Test
    void aRevokedTokenIsRefusedBeforeItsExpiryIsEvenConsidered() {
        givenToken(liveToken().revoked(Instant.now()));

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.TOKEN_REVOKED);
    }

    @Test
    void aProtocolThePanelDoesNotSpeakIsRefusedBeforeTheTokenIsEvenLookedUp() {
        EnrollRequest fromTheFuture = request().toBuilder()
                .setProtocolVersion(NodeProtocol.SUPPORTED + 1)
                .build();

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(fromTheFuture, ADDRESS, ""))
                .matches(refused ->
                        refused.reason() == EnrolmentRefused.Reason.PROTOCOL_UNSUPPORTED)
                .withMessageContaining("upgrade the panel");

        // Refusing here, before a credential exists, is the point: the alternative is a
        // machine that holds one and is turned away at every Connect for the rest of its
        // life (design §7.5).
        verify(tokens, never()).findByTokenHash(any());
        verify(nodes, never()).save(any());
    }

    @Test
    void aSignatureThatDoesNotMatchTheKeyIsRefused() {
        EnrolmentIdentity impostor = EnrolmentIdentity.generate();
        EnrollRequest forged = request().toBuilder()
                .setSignature(ByteString.copyFrom(impostor.sign(TOKEN_TEXT, FINGERPRINT)))
                .build();

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(forged, ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.PROOF_INVALID);

        verify(nodes, never()).save(any());
    }

    @Test
    void aRecordAMachineHasAlreadyEnrolledAgainstTakesNoSecond() {
        given(nodes.findById(NODE_ID)).willReturn(Optional.of(
                Node.created(NODE_ID, "node-a", "", "", List.of())
                        .enrolled(FINGERPRINT, "key", "hash", "panel:9090", Instant.now())));

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused ->
                        refused.reason() == EnrolmentRefused.Reason.NODE_ALREADY_ENROLLED);
    }

    @Test
    void aFailedRequiredPreflightCheckIsRefusedAndNamesTheCheck() {
        EnrollRequest unhealthy = request().toBuilder()
                .setDoctor(doctor(false))
                .build();

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(unhealthy, ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.DOCTOR_FAILED)
                .withMessageContaining("cgroups v2");

        verify(nodes, never()).save(any());
    }

    @Test
    void aFingerprintAnotherNodeAlreadyReportsStopsTheEnrolment() {
        // DetectClonedNode suspends both records and throws; this asserts EnrolNode lets
        // that decision through rather than issuing a credential anyway.
        willThrow(new EnrolmentRefused(
                        EnrolmentRefused.Reason.FINGERPRINT_TAKEN, "both suspended"))
                .given(clones).atEnrolment(eq(NODE_ID), eq(FINGERPRINT), any());

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(request(), ADDRESS, ""))
                .matches(refused ->
                        refused.reason() == EnrolmentRefused.Reason.FINGERPRINT_TAKEN);

        verify(nodes, never()).save(any());
        verify(tokens, never()).save(any());
    }

    @Test
    void aMalformedRequestIsRefusedWithoutTouchingTheDatabase() {
        EnrollRequest truncated = request().toBuilder()
                .setPublicKey(ByteString.copyFrom(new byte[16]))
                .build();

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> enrolNode.enrol(truncated, ADDRESS, ""))
                .matches(refused -> refused.reason() == EnrolmentRefused.Reason.MALFORMED);

        verify(tokens, never()).findByTokenHash(any());
    }

    private void givenToken(NodeEnrollmentToken token) {
        given(tokens.findByTokenHash(NodeSecret.hashOf(TOKEN_TEXT)))
                .willReturn(Optional.of(token));
    }

    private static NodeEnrollmentToken liveToken() {
        return NodeEnrollmentToken.issued(TOKEN_ID, NODE_ID, NodeSecret.hashOf(TOKEN_TEXT), null,
                Instant.now().plusSeconds(900));
    }

    private EnrollRequest request() {
        return EnrollRequest.newBuilder()
                .setBootstrapToken(TOKEN_TEXT)
                .setPublicKey(ByteString.copyFrom(identity.publicKey()))
                .setSignature(ByteString.copyFrom(identity.sign(TOKEN_TEXT, FINGERPRINT)))
                .setMachineFingerprint(FINGERPRINT)
                .setDoctor(doctor(true))
                .setAgentVersion("0.1.0")
                .setProtocolVersion(NodeProtocol.SUPPORTED)
                .setHostname("node-a.example")
                .addAdvertiseAddresses("203.0.113.9")
                .build();
    }

    private static DoctorReport doctor(boolean healthy) {
        return DoctorReport.newBuilder()
                .setRequiredChecksPassed(healthy)
                .setAgentVersion("0.1.0")
                .addChecks(DoctorCheck.newBuilder()
                        .setId("kernel.cgroups2")
                        .setTitle("cgroups v2")
                        .setSeverity(DoctorSeverity.DOCTOR_SEVERITY_REQUIRED)
                        .setOutcome(healthy ? DoctorOutcome.DOCTOR_OUTCOME_PASS
                                : DoctorOutcome.DOCTOR_OUTCOME_FAIL)
                        .setDetail(healthy ? "enabled" : "not mounted")
                        .setRemedy("boot with systemd.unified_cgroup_hierarchy=1"))
                .setMachine(MachineFacts.newBuilder()
                        .setHostname("node-a.example")
                        .setOperatingSystem("debian 13")
                        .setArchitecture("amd64")
                        .setCpuCores(4)
                        .setMemoryBytes(8L * 1024 * 1024 * 1024)
                        .setDiskTotalBytes(200L * 1024 * 1024 * 1024)
                        .setDiskFreeBytes(180L * 1024 * 1024 * 1024)
                        .setStateFilesystem("xfs")
                        .setProjectQuotaSupported(true)
                        .setCgroupsV2(healthy)
                        .setDockerVersion("27.1.1")
                        .setRunscAvailable(true)
                        .setClockSynchronised(true))
                .build();
    }
}
