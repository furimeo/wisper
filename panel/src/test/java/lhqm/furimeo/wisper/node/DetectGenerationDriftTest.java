package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
 * The three cases of {@code applied_generation} against {@code desired_generation}, and
 * the fact that the third one is not a bug in the node (schema.md §2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetectGenerationDriftTest {

    private final UUID nodeId = UUID.randomUUID();

    @Mock
    private NodeRepository nodes;

    @Mock
    private SuspendNode suspendNode;

    @Mock
    private PublishNodeSpec publishNodeSpec;

    @InjectMocks
    private DetectGenerationDrift drift;

    @Test
    void convergedIsTheCommonCaseAndDoesNothingAtAll() {
        given(nodes.desiredGenerationOf(nodeId)).willReturn(Optional.of(47L));

        assertThat(drift.check(nodeId, "node-a", 47L, "heartbeat")).isFalse();

        verifyNoInteractions(publishNodeSpec, suspendNode);
    }

    @Test
    void aNodeThatIsBehindIsSentTheWholeSpecAgain() {
        given(nodes.desiredGenerationOf(nodeId)).willReturn(Optional.of(47L));

        assertThat(drift.check(nodeId, "node-a", 46L, "reconnect")).isFalse();

        // The whole spec, never a delta: that is what makes a week-long outage need no
        // catch-up logic (design §5.2).
        verify(publishNodeSpec).toNode(nodeId, "reconnect");
        verifyNoInteractions(suspendNode);
    }

    @Test
    void aNodeThatHasNeverAppliedAnythingIsBehindRatherThanBroken() {
        given(nodes.desiredGenerationOf(nodeId)).willReturn(Optional.of(0L));

        assertThat(drift.check(nodeId, "node-a", NodeStatus.NEVER_REPORTED, "first connect"))
                .isFalse();

        verify(publishNodeSpec).toNode(nodeId, "first connect");
    }

    @Test
    void aGenerationThisPanelNeverPublishedMeansTwoPanelsAndSuspendsTheNode() {
        given(nodes.desiredGenerationOf(nodeId)).willReturn(Optional.of(47L));

        assertThat(drift.check(nodeId, "node-a", 48L, "status")).isTrue();

        verify(suspendNode).suspend(any(), eq(nodeId),
                eq(NodeSuspensionReason.DUPLICATE_FINGERPRINT), any());
        // And crucially: no republish. Publishing again would make this panel the second
        // author of a state that already has two.
        verifyNoInteractions(publishNodeSpec);
    }
}
