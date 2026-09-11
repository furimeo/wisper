package lhqm.furimeo.wisper.org;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Grants and removes one tenant's exceptions to its plan.
 *
 * <p>Two posts nested under the tenant screen in {@link AdminOrganizationController},
 * which is where the resulting exceptions are listed. A separate file because granting an
 * exception is a different decision from moving somebody between tiers, and because a
 * controller holding both would be the beginning of the admin god-file.
 *
 * <p>Expiry is expressed in days rather than as a date. An operator raising a limit "for
 * the migration next week" is thinking in days; a date picker on a phone, in a timezone
 * that may not be the server's, is three chances to get an off-by-one.
 */
@Controller
public class AdminQuotaOverrideController {

    private final ResolveCurrentAccount currentAccount;
    private final OrganizationRepository organizations;
    private final GrantQuotaOverride grantQuotaOverride;
    private final RevokeQuotaOverride revokeQuotaOverride;
    private final RecordRefusal refusals;

    public AdminQuotaOverrideController(ResolveCurrentAccount currentAccount,
                                        OrganizationRepository organizations,
                                        GrantQuotaOverride grantQuotaOverride,
                                        RevokeQuotaOverride revokeQuotaOverride,
                                        RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.organizations = organizations;
        this.grantQuotaOverride = grantQuotaOverride;
        this.revokeQuotaOverride = revokeQuotaOverride;
        this.refusals = refusals;
    }

    @PostMapping("/admin/organizations/{organizationId}/quota-overrides")
    public String grant(@PathVariable UUID organizationId,
                        @Valid @ModelAttribute("form") GrantForm form, BindingResult errors,
                        HttpServletRequest request, RedirectAttributes flash) {
        AccountRef operator = currentAccount.require();
        AuditActor actor = AuditActor.account(operator.id(), operator.email(), request);
        String back = "redirect:/admin/organizations/" + organizationId;

        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            grantQuotaOverride.grant(actor, organizationId, form.resource(), form.limitValue(),
                    form.reason(), form.expiryFrom(Instant.now()), operator.id());
            InertiaFlash.success(flash, form.resource().name() + " set to " + form.limitValue()
                    + " for this organization.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "quota_override.grant",
                    AuditTarget.unidentified("quota_override", form.resource().name()),
                    organizationId, rejected, flash);
        }
        return back;
    }

    @PostMapping("/admin/organizations/{organizationId}/quota-overrides/{resource}/revoke")
    public String revoke(@PathVariable UUID organizationId, @PathVariable QuotaResource resource,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef operator = currentAccount.require();
        AuditActor actor = AuditActor.account(operator.id(), operator.email(), request);
        if (!organizations.existsById(organizationId)) {
            throw NotFoundException.of("organization", organizationId);
        }
        revokeQuotaOverride.revoke(actor, organizationId, resource);
        InertiaFlash.success(flash, resource.name() + " is back on the plan's limit.");
        return "redirect:/admin/organizations/" + organizationId;
    }

    /**
     * The grant form.
     *
     * @param resource      which limit is being replaced
     * @param limitValue    the ceiling that applies instead of the plan's; may be lower
     *                      than the plan as well as higher
     * @param reason        why, required, and kept on the row
     * @param expiresInDays how long it stands, or null for indefinitely
     */
    public record GrantForm(
            @NotNull(message = "Pick a resource.") QuotaResource resource,
            @NotNull(message = "Give a limit.")
            @PositiveOrZero(message = "A limit is zero or more.")
            Long limitValue,
            @NotBlank(message = "Say why. This is kept with the exception.")
            @Size(max = 500, message = "Keep it to 500 characters.")
            String reason,
            @Min(value = 1, message = "At least a day, or leave it empty for no expiry.")
            @Max(value = 3650, message = "At most ten years.")
            Integer expiresInDays) {

        Instant expiryFrom(Instant now) {
            return expiresInDays == null ? null : now.plus(Duration.ofDays(expiresInDays));
        }
    }
}
