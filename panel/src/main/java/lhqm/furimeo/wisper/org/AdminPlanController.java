package lhqm.furimeo.wisper.org;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The tiers themselves: create one, set its thirteen limits, choose the default, retire it.
 *
 * <p>Without this screen the {@code plan} and {@code quota} tables would be reachable only
 * by hand-written SQL, and a fresh installation could not open its first organization at
 * all - {@link CreateOrganization} refuses when there is no default plan, on purpose,
 * because a tenant on no plan has every limit at zero and looks like a broken panel.
 *
 * <p>Under {@code /admin/**}, gated on {@code ROLE_ADMIN} by {@code SecurityConfig}.
 */
@Controller
public class AdminPlanController {

    private final ResolveCurrentAccount currentAccount;
    private final PlanRepository plans;
    private final QuotaRepository quotas;
    private final OrganizationRepository organizations;
    private final CreatePlan createPlan;
    private final SetPlanQuota setPlanQuota;
    private final MakePlanDefault makePlanDefault;
    private final ArchivePlan archivePlan;
    private final RecordRefusal refusals;

    public AdminPlanController(ResolveCurrentAccount currentAccount, PlanRepository plans,
                               QuotaRepository quotas, OrganizationRepository organizations,
                               CreatePlan createPlan, SetPlanQuota setPlanQuota,
                               MakePlanDefault makePlanDefault, ArchivePlan archivePlan,
                               RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.plans = plans;
        this.quotas = quotas;
        this.organizations = organizations;
        this.createPlan = createPlan;
        this.setPlanQuota = setPlanQuota;
        this.makePlanDefault = makePlanDefault;
        this.archivePlan = archivePlan;
        this.refusals = refusals;
    }

    @GetMapping("/admin/plans")
    public String list(Model model) {
        model.addAttribute("plans", plans.findAllSelectableFirst());
        return "org/AdminPlanList";
    }

    /** One tier and every limit on it, including the ones still at zero. */
    @GetMapping("/admin/plans/{planId}")
    public String detail(@PathVariable UUID planId, Model model) {
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> NotFoundException.of("plan", planId));
        model.addAttribute("plan", plan);
        model.addAttribute("limits", limitsOf(planId));
        model.addAttribute("tenantCount", organizations.countByPlanId(planId));
        return "org/AdminPlanDetail";
    }

    @PostMapping("/admin/plans")
    public String create(@Valid @ModelAttribute("form") PlanForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return "redirect:/admin/plans";
        }
        try {
            Plan plan = createPlan.create(actor, form.code(), form.name(), form.description());
            InertiaFlash.success(flash, "Created " + plan.code()
                    + ". Every limit starts at zero.");
            return "redirect:/admin/plans/" + plan.id();
        } catch (RequestRejected rejected) {
            refusals.of(actor, "plan.create", AuditTarget.unidentified("plan", form.code()),
                    null, rejected, flash);
            return "redirect:/admin/plans";
        }
    }

    @PostMapping("/admin/plans/{planId}/quotas")
    public String setQuota(@PathVariable UUID planId,
                           @Valid @ModelAttribute("form") LimitForm form, BindingResult errors,
                           HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        String back = "redirect:/admin/plans/" + planId;
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            setPlanQuota.set(actor, planId, form.resource(), form.limitValue());
            InertiaFlash.success(flash, form.resource().name() + " is now " + form.limitValue()
                    + ".");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "quota.set", AuditTarget.of("plan", planId, ""), null, rejected,
                    flash);
        }
        return back;
    }

    @PostMapping("/admin/plans/{planId}/default")
    public String makeDefault(@PathVariable UUID planId, HttpServletRequest request,
                              RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Plan plan = makePlanDefault.makeDefault(actor, planId);
            InertiaFlash.success(flash, "New organizations now start on " + plan.code() + ".");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "plan.default", AuditTarget.of("plan", planId, ""), null,
                    rejected, flash);
        }
        return "redirect:/admin/plans";
    }

    @PostMapping("/admin/plans/{planId}/archive")
    public String archive(@PathVariable UUID planId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Plan plan = archivePlan.archive(actor, planId);
            InertiaFlash.success(flash, plan.code()
                    + " is retired. Organizations already on it are unaffected.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "plan.archive", AuditTarget.of("plan", planId, ""), null,
                    rejected, flash);
        }
        return "redirect:/admin/plans";
    }

    @PostMapping("/admin/plans/{planId}/restore")
    public String restore(@PathVariable UUID planId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Plan plan = archivePlan.restore(actor, planId);
            InertiaFlash.success(flash, plan.code() + " is selectable again.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "plan.restore", AuditTarget.of("plan", planId, ""), null,
                    rejected, flash);
        }
        return "redirect:/admin/plans";
    }

    /**
     * Every resource with the number the plan grants, zeros included.
     *
     * <p>A screen that lists only the rows that exist cannot be used to add the missing
     * ones, and the missing ones are exactly what an operator is looking for.
     */
    private List<PlanLimit> limitsOf(UUID planId) {
        Map<QuotaResource, Long> set = new EnumMap<>(QuotaResource.class);
        for (Quota quota : quotas.findByPlanId(planId)) {
            set.put(quota.resource(), quota.limitValue());
        }
        return Arrays.stream(QuotaResource.values())
                .map(resource -> new PlanLimit(resource, set.getOrDefault(resource, 0L),
                        set.containsKey(resource)))
                .toList();
    }

    private AuditActor operator(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }

    /**
     * One line of the limits table.
     *
     * @param resource which limit
     * @param limit    the number in force; zero whether the row says so or is absent
     * @param explicit whether a {@code quota} row exists, so the screen can show the
     *                 difference between "set to zero" and "never set", which is the
     *                 difference an operator is usually hunting for
     */
    public record PlanLimit(QuotaResource resource, long limit, boolean explicit) {
    }

    /** The new-plan form. */
    public record PlanForm(
            @NotBlank(message = "Give it a code.")
            @Size(max = 40, message = "A code is at most 40 characters.")
            String code,
            @NotBlank(message = "Give it a name.")
            @Size(max = 120, message = "That name is too long.")
            String name,
            @Size(max = 500, message = "Keep it to 500 characters.")
            String description) {
    }

    /** One limit being set. */
    public record LimitForm(
            @NotNull(message = "Pick a resource.") QuotaResource resource,
            @NotNull(message = "Give a limit.")
            @PositiveOrZero(message = "A limit is zero or more.")
            Long limitValue) {
    }
}
