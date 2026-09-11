package lhqm.furimeo.wisper.org;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Moves one member to a different role.
 *
 * <p>Three rules, and each of them closes a hole rather than expressing a preference:
 *
 * <ol>
 * <li>Only an owner may create an owner or change one. An administrator who could do
 *     either would be an owner in all but name.</li>
 * <li>The last owner cannot be demoted. An organization with no owner is one nobody can
 *     ever administer again, and no screen in this panel can repair it.</li>
 * <li>An outstanding invitation can be re-roled, because correcting a mistake before
 *     somebody accepts is cheaper than removing and re-inviting them.</li>
 * </ol>
 */
@Component
public class ChangeMemberRole {

    private static final String ACTION = "member.role_change";

    private final MemberRepository members;
    private final FindAccount accounts;
    private final AuditTrail audit;

    public ChangeMemberRole(MemberRepository members, FindAccount accounts, AuditTrail audit) {
        this.members = members;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * @param actor    who is making the change, for the trail
     * @param caller   the changer's membership, which fixes the organization
     * @param memberId the row being changed
     * @param newRole  what they become
     * @throws PermissionDenied  if the caller may not administer, or is reaching past the
     *                           owner boundary
     * @throws NotFoundException if that member is not in the caller's organization
     * @throws RequestRejected   if it would leave no owner, or nothing would change
     */
    @Transactional
    public Member change(AuditActor actor, Membership caller, UUID memberId, MemberRole newRole) {
        caller.requireAdministration(ACTION);

        Member member = members.findById(memberId)
                .filter(candidate -> candidate.organizationId().equals(caller.organizationId()))
                .orElseThrow(() -> NotFoundException.of("member", memberId));

        if (member.role() == newRole) {
            throw new RequestRejected("role", "That member is already " + article(newRole) + " "
                    + newRole.label().toLowerCase(Locale.ROOT) + ".");
        }
        if ((member.role() == MemberRole.OWNER || newRole == MemberRole.OWNER)
                && !caller.isOwner()) {
            throw new PermissionDenied(ACTION, caller.role(), "an owner, to change an owner");
        }
        if (member.role() == MemberRole.OWNER && member.isAccepted()
                && members.countOwners(caller.organizationId()) <= 1) {
            throw new RequestRejected("role", "This is the last owner. Make somebody else an "
                    + "owner first, otherwise nobody could administer this organization.");
        }

        MemberRole previous = member.role();
        Member changed = members.save(member.withRole(newRole));
        String label = accounts.byId(member.accountId()).map(AccountRef::auditLabel)
                .orElse("member");

        audit.record(AuditEntry.succeeded(actor, ACTION,
                AuditTarget.of("member", changed.id(), label), caller.organizationId(),
                previous.label() + " to " + newRole.label()));
        return changed;
    }

    private static String article(MemberRole role) {
        return role == MemberRole.OWNER || role == MemberRole.ADMIN ? "an" : "a";
    }
}
