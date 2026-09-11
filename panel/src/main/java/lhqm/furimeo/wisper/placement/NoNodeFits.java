package lhqm.furimeo.wisper.placement;

import java.util.List;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Nothing in the fleet can take this service.
 *
 * <p>A {@link RequestRejected} rather than an internal failure, because that is what it
 * is: the form was filled in correctly, the customer is entitled to press the button, and
 * the answer is still no. The controllers that start services and the deployment worker
 * both already catch that type, so this arrives on the page as a sentence under the start
 * button rather than as the error screen.
 *
 * <p>The message says which resource ran out and on how many machines it was tried,
 * because the two failures behind it need completely different actions: "no node carries
 * the tag {@code region=eu}" is a configuration mistake, and "all four nodes are full" is
 * a capacity decision. A single "placement failed" would send an operator looking in the
 * wrong place, which is the whole reason this class carries detail at all.
 */
public class NoNodeFits extends RequestRejected {

    private final ResourceDemand demand;
    private final List<String> requiredTags;
    private final int nodesConsidered;

    public NoNodeFits(String message, ResourceDemand demand, List<String> requiredTags,
                      int nodesConsidered) {
        super(null, message);
        this.demand = demand;
        this.requiredTags = List.copyOf(requiredTags);
        this.nodesConsidered = nodesConsidered;
    }

    /**
     * No machine even got as far as being measured: none is enrolled and schedulable, or
     * none carries the tags this service insists on.
     */
    public static NoNodeFits noCandidates(String subject, ResourceDemand demand,
                                          List<String> requiredTags) {
        String message = requiredTags.isEmpty()
                ? "There is no enrolled, schedulable node to put " + subject + " on."
                : "No enrolled, schedulable node carries all of " + String.join(", ", requiredTags)
                        + ", which " + subject + " requires.";
        return new NoNodeFits(message, demand, requiredTags, 0);
    }

    /**
     * Machines were measured and every one of them was too full.
     *
     * @param shortfalls one line per candidate, saying which resource ran out on it
     */
    public static NoNodeFits allFull(String subject, ResourceDemand demand,
                                     List<String> requiredTags, List<String> shortfalls) {
        String message = subject + " needs " + demand.describe() + ", and none of the "
                + shortfalls.size() + " eligible node(s) has that much free once the reserved "
                + "headroom is kept back: " + String.join("; ", shortfalls) + ".";
        return new NoNodeFits(message, demand, requiredTags, shortfalls.size());
    }

    /** What was being asked for, so a caller can log it next to what was available. */
    public ResourceDemand demand() {
        return demand;
    }

    /** The tags that narrowed the fleet, empty when nothing did. */
    public List<String> requiredTags() {
        return requiredTags;
    }

    /** How many machines were measured. Zero means the filter, not the capacity, refused. */
    public int nodesConsidered() {
        return nodesConsidered;
    }
}
