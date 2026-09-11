package lhqm.furimeo.wisper.project;

import java.time.Instant;
import java.util.UUID;

/**
 * One card on the dashboard: the project, the tenant it belongs to, and the two numbers a
 * customer looks for before deciding whether to open it.
 *
 * <p>A shape of its own rather than {@link Project} with the counts bolted on. The counts
 * are not on the row and never will be - usage is measured, never accumulated - so a
 * record that pretends otherwise would be a column somebody eventually tries to update.
 *
 * <p>{@code organizationName} travels with the card because the dashboard is the one
 * screen that crosses tenants: a contractor in four organizations sees all four here, and
 * a list of eleven projects with no owner against them is a list they have to guess at.
 *
 * @param id               the project
 * @param organizationId   the tenant it belongs to
 * @param organizationName that tenant's name, for the group heading
 * @param name             what the project is called
 * @param slug             its URL fragment
 * @param description      the one-liner under the title, empty when there is none
 * @param archivedAt       null while it is live; a date puts the card in the archived
 *                         section with a restore button rather than hiding it entirely
 * @param createdAt        when it was opened
 * @param serviceCount     services in it that are not archived
 * @param runningCount     how many of those the customer has asked to be running. Intent,
 *                         not fact: what a node actually reports belongs on the service
 *                         page, where there is room to say why the two differ.
 */
public record ProjectSummary(
        UUID id,
        UUID organizationId,
        String organizationName,
        String name,
        String slug,
        String description,
        Instant archivedAt,
        Instant createdAt,
        long serviceCount,
        long runningCount) {

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** True for a project with nothing in it, which the card offers to fill. */
    public boolean isEmpty() {
        return serviceCount == 0;
    }
}
