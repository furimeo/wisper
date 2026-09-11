package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A named bundle of limits an organization is placed on.
 *
 * <p>No price column: billing is out of scope (design §12), and a column nothing charges
 * against is a door into an empty room.
 *
 * <p>Archiving rather than deleting is the whole lifecycle. {@code organization.plan_id}
 * is {@code RESTRICT}, so a plan somebody is on cannot be removed; archiving takes it out
 * of the picker and leaves the organizations already on it exactly where they were.
 *
 * @param id          generated in Java before the insert
 * @param code        stable machine name used in URLs and seed data: {@code free}
 * @param name        what an operator sees in the picker
 * @param description one line explaining who it is for
 * @param isDefault   the plan a new organization gets when nobody picks one; at most one
 *                    row may have it, enforced by {@code plan_single_default_idx}
 * @param archivedAt  when it stopped being offered; null while it is selectable
 * @param version     null means new
 */
public record Plan(
        @Id UUID id,
        String code,
        String name,
        String description,
        boolean isDefault,
        Instant archivedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** Matches {@code plan_code_shape}. */
    public static final String CODE_PATTERN = "^[a-z0-9][a-z0-9-]*$";

    public boolean isSelectable() {
        return archivedAt == null;
    }
}
