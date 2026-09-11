package lhqm.furimeo.wisper.deploy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static lhqm.furimeo.wisper.deploy.DeploymentStatus.ASSIGNED;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.BUILDING;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.CANCELLED;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.FAILED;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.PUBLISHING;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.QUEUED;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.SUCCEEDED;
import static lhqm.furimeo.wisper.deploy.DeploymentStatus.SUPERSEDED;

/**
 * The state machine, asserted as a whole rather than one happy path at a time.
 *
 * <p>The table below is the specification: for every status, exactly which statuses may
 * follow it. The test then walks all sixty-four pairs and checks both directions of the
 * answer, so a transition quietly added to the enum fails here until it is added to the
 * table too - which is the only way a rule like this stays true after six months of
 * features.
 */
class DeploymentStatusTest {

    /** Every legal move. Anything not in here must be rejected. */
    private static final Map<DeploymentStatus, Set<DeploymentStatus>> ALLOWED = Map.of(
            QUEUED, EnumSet.of(ASSIGNED, CANCELLED, SUPERSEDED, FAILED),
            ASSIGNED, EnumSet.of(BUILDING, PUBLISHING, CANCELLED, FAILED),
            BUILDING, EnumSet.of(PUBLISHING, CANCELLED, FAILED),
            PUBLISHING, EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, EnumSet.noneOf(DeploymentStatus.class),
            FAILED, EnumSet.noneOf(DeploymentStatus.class),
            CANCELLED, EnumSet.noneOf(DeploymentStatus.class),
            SUPERSEDED, EnumSet.noneOf(DeploymentStatus.class));

    @Test
    @DisplayName("every pair of statuses answers exactly what the table says")
    void theWholeMatrix() {
        for (DeploymentStatus from : DeploymentStatus.values()) {
            for (DeploymentStatus to : DeploymentStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("an illegal transition throws rather than being ignored")
    void illegalTransitionsThrow() {
        assertThatThrownBy(() -> SUCCEEDED.transitionTo(BUILDING))
                .isInstanceOf(IllegalStatusTransition.class)
                .hasMessageContaining("SUCCEEDED")
                .hasMessageContaining("BUILDING");

        assertThatThrownBy(() -> QUEUED.transitionTo(SUCCEEDED))
                .isInstanceOf(IllegalStatusTransition.class);
        assertThatThrownBy(() -> QUEUED.transitionTo(PUBLISHING))
                .isInstanceOf(IllegalStatusTransition.class);
        assertThatThrownBy(() -> PUBLISHING.transitionTo(CANCELLED))
                .isInstanceOf(IllegalStatusTransition.class);
    }

    @Test
    @DisplayName("a legal transition returns the target")
    void legalTransitionsPassThrough() {
        assertThat(QUEUED.transitionTo(ASSIGNED)).isEqualTo(ASSIGNED);
        assertThat(ASSIGNED.transitionTo(BUILDING)).isEqualTo(BUILDING);
        assertThat(BUILDING.transitionTo(PUBLISHING)).isEqualTo(PUBLISHING);
        assertThat(PUBLISHING.transitionTo(SUCCEEDED)).isEqualTo(SUCCEEDED);
    }

    @Test
    @DisplayName("a status cannot transition to itself")
    void noSelfTransition() {
        for (DeploymentStatus status : DeploymentStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> itself", status).isFalse();
        }
    }

    @Test
    @DisplayName("a null target is refused, not treated as no-op")
    void nullIsNotATarget() {
        assertThat(QUEUED.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("the four terminal statuses are the ones with nowhere to go")
    void terminalMeansNoWayOut() {
        for (DeploymentStatus status : DeploymentStatus.values()) {
            boolean hasSomewhereToGo = EnumSet.allOf(DeploymentStatus.class).stream()
                    .anyMatch(status::canTransitionTo);
            assertThat(status.isTerminal()).as("%s is terminal", status)
                    .isEqualTo(!hasSomewhereToGo);
            assertThat(status.isInFlight()).isEqualTo(!status.isTerminal());
        }
        assertThat(EnumSet.allOf(DeploymentStatus.class).stream()
                .filter(DeploymentStatus::isTerminal))
                .containsExactlyInAnyOrder(SUCCEEDED, FAILED, CANCELLED, SUPERSEDED);
    }

    @Test
    @DisplayName("isInFlight matches the deployment_pending_idx predicate exactly")
    void inFlightMatchesTheIndex() {
        // The partial index the worker's queue reads is
        //   WHERE status IN ('QUEUED','ASSIGNED','BUILDING','PUBLISHING')
        // and a Java predicate that drifts from it means a queue that misses rows.
        assertThat(EnumSet.allOf(DeploymentStatus.class).stream()
                .filter(DeploymentStatus::isInFlight))
                .containsExactlyInAnyOrder(QUEUED, ASSIGNED, BUILDING, PUBLISHING);
    }

    @Test
    @DisplayName("publishing cannot be cancelled; everything before it can")
    void cancellability() {
        assertThat(QUEUED.isCancellable()).isTrue();
        assertThat(ASSIGNED.isCancellable()).isTrue();
        assertThat(BUILDING.isCancellable()).isTrue();
        // The spec is already out and the node is converging to it. Reporting a
        // cancellation would be a claim the panel cannot make good on.
        assertThat(PUBLISHING.isCancellable()).isFalse();
        assertThat(SUCCEEDED.isCancellable()).isFalse();
        assertThat(FAILED.isCancellable()).isFalse();
    }

    @Test
    @DisplayName("every status has a label a person can read")
    void everyStatusIsPresentable() {
        for (DeploymentStatus status : DeploymentStatus.values()) {
            assertThat(status.label()).isNotBlank().doesNotContain("_");
        }
    }
}
