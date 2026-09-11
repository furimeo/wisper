package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.org.RequestRejected;

/** Writing the binding, and refusing to write a second one. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceServiceTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    @Mock
    private PlacementRepository placements;

    @InjectMocks
    private PlaceService placeService;

    @BeforeEach
    void savesEcho() {
        given(placements.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void aNewBindingIsActiveFromTheMomentItIsWritten() {
        given(placements.findCurrentFor(SERVICE)).willReturn(Optional.empty());

        Placement placement = placeService.onNode(SERVICE, NODE, false, "scheduled for api");

        assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(placement.nodeId()).isEqualTo(NODE);
        assertThat(placement.pinned()).isFalse();
        assertThat(placement.reason()).isEqualTo("scheduled for api");
        // Spring Data JDBC writes every column on insert, so a null here would land on a
        // NOT NULL column instead of taking the DEFAULT now().
        assertThat(placement.placedAt()).isNotNull();
        assertThat(placement.releasedAt()).isNull();
    }

    @Test
    void storageMakesItPinnedAndThereforeUnmovableByTheScheduler() {
        given(placements.findCurrentFor(SERVICE)).willReturn(Optional.empty());

        Placement placement = placeService.onNode(SERVICE, NODE, true, "pinned by 1 volume(s)");

        assertThat(placement.pinned()).isTrue();
        assertThat(placement.isEvacuable()).isFalse();
    }

    @Test
    void aSecondBindingIsRefusedWithTheNodeItIsAlreadyOnInTheMessage() {
        given(placements.findCurrentFor(SERVICE)).willReturn(Optional.of(
                PlacementFixture.placement(SERVICE, NODE, PlacementState.ACTIVE, false)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> placeService.onNode(SERVICE, UUID.randomUUID(), false, "again"))
                .withMessageContaining(NODE.toString());
        verify(placements, never()).save(any());
    }

    @Test
    void aBlankReasonBecomesSomethingAnOperatorCanRead() {
        given(placements.findCurrentFor(SERVICE)).willReturn(Optional.empty());

        assertThat(placeService.onNode(SERVICE, NODE, false, "  ").reason())
                .isEqualTo("placed by the scheduler");
    }
}
