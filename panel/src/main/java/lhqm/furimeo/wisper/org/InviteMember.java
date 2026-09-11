package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Asks somebody who already has an account to join an organization.
 *
 * <p>The invitation is a {@code member} row with {@code accepted_at} null. It grants
 * nothing until {@link AcceptInvite} runs, so an address typed wrongly hands no access to
 * whoever owns it - they still have to sign in as that account to take it up.
 *
 * <p>Only an existing account can be invited. Creating one on somebody's behalf belongs
 * to {@code auth}, and an invitation flow that creates half an account is how a panel
 * ends up with rows nobody can sign in to.
 */
@Component
public class InviteMember {

    /** The audit action, and the string the refusals name themselves with. */
    private static final String ACTION = "member.invite";

    private final MemberRepository members;
    private final FindAccount accounts;
    private final QuotaGuard quotas;
    private final AuditTrail audit;

    public InviteMember(MemberRepository members, FindAccount accounts, QuotaGuard quotas,
                        AuditTrail audit) {
        this.members = members;
        this.accounts = accounts;
        this.quotas = quotas;
        this.audit = audit;
    }

    /**
     * @param actor  who is inviting, for the trail
     * @param caller the inviter's membership, which carries the organization
     * @param email  the address of an account that already exists
     * @param role   what they will be able to do once they accept
     * @throws PermissionDenied if the caller may not administer, or is not an owner and
     *                          is trying to create another owner
     * @throws QuotaExceeded    if the organization has no seat free, or is suspended
     * @throws RequestRejected  if nobody has that address, that account is suspended, or
     *                          they are already here
     */
    @Transactional
    public Member invite(AuditActor actor, Membership caller, String email, MemberRole role) {
        caller.requireAdministration(ACTION);
        if (role == MemberRole.OWNER && !caller.isOwner()) {
            // An administrator who could mint owners could promote themselves through a
            // second account, which makes the OWNER boundary decorative.
            throw new PermissionDenied(ACTION, caller.role(), "an owner, to invite another owner");
        }

        // Before the insert, inside this transaction: the seat count and the row that
        // takes the seat see the same snapshot.
        quotas.require(caller.organizationId(), QuotaResource.MEMBER, 1);

        AccountRef invitee = accounts.byEmail(email).orElseThrow(() -> new RequestRejected("email",
                "Nobody has signed up with that address. Ask them to create an account first, "
                        + "then invite them."));
        if (!invitee.active()) {
            throw new RequestRejected("email", "That account is suspended.");
        }
        refuseIfAlreadyHere(caller.organizationId(), invitee);

        Member invitation = members.save(Member.invited(UUID.randomUUID(),
                caller.organizationId(), invitee.id(), role, caller.accountId(), Instant.now()));

        audit.record(AuditEntry.succeeded(actor, ACTION,
                AuditTarget.of("member", invitation.id(), invitee.auditLabel()),
                caller.organizationId(), "Invited as " + role.label()));
        return invitation;
    }

    /**
     * The unique index on {@code (organization_id, account_id)} would refuse the second
     * row anyway; catching it here is what turns a constraint violation into a sentence
     * that tells the customer which of the two situations they are in.
     */
    private void refuseIfAlreadyHere(UUID organizationId, AccountRef invitee) {
        Optional<Member> existing =
                members.findByOrganizationIdAndAccountId(organizationId, invitee.id());
        if (existing.isEmpty()) {
            return;
        }
        throw new RequestRejected("email", existing.get().isAccepted()
                ? invitee.email() + " is already a member."
                : invitee.email() + " has already been invited and has not answered yet.");
    }
}
