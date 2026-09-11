package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a node go: every binding this service still has becomes history.
 *
 * <p>Published to {@code service} (panel-ports.md §4), which calls it when a service is
 * deleted. The rows are marked {@link PlacementState#RELEASED} rather than deleted, so the
 * audit trail can still answer "which machine was that running on in March" - a question
 * that is only ever asked after the row would have been thrown away.
 *
 * <p>It deliberately does <strong>not</strong> publish a spec. The caller asked which
 * nodes were hosting the service <em>before</em> calling this, precisely because the
 * answer is empty afterwards, and it publishes to that list once the row is gone. Doing it
 * here would publish to the nodes that are still listed and miss nothing, but it would also
 * hide the ordering that makes the caller correct, and a node nobody remembered to tell
 * keeps a container running forever.
 *
 * <p>Nothing here deletes a customer's bytes either. The node removes containers that have
 * left its spec; volume data survives until an explicit purge, because "cannot see it" is
 * not "does not exist" (AGENTS.md §4.5).
 */
@Component
public class ReleasePlacement {

    private final PlacementRepository placements;

    public ReleasePlacement(PlacementRepository placements) {
        this.placements = placements;
    }

    /**
     * Releases every live binding for a service.
     *
     * <p>Usually one row, two during a migration - the draining original and its
     * replacement - and none at all for a service that was never started, which is not an
     * error and does nothing.
     *
     * @param reason one sentence, kept on the row and read back on the placement history
     */
    @Transactional
    public void forService(UUID serviceId, String reason) {
        Instant now = Instant.now();
        String note = reason == null || reason.isBlank() ? "released" : reason;
        for (Placement placement : placements.findLiveFor(serviceId)) {
            placements.release(placement.id(), note, now);
        }
    }
}
