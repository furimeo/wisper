package lhqm.furimeo.wisper.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.service.Service;

/**
 * Picks the machine a service goes on, and refuses when there is not one.
 *
 * <p>Three steps, in this order (design §7.8):
 *
 * <ol>
 * <li><strong>Filter</strong> to nodes that are enrolled, schedulable and carry every tag
 *     the service insists on. {@link LoadNodeCapacity} does that in SQL.</li>
 * <li><strong>Keep the headroom.</strong> A node is measured against its usable size, not
 *     its real one, so the last slice of every machine stays free. A full node does not
 *     degrade, it stops answering, and it takes every customer on it with it - refusing
 *     one deployment with a sentence is much the cheaper failure (design §7.6).</li>
 * <li><strong>Best fit.</strong> Of the nodes that still work, the tightest one wins. See
 *     {@link NodeCapacity#pressureWith} for why packing rather than spreading is right
 *     when a volume can pin a service to a machine forever.</li>
 * </ol>
 *
 * <p>This class only chooses; it writes nothing. {@link PlaceService} records the binding,
 * and keeping the two apart is what lets a migration ask "would this fit over there?"
 * without moving anything.
 */
@Component
public class ReserveCapacity {

    private final LoadNodeCapacity capacities;

    public ReserveCapacity(LoadNodeCapacity capacities) {
        this.capacities = capacities;
    }

    /**
     * The node that should take this service.
     *
     * @param demand   the ceilings it holds, not what it is expected to use
     * @param excluded nodes the caller has already ruled out - the machine being drained,
     *                 or the one a migration is moving away from
     * @throws NoNodeFits when the fleet cannot take it, with a message saying why
     */
    @Transactional(readOnly = true)
    public UUID bestFit(Service service, ResourceDemand demand, Collection<UUID> excluded) {
        List<String> requiredTags = tagsOf(service);
        List<NodeCapacity> candidates = eligible(requiredTags, excluded);
        if (candidates.isEmpty()) {
            throw NoNodeFits.noCandidates(service.name(), demand, requiredTags);
        }

        NodeCapacity chosen = null;
        double chosenPressure = Double.POSITIVE_INFINITY;
        List<String> shortfalls = new ArrayList<>();
        for (NodeCapacity candidate : candidates) {
            if (!candidate.fits(demand)) {
                shortfalls.add(candidate.shortfallAgainst(demand));
                continue;
            }
            double pressure = candidate.pressureWith(demand);
            if (chosen == null || isLeastLoaded(pressure, chosenPressure, candidate, chosen)) {
                chosen = candidate;
                chosenPressure = pressure;
            }
        }
        if (chosen == null) {
            throw NoNodeFits.allFull(service.name(), demand, requiredTags, shortfalls);
        }
        return chosen.nodeId();
    }

    /**
     * Insists that one named node has room, for a move an operator has already chosen.
     *
     * <p>A migration is deliberate: the operator picked the target, so the answer is "that
     * one will not take it" rather than a different machine chosen on their behalf.
     * Quietly substituting another node is how a service ends up somewhere nobody meant.
     *
     * @throws NoNodeFits if the node is not eligible, or is eligible and too full
     */
    @Transactional(readOnly = true)
    public void requireRoomOn(UUID nodeId, Service service, ResourceDemand demand) {
        List<String> requiredTags = tagsOf(service);
        NodeCapacity target = capacities.candidates(requiredTags).stream()
                .filter(candidate -> candidate.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> NoNodeFits.noCandidates(service.name(), demand, requiredTags));
        if (!target.fits(demand)) {
            throw NoNodeFits.allFull(service.name(), demand, requiredTags,
                    List.of(target.shortfallAgainst(demand)));
        }
    }

    /**
     * The measured candidates, for the placement screen and for a drain working out
     * whether it has anywhere to send what it is holding.
     */
    @Transactional(readOnly = true)
    public List<NodeCapacity> eligible(List<String> requiredTags, Collection<UUID> excluded) {
        Set<UUID> skip = excluded == null ? Set.of() : Set.copyOf(excluded);
        return capacities.candidates(requiredTags).stream()
                .filter(candidate -> !skip.contains(candidate.nodeId()))
                .toList();
    }

    /**
     * The placement filters a service insists on.
     *
     * <p>{@code service.required_tags} is {@code NOT NULL DEFAULT '{}'}, so the null check
     * is for a record built in a test rather than for a row.
     */
    public static List<String> tagsOf(Service service) {
        String[] tags = service.requiredTags();
        return tags == null ? List.of() : List.of(tags);
    }

    /**
     * Tighter, and deterministically so.
     *
     * <p>Two identical nodes produce identical pressure, and a scheduler that then keeps
     * whichever the database returned first gives a different answer on a replica. The
     * node id breaks the tie, so the same fleet and the same service always resolve to the
     * same machine.
     */
    private static boolean isLeastLoaded(double pressure, double incumbentPressure,
                                         NodeCapacity candidate, NodeCapacity incumbent) {
        int byPressure = Double.compare(pressure, incumbentPressure);
        if (byPressure != 0) {
            return byPressure < 0;
        }
        return candidate.nodeId().compareTo(incumbent.nodeId()) < 0;
    }
}
