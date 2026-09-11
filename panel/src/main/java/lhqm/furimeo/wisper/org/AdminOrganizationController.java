package lhqm.furimeo.wisper.org;

import java.util.List;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The operator's view of every tenant: which plan they are on, whether they are
 * suspended, and the two buttons that change either.
 *
 * <p>Under {@code /admin/**}, which {@code SecurityConfig} gates on {@code ROLE_ADMIN}.
 * No membership is involved: a platform operator is not a member of the organizations
 * they administer, and requiring one would mean adding themselves to every customer's
 * tenant to do their job.
 *
 * <p>Quota exceptions are the neighbouring screen and live in
 * {@link AdminQuotaOverrideController}; keeping them apart is what keeps both files about
 * one thing.
 */
@Controller
public class AdminOrganizationController {

    private final ResolveCurrentAccount currentAccount;
    private final OrganizationRepository organizations;
    private final PlanRepository plans;
    private final ListMembers listMembers;
    private final QuotaGuard quotas;
    private final QuotaOverrideRepository overrides;
    private final AssignPlan assignPlan;
    private final SuspendOrganization suspendOrganization;
    private final ResumeOrganization resumeOrganization;
    private final RecordRefusal refusals;

    public AdminOrganizationController(ResolveCurrentAccount currentAccount,
                                       OrganizationRepository organizations, PlanRepository plans,
                                       ListMembers listMembers, QuotaGuard quotas,
                                       QuotaOverrideRepository overrides, AssignPlan assignPlan,
                                       SuspendOrganization suspendOrganization,
                                       ResumeOrganization resumeOrganization,
                                       RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.organizations = organizations;
        this.plans = plans;
        this.listMembers = listMembers;
        this.quotas = quotas;
        this.overrides = overrides;
        this.assignPlan = assignPlan;
        this.suspendOrganization = suspendOrganization;
        this.resumeOrganization = resumeOrganization;
        this.refusals = refusals;
    }

    /** Every tenant, newest first, with the plans they could be moved to. */
    @GetMapping("/admin/organizations")
    public String list(Model model) {
        model.addAttribute("tenants", organizations.findAllNewestFirst());
        model.addAttribute("plans", plans.findAllSelectableFirst());
        return "org/AdminOrganizationList";
    }

    /** One tenant in full: plan, limits, exceptions, people. */
    @GetMapping("/admin/organizations/{organizationId}")
    public String detail(@PathVariable UUID organizationId, Model model) {
        Organization organization = load(organizationId);
        model.addAttribute("tenant", organization);
        model.addAttribute("plan", plans.findById(organization.planId()).orElse(null));
        model.addAttribute("plans", plans.findAllSelectableFirst());
        model.addAttribute("allowances", quotas.allowances(organizationId));
        model.addAttribute("overrides", overrides.findByOrganizationId(organizationId));
        model.addAttribute("members", listMembers.of(organizationId));
        model.addAttribute("resources", List.of(QuotaResource.values()));
        return "org/AdminOrganizationDetail";
    }

    @PostMapping("/admin/organizations/{organizationId}/plan")
    public String assign(@PathVariable UUID organizationId,
                         @Valid @ModelAttribute("form") AssignPlanForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        String back = "redirect:/admin/organizations/" + organizationId;
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            Organization moved = assignPlan.assign(actor, organizationId, form.planId());
            InertiaFlash.success(flash, moved.name() + " moved.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "plan.assign", target(organizationId), organizationId, rejected,
                    flash);
        }
        return back;
    }

    @PostMapping("/admin/organizations/{organizationId}/suspend")
    public String suspend(@PathVariable UUID organizationId,
                          @Valid @ModelAttribute("form") SuspendForm form, BindingResult errors,
                          HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        String back = "redirect:/admin/organizations/" + organizationId;
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            Organization suspended =
                    suspendOrganization.suspend(actor, organizationId, form.reason());
            InertiaFlash.success(flash, suspended.name()
                    + " is suspended. Their containers keep running.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "organization.suspend", target(organizationId), organizationId,
                    rejected, flash);
        }
        return back;
    }

    @PostMapping("/admin/organizations/{organizationId}/resume")
    public String resume(@PathVariable UUID organizationId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Organization resumed = resumeOrganization.resume(actor, organizationId);
            InertiaFlash.success(flash, resumed.name() + " is active again.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "organization.resume", target(organizationId), organizationId,
                    rejected, flash);
        }
        return "redirect:/admin/organizations/" + organizationId;
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));
    }

    private AuditTarget target(UUID organizationId) {
        return AuditTarget.of("organization", organizationId,
                organizations.findById(organizationId).map(Organization::name).orElse(""));
    }

    private AuditActor operator(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }

    /** The plan picker on the tenant screen. */
    public record AssignPlanForm(@NotNull(message = "Pick a plan.") UUID planId) {
    }

    /** The suspension form. A reason is required; the customer is shown it. */
    public record SuspendForm(
            @NotBlank(message = "Say why. The customer is shown this.")
            @Size(max = 500, message = "Keep it to 500 characters.")
            String reason) {
    }
}
