package lhqm.furimeo.wisper.project;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A grouping of services inside an organization: the unit a customer thinks in and the
 * unit the dashboard lists.
 *
 * <p>It carries no state of its own beyond a name and an archive flag. That is
 * deliberate - a project is a folder, not a deployable thing - and it is why every
 * screen below it is reached through {@code /projects/{id}/...} rather than through a
 * flat list keyed by a query parameter. The path is what gives the authorization check
 * something to check.
 *
 * <p>Archiving and deleting are different acts and both exist. Archiving takes a project
 * out of the dashboard and stops its services; the rows, the volumes and the customer's
 * bytes stay exactly where they were, and restoring is one click. Deleting cascades
 * through {@code service} and everything under it, and is why {@link DeleteProject}
 * insists the customer types the slug.
 *
 * @param id             generated in Java before the insert (schema.md §1)
 * @param organizationId the tenant; the only ownership edge, never denormalised further
 *                       down
 * @param name           what the customer calls it
 * @param slug           lower-case, unique within the organization, appears in URLs
 * @param description    free text shown on the overview; never null, empty by default
 * @param archivedAt     when it was archived, or null while it is live
 * @param version        null means new; Spring Data JDBC needs this to tell an insert of
 *                       an application-assigned id from an update
 */
public record Project(
        @Id UUID id,
        UUID organizationId,
        String name,
        String slug,
        String description,
        Instant archivedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public Project {
        description = description == null ? "" : description;
    }

    /** A brand new, live project. */
    public static Project opened(UUID id, UUID organizationId, String name, String slug,
                                 String description) {
        return new Project(id, organizationId, name, slug, description, null, null, null, null);
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * Renames it and rewrites the description in one step.
     *
     * <p>The slug does not follow the name. A URL a customer has bookmarked, pasted into
     * a ticket or wired into a webhook keeps working, which is worth more than a tidy
     * address bar.
     */
    public Project renamedTo(String newName, String newDescription) {
        return new Project(id, organizationId, newName, slug, newDescription, archivedAt,
                createdAt, updatedAt, version);
    }

    /** Takes it out of the dashboard. {@link ArchiveProject} stops the services too. */
    public Project archived(Instant at) {
        return new Project(id, organizationId, name, slug, description, at, createdAt, updatedAt,
                version);
    }

    /** Brings it back. Services stay stopped until somebody starts them. */
    public Project restored() {
        return new Project(id, organizationId, name, slug, description, null, createdAt,
                updatedAt, version);
    }
}
