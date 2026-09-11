package lhqm.furimeo.wisper.placement;

import java.util.List;
import java.util.UUID;

/**
 * What a drain did, or - in survey mode - what it would do.
 *
 * <p>The three lists are the three answers design §7.7 insists a drain gives, and they are
 * separate because they need three different reactions from whoever asked:
 *
 * <ul>
 * <li>{@link #evacuated} moved. Nothing more to do.</li>
 * <li>{@link #pinned} has storage on this machine and was left alone. An operator decides
 *     what happens to it, one service at a time, through {@link MigrateService}. It is
 *     never moved automatically: the bytes would not come with it.</li>
 * <li>{@link #stranded} could have moved and had nowhere to go. That is a capacity problem
 *     in the rest of the fleet, and it is the one an operator has to fix before the node
 *     can be emptied.</li>
 * </ul>
 *
 * <p>Every list holds service ids, which are also the workload ids the node knows those
 * services by, so the same values line up with {@code DrainReport} on the wire.
 *
 * @param complete nothing evacuable is left. Pinned services do not count against it -
 *                 they are not evacuable - so a node holding only pinned workloads reports
 *                 complete, and it is {@link #pinned} that tells an operator the machine
 *                 still cannot be deleted.
 */
public record DrainOutcome(
        UUID nodeId,
        List<UUID> evacuated,
        List<UUID> pinned,
        List<UUID> stranded,
        boolean surveyOnly,
        boolean complete) {

    public DrainOutcome {
        evacuated = List.copyOf(evacuated);
        pinned = List.copyOf(pinned);
        stranded = List.copyOf(stranded);
    }

    /** How many services the node is still holding after this pass. */
    public int remaining() {
        return pinned.size() + stranded.size();
    }

    /** One sentence for an audit entry and for the flash message above the node's page. */
    public String describe() {
        String prefix = surveyOnly ? "Survey: " : "";
        if (complete && remaining() == 0) {
            return prefix + "the node is empty.";
        }
        return prefix + evacuated.size() + " service(s) " + (surveyOnly ? "would move" : "moved")
                + ", " + pinned.size() + " pinned by storage and left in place, "
                + stranded.size() + " with nowhere to go.";
    }
}
