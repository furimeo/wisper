package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Takes up an outstanding invitation, which is the moment access actually begins.
 *
 * <p>The seat is checked again here and not only at invitation time. Between the two, the
 * organization can have filled up, had its plan lowered or been suspended; charging the
 * seat when the row is created and never re-checking is how a tenant ends up with more
 * accepted members than its plan allows.
 */
@Component
public class AcceptInvite {

    private final MemberRepository members;
    private final FindAccount accounts;
    private final QuotaGuard quotas;
    private final AuditTrail audit;

    public AcceptInvite(MemberRepository members, FindAccount accounts, QuotaGuard quotas,
                        AuditTrail audit) {
        this.members = members;
        this.accounts = accounts;
        this.quotas = quotas;
        this.audit = audit;
    }

    /**
     * @param actor          the accepting account, for the trail
     * @param accountId      whose invitation this is; taken from the session, never from
     *                       the request, so one member cannot accept another's invitation
     * @param organizationId the organization being joined
     * @throws NotFoundException if there is no outstanding invitation for this pair - the
     *                           same answer a stranger gets, so the endpoint cannot be
     *                           used to discover which organizations exist
     * @throws QuotaExceeded     if the organization has since filled up or been suspended
     */
    @Transactional
    public Membership accept(AuditActor actor, UUID accountId, UUID organizationId) {
        Member invitation = members
                .findByOrganizationIdAndAccountId(organizationId, accountId)
                .filter(member -> !member.isAccepted())
                .orElseThrow(() -> NotFoundException.of("invitation", organizationId));

        quotas.require(organizationId, QuotaResource.MEMBER, 1);

        Member accepted = members.save(invitation.accepted(Instant.now()));
        String label = accounts.byId(accountId).map(AccountRef::auditLabel).orElse("member");

        audit.record(AuditEntry.succeeded(actor, "member.accept",
                AuditTarget.of("member", accepted.id(), label), organizationId,
                "Joined as " + accepted.role().label()));
        return accepted.membership();
    }
}
