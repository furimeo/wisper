package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The state changes on {@link Node} exist as methods rather than as setters somewhere else
 * so the pairs the database enforces cannot be half-applied. These are those pairs.
 */
class NodeTest {

    private static final Instant AT = Instant.parse("2026-02-01T10:00:00Z");

    private final Node created = Node.created(UUID.randomUUID(), "node-a", "The first one",
            "203.0.113.9", List.of("region=eu", "ssd"));

    @Test
    void aFreshRecordHasNoIdentityAndCannotBeScheduledOnto() {
        assertThat(created.isEnrolled()).isFalse();
        assertThat(created.fingerprint()).isNull();
        assertThat(created.credentialHash()).isNull();
        assertThat(created.desiredGeneration()).isZero();
        // schedulable is true, but the lifecycle is not ENROLLED - and both have to be.
        assertThat(created.schedulable()).isTrue();
        assertThat(created.isPlaceable()).isFalse();
        assertThat(created.tagList()).containsExactly("region=eu", "ssd");
    }

    @Test
    void enrollingSetsAllThreeHalvesOfTheIdentityAtOnce() {
        Node enrolled = created.enrolled("fingerprint", "key", "hash", "panel:9090", AT);

        // node_enrolled_identity_complete: a node past CREATED holds all three, or it
        // could not be checked for cloning.
        assertThat(enrolled.lifecycle()).isEqualTo(NodeLifecycle.ENROLLED);
        assertThat(enrolled.fingerprint()).isEqualTo("fingerprint");
        assertThat(enrolled.publicKey()).isEqualTo("key");
        assertThat(enrolled.credentialHash()).isEqualTo("hash");
        assertThat(enrolled.credentialIssuedAt()).isEqualTo(AT);
        assertThat(enrolled.isPlaceable()).isTrue();
    }

    @Test
    void suspendingSetsBothHalvesOfTheSuspensionAndStopsScheduling() {
        Node suspended = created.enrolled("f", "k", "h", "panel:9090", AT)
                .suspended(NodeSuspensionReason.DUPLICATE_FINGERPRINT, AT);

        // node_suspension_consistent: lifecycle = SUSPENDED and suspended_at IS NOT NULL
        // are the same fact.
        assertThat(suspended.lifecycle()).isEqualTo(NodeLifecycle.SUSPENDED);
        assertThat(suspended.suspendedAt()).isEqualTo(AT);
        assertThat(suspended.isSuspended()).isTrue();
        assertThat(suspended.schedulable()).isFalse();
        assertThat(suspended.isPlaceable()).isFalse();
    }

    @Test
    void resumingClearsBothHalvesAndPutsTheNodeBackInThePool() {
        Node resumed = created.enrolled("f", "k", "h", "panel:9090", AT)
                .suspended(NodeSuspensionReason.OPERATOR, AT)
                .resumed();

        assertThat(resumed.lifecycle()).isEqualTo(NodeLifecycle.ENROLLED);
        assertThat(resumed.suspendedAt()).isNull();
        assertThat(resumed.suspensionReason()).isNull();
        assertThat(resumed.isPlaceable()).isTrue();
    }

    @Test
    void drainingStopsNewPlacementsWhileTheControlChannelStaysOpen() {
        Node draining = created.enrolled("f", "k", "h", "panel:9090", AT).draining(AT);

        assertThat(draining.lifecycle()).isEqualTo(NodeLifecycle.DRAINING);
        assertThat(draining.drainRequestedAt()).isEqualTo(AT);
        assertThat(draining.isPlaceable()).isFalse();
        // Still publishable: a draining node has to be told what to stop running.
        assertThat(draining.lifecycle().acceptsControl()).isTrue();

        assertThat(draining.drained().lifecycle()).isEqualTo(NodeLifecycle.DRAINED);
    }

    @Test
    void aSuspendedNodeIsNotTalkedToAndACreatedOneCannotBe() {
        assertThat(NodeLifecycle.SUSPENDED.acceptsControl()).isFalse();
        assertThat(NodeLifecycle.RETIRED.acceptsControl()).isFalse();
        assertThat(NodeLifecycle.CREATED.acceptsControl()).isFalse();
        assertThat(NodeLifecycle.CREATED.acceptsEnrolment()).isTrue();
        assertThat(NodeLifecycle.ENROLLED.acceptsEnrolment()).isFalse();
    }

    @Test
    void theGenerationOnlyEverGoesUp() {
        Node published = created.publishedGeneration(1, AT);

        assertThat(published.desiredGeneration()).isEqualTo(1);
        assertThat(published.specUpdatedAt()).isEqualTo(AT);

        // A counter that can go backwards is a node that can be rolled back to a spec it
        // has already moved past, which is the one thing it exists to prevent.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> published.publishedGeneration(1, AT));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> published.publishedGeneration(0, AT));
    }

    @Test
    void editingTheDetailsLeavesTheNameAndTheIdentityAlone() {
        Node enrolled = created.enrolled("f", "k", "h", "panel:9090", AT);

        Node edited = enrolled.withDetails("Now the second one", "203.0.113.10",
                List.of("region=us"), false);

        assertThat(edited.name()).isEqualTo("node-a");
        assertThat(edited.fingerprint()).isEqualTo("f");
        assertThat(edited.description()).isEqualTo("Now the second one");
        assertThat(edited.publicAddress()).isEqualTo("203.0.113.10");
        assertThat(edited.tagList()).containsExactly("region=us");
        assertThat(edited.schedulable()).isFalse();
    }

    @Test
    void printingANodeDoesNotPrintItsCredentialHash() {
        Node enrolled = created.enrolled("f", "k", "a-credential-hash", "panel:9090", AT);

        assertThat(enrolled.toString()).doesNotContain("a-credential-hash");
    }

    @Test
    void twoNodesWithTheSameTagsAreEqualDespiteTheArrayColumn() {
        Node one = Node.created(created.id(), "node-a", "The first one", "203.0.113.9",
                List.of("region=eu", "ssd"));

        // The generated equals on a record with an array component compares by identity,
        // which turns an innocent assertion into a false negative.
        assertThat(one).isEqualTo(created);
    }
}
