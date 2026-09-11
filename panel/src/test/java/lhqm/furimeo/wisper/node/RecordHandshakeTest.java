package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.simple.JdbcClient;

import lhqm.furimeo.wisper.proto.v1.DoctorReport;
import lhqm.furimeo.wisper.proto.v1.MachineFacts;
import lhqm.furimeo.wisper.proto.v1.NodeHello;

/**
 * The first frame decides whether the stream is worth keeping.
 *
 * <p>The version check is the one design §7.5 exists for: two versions quietly
 * misunderstanding each other is worse than no control channel, so there is no negotiating
 * down and the refusal still records what the agent said it was - otherwise the node's page
 * shows a machine that mysteriously never connects instead of one that needs upgrading.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordHandshakeTest {

    private final UUID nodeId = UUID.randomUUID();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JdbcClient jdbc;

    @Mock
    private NodeRepository nodes;

    @Mock
    private DetectClonedNode clones;

    @Mock
    private DetectGenerationDrift drift;

    @Mock
    private RecordMachineFacts machineFacts;

    @InjectMocks
    private RecordHandshake recordHandshake;

    @BeforeEach
    void anEnrolledNode() {
        given(nodes.findById(nodeId)).willReturn(Optional.of(enrolled()));
        given(clones.atHandshake(any(), any(), any())).willReturn(false);
    }

    @Test
    void aMatchingProtocolIsAcceptedAndTheMachineFactsAreRefreshed() {
        recordHandshake.accept(nodeId, hello(NodeProtocol.SUPPORTED, 47L, false),
                "198.51.100.7");

        // Refreshed on every reconnect, not only at enrolment: RAM gets added and runsc
        // gets removed by a distribution upgrade.
        verify(machineFacts).accept(eq(nodeId), any(MachineFacts.class), any(DoctorReport.class),
                any(Instant.class));
        // And nothing is published yet: the caller has not registered the stream, so a
        // spec sent here would be swallowed as undeliverable.
        verify(drift, never()).check(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void theCatchUpAfterTheStreamIsRegisteredSendsTheWholeSpec() {
        recordHandshake.catchUp(nodeId, hello(NodeProtocol.SUPPORTED, 47L, false));

        verify(drift).check(eq(nodeId), eq("node-a"), eq(47L), eq("reconnect"));
    }

    @Test
    void aFreshStartIsDistinguishedFromAReconnectInTheReasonItPublishesUnder() {
        recordHandshake.catchUp(nodeId, hello(NodeProtocol.SUPPORTED, 0L, true));

        verify(drift).check(eq(nodeId), eq("node-a"), eq(0L), eq("daemon started"));
    }

    @Test
    void aProtocolThePanelCannotSpeakEndsTheStreamAndNothingIsDispatchedOnIt() {
        assertThatExceptionOfType(HandshakeRefused.class)
                .isThrownBy(() -> recordHandshake.accept(nodeId,
                        hello(NodeProtocol.SUPPORTED + 1, 47L, false), "198.51.100.7"))
                .matches(refused ->
                        refused.reason() == HandshakeRefused.Reason.PROTOCOL_UNSUPPORTED)
                .withMessageContaining("upgrade the panel");

        verifyNoInteractions(machineFacts, drift);
    }

    @Test
    void anOlderProtocolIsRefusedWithAMessageThatSaysWhichEndToUpgrade() {
        assertThatExceptionOfType(HandshakeRefused.class)
                .isThrownBy(() -> recordHandshake.accept(nodeId, hello(0, 47L, false),
                        "198.51.100.7"))
                .withMessageContaining("The node needs upgrading");
    }

    @Test
    void aSuspendedNodeIsRefusedAndToldItsContainersAreUnaffected() {
        given(nodes.findById(nodeId)).willReturn(Optional.of(
                enrolled().suspended(NodeSuspensionReason.OPERATOR, Instant.now())));

        assertThatExceptionOfType(HandshakeRefused.class)
                .isThrownBy(() -> recordHandshake.accept(nodeId,
                        hello(NodeProtocol.SUPPORTED, 47L, false), "198.51.100.7"))
                .matches(refused ->
                        refused.reason() == HandshakeRefused.Reason.NOT_ACCEPTING_CONTROL)
                .withMessageContaining("containers are unaffected");

        verifyNoInteractions(machineFacts);
    }

    @Test
    void aSecondStreamFromAnotherAddressIsRefusedBeforeTheStreamIsUsable() {
        given(clones.atHandshake(eq(nodeId), eq("node-a"), any())).willReturn(true);

        assertThatExceptionOfType(HandshakeRefused.class)
                .isThrownBy(() -> recordHandshake.accept(nodeId,
                        hello(NodeProtocol.SUPPORTED, 47L, false), "203.0.113.44"))
                .matches(refused -> refused.reason() == HandshakeRefused.Reason.DUPLICATE_STREAM);

        verifyNoInteractions(machineFacts);
    }

    private Node enrolled() {
        return Node.created(nodeId, "node-a", "", "203.0.113.9", List.of())
                .enrolled("fingerprint", "key", "hash", "panel:9090", Instant.now());
    }

    private static NodeHello hello(int protocolVersion, long appliedGeneration,
                                   boolean freshStart) {
        return NodeHello.newBuilder()
                .setProtocolVersion(protocolVersion)
                .setAgentVersion("0.1.0")
                .setAgentCommit("abc1234")
                .setAppliedGeneration(appliedGeneration)
                .setFreshStart(freshStart)
                .setMachine(MachineFacts.newBuilder().setHostname("node-a.example")
                        .setArchitecture("amd64").setCpuCores(4).setRunscAvailable(true))
                .setDoctor(DoctorReport.newBuilder().setRequiredChecksPassed(true))
                .build();
    }
}
