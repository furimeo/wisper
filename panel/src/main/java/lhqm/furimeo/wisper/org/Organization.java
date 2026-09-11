package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * The tenant. Everything a customer owns hangs off exactly one of these, and the
 * authorization join {@code account -> member -> organization -> project -> service} is
 * the only place ownership is read from.
 *
 * <p>The state changes are expressed as methods returning a new record rather than as
 * setters somewhere else, so the {@code organization_suspension_consistent} CHECK -
 * {@code status = 'SUSPENDED'} and {@code suspended_at IS NOT NULL} are the same fact -
 * cannot be half-applied by a caller who set one field and forgot the other.
 *
 * @param id               generated in Java before the insert; see schema.md §1
 * @param name             what the customer calls it
 * @param slug             lower-case, globally unique, appears in URLs
 * @param planId           the plan whose limits apply, unless an override says otherwise
 * @param status           active or suspended
 * @param suspendedAt      set exactly when {@code status} is {@code SUSPENDED}
 * @param suspensionReason shown to the customer, so a locked-out tenant is not a mystery
 * @param version          null means new; Spring Data JDBC needs this to tell an insert
 *                         of an application-assigned id from an update
 */
public record Organization(
        @Id UUID id,
        String name,
        String slug,
        UUID planId,
        OrganizationStatus status,
        Instant suspendedAt,
        String suspensionReason,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** The shape a slug has to have, matching {@code organization_slug_shape}. */
    public static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,62}$";

    /** A brand new, active organization on the given plan. */
    public static Organization opened(UUID id, String name, String slug, UUID planId) {
        return new Organization(id, name, slug, planId, OrganizationStatus.ACTIVE,
                null, null, null, null, null);
    }

    public boolean isSuspended() {
        return status == OrganizationStatus.SUSPENDED;
    }

    /** Blocks every new write for this tenant. Running containers are untouched. */
    public Organization suspended(String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A suspension needs a reason the customer can read");
        }
        return new Organization(id, name, slug, planId, OrganizationStatus.SUSPENDED, at, reason,
                createdAt, updatedAt, version);
    }

    /** Lifts a suspension, clearing both halves of the fact at once. */
    public Organization resumed() {
        return new Organization(id, name, slug, planId, OrganizationStatus.ACTIVE, null, null,
                createdAt, updatedAt, version);
    }

    /** Moves the tenant to another plan; every limit is re-resolved from that moment. */
    public Organization onPlan(UUID newPlanId) {
        return new Organization(id, name, slug, newPlanId, status, suspendedAt, suspensionReason,
                createdAt, updatedAt, version);
    }

    /** Renames it. The slug does not follow: URLs that already exist keep working. */
    public Organization renamedTo(String newName) {
        return new Organization(id, newName, slug, planId, status, suspendedAt, suspensionReason,
                createdAt, updatedAt, version);
    }
}
