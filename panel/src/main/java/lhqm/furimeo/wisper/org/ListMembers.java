package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Builds the member list a screen can actually render: the rows, with names on them.
 *
 * <p>Two queries, not one per row. {@link MemberRepository#findByOrganization} gives the
 * memberships in role order and {@link FindAccount#byIds} resolves every account in a
 * single statement - the shape that stops a twenty-person organization being twenty-one
 * round trips.
 */
@Component
public class ListMembers {

    private final MemberRepository members;
    private final FindAccount accounts;

    public ListMembers(MemberRepository members, FindAccount accounts) {
        this.members = members;
        this.accounts = accounts;
    }

    /** Owners first, then administrators, developers and viewers; joins before invites. */
    public List<MemberView> of(UUID organizationId) {
        List<Member> rows = members.findByOrganization(organizationId);
        Map<UUID, AccountRef> people =
                accounts.byIds(rows.stream().map(Member::accountId).toList());

        return rows.stream().map(member -> {
            /*
             * An account row can vanish between the two queries - member.account_id is
             * ON DELETE CASCADE, so the membership goes with it, but this list may have
             * been read a moment earlier. A placeholder keeps the page rendering; the
             * next load will not contain the row at all.
             */
            AccountRef person = people.get(member.accountId());
            return new MemberView(member.id(), member.accountId(),
                    person == null ? "(deleted account)" : person.email(),
                    person == null ? "(deleted account)" : person.displayName(),
                    member.role(), member.isAccepted(), member.invitedAt(), member.acceptedAt());
        }).toList();
    }
}
