package lhqm.furimeo.wisper.org;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The tenant screens a customer sees: their organizations, and one organization's
 * overview with its plan and limits on it.
 *
 * <p>Owns {@code /orgs} and {@code /orgs/{organizationId}} (panel-http.md). Membership is
 * resolved on every path through {@link ResolveMembership}, which answers 404 for a tenant
 * the visitor is not in - the same answer a tenant that does not exist gets, so the URL
 * space cannot be used to enumerate customers.
 */
@Controller
public class OrganizationController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ListOrganizationsForAccount organizations;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository plans;
    private final MemberRepository members;
    private final QuotaGuard quotas;
    private final CreateOrganization createOrganization;
    private final AcceptInvite acceptInvite;
    private final RecordRefusal refusals;

    public OrganizationController(ResolveCurrentAccount currentAccount,
                                  ResolveMembership memberships,
                                  ListOrganizationsForAccount organizations,
                                  OrganizationRepository organizationRepository,
                                  PlanRepository plans, MemberRepository members,
                                  QuotaGuard quotas, CreateOrganization createOrganization,
                                  AcceptInvite acceptInvite, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.organizations = organizations;
        this.organizationRepository = organizationRepository;
        this.plans = plans;
        this.members = members;
        this.quotas = quotas;
        this.createOrganization = createOrganization;
        this.acceptInvite = acceptInvite;
        this.refusals = refusals;
    }

    /** What the customer belongs to, what they have been invited to, and a way to add one. */
    @GetMapping("/orgs")
    public String list(Model model) {
        AccountRef account = currentAccount.require();
        model.addAttribute("memberships", organizations.accepted(account.id()));
        model.addAttribute("invitations", organizations.invitations(account.id()));
        model.addAttribute("plans", plans.findSelectable());
        return "org/OrganizationList";
    }

    /** One tenant: who is in it, which plan it is on, and how much of it is used. */
    @GetMapping("/orgs/{organizationId}")
    public String overview(@PathVariable UUID organizationId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forAccount(account.id(), organizationId);
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));

        model.addAttribute("plan", plans.findById(organization.planId()).orElse(null));
        model.addAttribute("allowances", quotas.allowances(organizationId));
        model.addAttribute("memberCount", members.countAccepted(organizationId));
        model.addAttribute("viewerRole", membership.role());
        return "org/OrganizationOverview";
    }

    @PostMapping("/orgs")
    public String create(@Valid @ModelAttribute("form") CreateForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return "redirect:/orgs";
        }
        try {
            Organization created = createOrganization.create(actor, account.id(), form.name(),
                    form.slug(), form.planId());
            InertiaFlash.success(flash, created.name() + " is ready.");
            return "redirect:/orgs/" + created.id();
        } catch (RequestRejected rejected) {
            refusals.of(actor, "organization.create",
                    AuditTarget.unidentified("organization", form.slug()), null, rejected, flash);
            return "redirect:/orgs";
        }
    }

    /** Takes up an invitation. The account is the session's, never the request's. */
    @PostMapping("/orgs/{organizationId}/accept")
    public String accept(@PathVariable UUID organizationId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            acceptInvite.accept(actor, account.id(), organizationId);
            InertiaFlash.success(flash, "You have joined.");
            return "redirect:/orgs/" + organizationId;
        } catch (QuotaExceeded exceeded) {
            String name = organizationRepository.findById(organizationId)
                    .map(Organization::name).orElse("");
            refusals.of(actor, "member.accept",
                    AuditTarget.of("organization", organizationId, name), organizationId,
                    exceeded, flash);
            return "redirect:/orgs";
        }
    }

    /**
     * The new-organization form.
     *
     * @param name   what to call it
     * @param slug   the URL fragment; lower-cased and shape-checked in the use-case,
     *               where the uniqueness check also lives
     * @param planId the plan to start on, or null for the default
     */
    public record CreateForm(
            @NotBlank(message = "Give it a name.")
            @Size(max = 120, message = "That name is too long.")
            String name,
            @NotBlank(message = "Give it an address.")
            @Size(max = 63, message = "An address is at most 63 characters.")
            String slug,
            UUID planId) {
    }
}
