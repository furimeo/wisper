package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Cloning a running virtual machine produces two machines holding one credential, and the
 * only wrong answer is to carry on (design §7.3).
 *
 * <p>The rule these tests pin down is that <strong>both</strong> sides are suspended. The
 * panel cannot tell which copy is the original - a hardware serial says nothing about
 * which machine was imaged from which - so choosing one to keep would be a coin toss whose
 * losing side is a customer's data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectClonedNodeTest {

    private static final String FINGERPRINT =
            "3d0f7a1b5c2e9d8476a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f607";

    private final UUID original = UUID.randomUUID();
    private final UUID clone = UUID.randomUUID();

    @Mock
    private NodeRepository nodes;

    @Mock
    private NodeStatusRepository statuses;

    @Mock
    private SuspendNode suspendNode;

    @InjectMocks
    private DetectClonedNode detectClonedNode;

    @Test
    void aFingerprintAnotherNodeHoldsSuspendsBothRecordsAndRefusesTheEnrolment() {
        given(nodes.findByFingerprint(FINGERPRINT)).willReturn(Optional.of(
                Node.created(original, "node-a", "", "", List.of())
                        .enrolled(FINGERPRINT, "key", "hash", "panel:9090",
                                java.time.Instant.now())));

        assertThatExceptionOfType(EnrolmentRefused.class)
                .isThrownBy(() -> detectClonedNode.atEnrolment(clone, FINGERPRINT,
                        "198.51.100.7"))
                .matches(refused ->
                        refused.reason() == EnrolmentRefused.Reason.FINGERPRINT_TAKEN);

        verify(suspendNode).suspend(any(), eq(original),
                eq(NodeSuspensionReason.DUPLICATE_FINGERPRINT), any());
        verify(suspendNode).suspend(any(), eq(clone),
                eq(NodeSuspensionReason.DUPLICATE_FINGERPRINT), any());
    }

    @Test
    void aFingerprintNobodyHoldsIsTheOrdinaryCaseAndChangesNothing() {
        given(nodes.findByFingerprint(FINGERPRINT)).willReturn(Optional.empty());

        detectClonedNode.atEnrolment(clone, FINGERPRINT, "198.51.100.7");

        verifyNoInteractions(suspendNode);
    }

    @Test
    void reEnrollingTheSameRecordWithItsOwnFingerprintIsNotAClone() {
        given(nodes.findByFingerprint(FINGERPRINT)).willReturn(Optional.of(
                Node.created(original, "node-a", "", "", List.of())
                        .enrolled(FINGERPRINT, "key", "hash", "panel:9090",
                                java.time.Instant.now())));

        detectClonedNode.atEnrolment(original, FINGERPRINT, "198.51.100.7");

        verifyNoInteractions(suspendNode);
    }

    @Test
    void aSecondStreamFromAnotherAddressWhileOneIsOpenSuspendsTheNode() {
        given(statuses.findRow(original)).willReturn(Optional.of(
                NodeStatusFixture.connected(original, "198.51.100.7", 7L)));

        boolean cloned = detectClonedNode.atHandshake(original, "node-a", "203.0.113.44");

        assertThat(cloned).isTrue();
        verify(suspendNode).suspend(any(), eq(original),
                eq(NodeSuspensionReason.DUPLICATE_FINGERPRINT), any());
    }

    @Test
    void reconnectingFromTheSameAddressIsATunnelDroppingAStreamAndNotAClone() {
        given(statuses.findRow(original)).willReturn(Optional.of(
                NodeStatusFixture.connected(original, "198.51.100.7", 7L)));

        boolean cloned = detectClonedNode.atHandshake(original, "node-a", "198.51.100.7");

        assertThat(cloned).isFalse();
        verify(suspendNode, never()).suspend(any(), any(), any(), any());
    }

    @Test
    void connectingWhileThePanelBelievesNothingIsOpenIsNormal() {
        given(statuses.findRow(original)).willReturn(Optional.of(
                NodeStatusFixture.disconnected(original, java.time.Instant.now())));

        assertThat(detectClonedNode.atHandshake(original, "node-a", "203.0.113.44")).isFalse();
        verifyNoInteractions(suspendNode);
    }

    @Test
    void aNodeThatHasNeverReportedHasNoStreamToCollideWith() {
        given(statuses.findRow(original)).willReturn(Optional.empty());

        assertThat(detectClonedNode.atHandshake(original, "node-a", "203.0.113.44")).isFalse();
        verifyNoInteractions(suspendNode);
    }
}
