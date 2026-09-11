package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * Who is in an organization, and the three things that can change about them: an
 * invitation, a role, a removal.
 *
 * <p>One screen, {@code /orgs/{organizationId}/members}, and three posts nested under it.
 * The nesting is the authorization surface: none of these routes can be reached without
 * an organization id, so there is no version of the handler that forgets to check whose
 * organization it is.
 *
 * <p>Every refusal - wrong role, no seat left, last owner - goes through
 * {@link RecordRefusal}, so the customer gets a sentence and the trail gets a
 * {@code DENIED} line without either being anybody's job to remember.
 */
@Controller
public class MemberController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ListMembers listMembers;
    private final QuotaGuard quotas;
    private final InviteMember inviteMember;
    private final ChangeMemberRole changeMemberRole;
    private final RemoveMember removeMember;
    private final RecordRefusal refusals;

    public MemberController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                            ListMembers listMembers, QuotaGuard quotas, InviteMember inviteMember,
                            ChangeMemberRole changeMemberRole, RemoveMember removeMember,
                            RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.listMembers = listMembers;
        this.quotas = quotas;
        this.inviteMember = inviteMember;
        this.changeMemberRole = changeMemberRole;
        this.removeMember = removeMember;
        this.refusals = refusals;
    }

    @GetMapping("/orgs/{organizationId}/members")
    public String list(@PathVariable UUID organizationId, Model model) {
        AccountRef account = currentAccount.require();
        Membership viewer = memberships.forAccount(account.id(), organizationId);

        model.addAttribute("members", listMembers.of(organizationId));
        model.addAttribute("viewer", viewer);
        model.addAttribute("roles", List.of(MemberRole.values()));
        model.addAttribute("seats", quotas.allowanceFor(organizationId, QuotaResource.MEMBER));
        return "org/MemberList";
    }

    @PostMapping("/orgs/{organizationId}/members")
    public String invite(@PathVariable UUID organizationId,
                         @Valid @ModelAttribute("form") InviteForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership caller = memberships.forAccount(account.id(), organizationId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        String back = "redirect:/orgs/" + organizationId + "/members";

        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            inviteMember.invite(actor, caller, form.email(), form.role());
            InertiaFlash.success(flash, "Invited " + form.email() + ".");
        } catch (PermissionDenied | QuotaExceeded | RequestRejected refused) {
            refusals.of(actor, "member.invite",
                    AuditTarget.unidentified("member", form.email()), organizationId,
                    refused, flash);
        }
        return back;
    }

    @PostMapping("/orgs/{organizationId}/members/{memberId}/role")
    public String changeRole(@PathVariable UUID organizationId, @PathVariable UUID memberId,
                             @Valid @ModelAttribute("form") RoleForm form, BindingResult errors,
                             HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership caller = memberships.forAccount(account.id(), organizationId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        String back = "redirect:/orgs/" + organizationId + "/members";

        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            Member changed = changeMemberRole.change(actor, caller, memberId, form.role());
            InertiaFlash.success(flash,
                    "Now " + changed.role().label().toLowerCase(Locale.ROOT) + ".");
        } catch (PermissionDenied | RequestRejected refused) {
            refusals.of(actor, "member.role_change",
                    AuditTarget.of("member", memberId, ""), organizationId, refused, flash);
        }
        return back;
    }

    @PostMapping("/orgs/{organizationId}/members/{memberId}/remove")
    public String remove(@PathVariable UUID organizationId, @PathVariable UUID memberId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership caller = memberships.forAccount(account.id(), organizationId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            removeMember.remove(actor, caller, memberId);
            InertiaFlash.success(flash, "Removed.");
        } catch (PermissionDenied | RequestRejected refused) {
            refusals.of(actor, "member.remove", AuditTarget.of("member", memberId, ""),
                    organizationId, refused, flash);
            return "redirect:/orgs/" + organizationId + "/members";
        }
        /*
         * Somebody who removed themselves cannot load the member list they came from, so
         * they go to the organization list instead of to a 404 that looks like a bug.
         */
        boolean stillHere = memberships.optionally(account.id(), organizationId).isPresent();
        return stillHere ? "redirect:/orgs/" + organizationId + "/members" : "redirect:/orgs";
    }

    /**
     * The invite form.
     *
     * @param email the address of an account that already exists
     * @param role  what they will be able to do once they accept
     */
    public record InviteForm(
            @NotBlank(message = "Give an email address.")
            @Email(message = "That is not an email address.")
            String email,
            @NotNull(message = "Pick a role.") MemberRole role) {
    }

    /** The role picker on one member's row. */
    public record RoleForm(@NotNull(message = "Pick a role.") MemberRole role) {
    }
}
