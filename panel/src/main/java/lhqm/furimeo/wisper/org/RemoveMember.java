package lhqm.furimeo.wisper.org;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Takes somebody out of an organization, or lets them leave it.
 *
 * <p>Leaving and being removed are the same write and therefore the same use-case: the
 * row disappears either way, and splitting them would mean two places implementing the
 * last-owner rule. Which one it was is recorded in the audit detail, where it is a fact
 * about the event rather than a branch in the code.
 *
 * <p>Nothing the member created is touched. Projects, services and deployments belong to
 * the organization, not to the person who happened to press the button, and the audit
 * trail keeps their name against what they did through {@code actor_label} even after the
 * account itself is gone.
 */
@Component
public class RemoveMember {

    private static final String ACTION = "member.remove";

    private final MemberRepository members;
    private final FindAccount accounts;
    private final AuditTrail audit;

    public RemoveMember(MemberRepository members, FindAccount accounts, AuditTrail audit) {
        this.members = members;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * @param actor    who is doing it, for the trail
     * @param caller   the remover's membership, which fixes the organization
     * @param memberId the row being removed; may be the caller's own
     * @throws PermissionDenied  if the caller may not administer and is not leaving, or
     *                           an administrator is reaching for an owner
     * @throws NotFoundException if that member is not in the caller's organization
     * @throws RequestRejected   if it would leave the organization without an owner
     */
    @Transactional
    public void remove(AuditActor actor, Membership caller, UUID memberId) {
        Member member = members.findById(memberId)
                .filter(candidate -> candidate.organizationId().equals(caller.organizationId()))
                .orElseThrow(() -> NotFoundException.of("member", memberId));

        boolean leaving = member.accountId().equals(caller.accountId());
        if (!leaving) {
            caller.requireAdministration(ACTION);
            if (member.role() == MemberRole.OWNER && !caller.isOwner()) {
                throw new PermissionDenied(ACTION, caller.role(), "an owner, to remove an owner");
            }
        }
        if (member.role() == MemberRole.OWNER && member.isAccepted()
                && members.countOwners(caller.organizationId()) <= 1) {
            throw new RequestRejected(null, leaving
                    ? "You are the last owner. Make somebody else an owner before you leave."
                    : "That is the last owner. Make somebody else an owner first.");
        }

        String label = accounts.byId(member.accountId()).map(AccountRef::auditLabel)
                .orElse("member");
        members.delete(member);

        audit.record(AuditEntry.succeeded(actor, ACTION,
                AuditTarget.of("member", member.id(), label), caller.organizationId(),
                (leaving ? "Left" : "Removed") + " as " + member.role().label()
                        + (member.isAccepted() ? "" : " (invitation withdrawn)")));
    }
}
