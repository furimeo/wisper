package lhqm.furimeo.wisper.audit;

import java.util.List;

/**
 * One page of the trail, plus enough to draw the pager.
 *
 * <p>{@code total} is a real {@code count(*)} over the same predicates, not an estimate.
 * The audit log is the screen somebody opens when they need to know whether something
 * happened at all, and "showing 50 of about 4,000" is a worse answer than the truth on a
 * table that has an index for every filter it offers.
 *
 * @param entries  the rows, newest first
 * @param total    how many rows match the filter in full
 * @param offset   how many were skipped to get here
 * @param pageSize how many were asked for, which is what "there is another page" is
 *                 computed from
 */
public record AuditLogPage(List<AuditLogEntry> entries, long total, int offset, int pageSize) {

    public AuditLogPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Whether a "newer" link should be offered. */
    public boolean hasPrevious() {
        return offset > 0;
    }

    /** Whether an "older" link should be offered. */
    public boolean hasNext() {
        return offset + entries.size() < total;
    }

    /** One-based index of the first row shown, or zero when nothing matched. */
    public int firstRow() {
        return entries.isEmpty() ? 0 : offset + 1;
    }

    /** One-based index of the last row shown. */
    public int lastRow() {
        return offset + entries.size();
    }
}
