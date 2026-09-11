package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Writes the binding between a service and the node that will hold it.
 *
 * <p>One row, one insert, no side effects. Choosing the machine is
 * {@link ReserveCapacity}, publishing the spec that follows is the caller's, and keeping
 * those three apart is what lets a migration reuse the middle one without re-running the
 * scheduler.
 *
 * <p>The row is born {@link PlacementState#ACTIVE} and, when the service has storage,
 * pinned. Pinning here is the moment design §7.8 describes: the bytes are on this machine
 * now, so the scheduler will never move the service off it, and a drain will list it for
 * an operator instead of relocating it quietly. A silent migration is a silent data loss.
 */
@Component
public class PlaceService {

    private final PlacementRepository placements;

    public PlaceService(PlacementRepository placements) {
        this.placements = placements;
    }

    /**
     * Binds a service to a node.
     *
     * <p>Refuses rather than inserting a second current binding. The partial unique index
     * {@code placement_one_active_per_service_idx} would refuse it too, a layer lower and
     * with a message nobody can read; catching it here names the node the service is
     * already on, which is the sentence an operator needs.
     *
     * @param pinned whether the service has storage on this node, which makes the binding
     *               permanent until somebody deliberately migrates it
     * @throws RequestRejected if the service already has a current binding
     */
    @Transactional
    public Placement onNode(UUID serviceId, UUID nodeId, boolean pinned, String reason) {
        Optional<Placement> current = placements.findCurrentFor(serviceId);
        if (current.isPresent()) {
            throw new RequestRejected(null, "That service is already placed on node "
                    + current.get().nodeId() + ". Release or migrate that binding first.");
        }
        Placement placement = Placement.active(UUID.randomUUID(), serviceId, nodeId, pinned,
                reason == null || reason.isBlank() ? "placed by the scheduler" : reason,
                Instant.now());
        return placements.save(placement);
    }
}
