package lhqm.furimeo.wisper.org;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The caller is a member of this organization and is not allowed to do this.
 *
 * <p>Deliberately not a {@code NotFoundException}. The 404-for-everything rule in
 * {@code panel-http.md} exists so that an outsider cannot learn which ids are real; a
 * {@code VIEWER} being told "you cannot invite people" learns nothing they did not
 * already know, and hiding the organization from somebody who is standing inside it
 * produces a bug report rather than an understanding.
 *
 * <p>"Not a member at all" is still a 404, and that decision stays in
 * {@link ResolveMembership}.
 *
 * <p>Controllers catch this, write the message with
 * {@code InertiaFlash.failure} and record the attempt with
 * {@link lhqm.furimeo.wisper.audit.AuditOutcome#DENIED}. Uncaught, Spring turns it into
 * a 403 that renders the ordinary error page.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PermissionDenied extends RuntimeException {

    private final String action;
    private final MemberRole role;

    /**
     * @param action   the dotted audit action that was attempted, such as
     *                 {@code member.invite}
     * @param role     what the caller actually is
     * @param required a noun phrase naming what was needed, such as
     *                 {@code "an owner or an administrator"}
     */
    public PermissionDenied(String action, MemberRole role, String required) {
        super(action + " needs " + required + "; you are " + article(role) + " "
                + role.label().toLowerCase(Locale.ROOT) + " here.");
        this.action = action;
        this.role = role;
    }

    /** The dotted audit action that was refused. */
    public String action() {
        return action;
    }

    /** What the caller actually is, for the audit detail. */
    public MemberRole role() {
        return role;
    }

    private static String article(MemberRole role) {
        return role == MemberRole.OWNER || role == MemberRole.ADMIN ? "an" : "a";
    }
}
