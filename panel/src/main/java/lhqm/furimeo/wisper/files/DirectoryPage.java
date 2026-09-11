package lhqm.furimeo.wisper.files;

import java.util.List;

import lhqm.furimeo.wisper.proto.v1.DirectoryListing;

/**
 * One page of a directory, as the browser receives it.
 *
 * <p>Paged because a customer with a {@code node_modules} has a hundred thousand entries
 * and a phone sent all of them renders nothing at all. The node returns them directories
 * first and then by name, which is what makes the cursor stable between pages: a file
 * created while the customer is scrolling shifts nothing they have already seen.
 *
 * @param nextCursor opaque; empty when this was the last page
 * @param total      how many entries the directory has in full, so the list can say
 *                   "showing 200 of 40,312" rather than implying it is complete
 */
public record DirectoryPage(String path, List<FileEntryView> entries, String nextCursor,
                            int total) {

    public DirectoryPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static DirectoryPage of(DirectoryListing listing) {
        List<FileEntryView> entries = listing.getEntriesList().stream()
                .map(FileEntryView::of)
                .toList();
        return new DirectoryPage(listing.getPath(), entries, listing.getNextCursor(),
                listing.getTotalEntries());
    }

    /** Whether there is another page to fetch. */
    public boolean hasMore() {
        return nextCursor != null && !nextCursor.isEmpty();
    }

    /**
     * Nothing to show.
     *
     * <p>Used when the service is not on a node yet: the page renders the reason instead of
     * a spinner that never resolves, which is the difference between "not started" and
     * "broken" as a customer reads it.
     */
    public static DirectoryPage empty(String path) {
        return new DirectoryPage(path, List.of(), "", 0);
    }
}
