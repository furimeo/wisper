package lhqm.furimeo.wisper.placement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Letting go of a node, including the pair of rows a half-finished move leaves behind. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReleasePlacementTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_B = UUID.randomUUID();

    @Mock
    private PlacementRepository placements;

    @InjectMocks
    private ReleasePlacement release;

    @Test
    void everyLiveBindingIsReleasedIncludingOneThatWasStillDraining() {
        Placement active = PlacementFixture.placement(SERVICE, NODE_B, PlacementState.ACTIVE,
                false);
        Placement draining = PlacementFixture.placement(SERVICE, NODE_A, PlacementState.DRAINING,
                false);
        given(placements.findLiveFor(SERVICE)).willReturn(List.of(active, draining));

        release.forService(SERVICE, "service api deleted");

        verify(placements).release(eq(active.id()), eq("service api deleted"),
                any(Instant.class));
        verify(placements).release(eq(draining.id()), eq("service api deleted"),
                any(Instant.class));
        verify(placements, times(2)).release(any(), anyString(), any(Instant.class));
    }

    @Test
    void aServiceThatWasNeverPlacedIsNotAnError() {
        given(placements.findLiveFor(SERVICE)).willReturn(List.of());

        release.forService(SERVICE, "service api deleted");

        verify(placements, never()).release(any(), anyString(), any(Instant.class));
    }

    @Test
    void aBlankReasonStillLeavesSomethingOnTheRow() {
        Placement active = PlacementFixture.placement(SERVICE, NODE_A, PlacementState.ACTIVE,
                false);
        given(placements.findLiveFor(SERVICE)).willReturn(List.of(active));

        release.forService(SERVICE, "   ");

        verify(placements).release(eq(active.id()), eq("released"), any(Instant.class));
    }
}
