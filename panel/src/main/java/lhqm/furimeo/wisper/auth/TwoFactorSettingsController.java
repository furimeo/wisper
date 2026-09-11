package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The four buttons on {@code /settings/security} that change the second factor.
 *
 * <p>All of them redirect back to that page, and the two that produce something the
 * customer must copy - the enrolment secret and a batch of recovery codes - hand it over
 * as a flash attribute. Spring merges the flash map into the model, so the value is a
 * prop on exactly the render that follows the redirect: on screen once, gone on a reload,
 * and never in a URL that could end up in a browser's history or a proxy's log.
 */
@Controller
public class TwoFactorSettingsController {

    private static final String BACK = "redirect:/settings/security";

    private final BeginTwoFactorEnrolment beginTwoFactorEnrolment;
    private final EnableTwoFactor enableTwoFactor;
    private final DisableTwoFactor disableTwoFactor;
    private final RegenerateRecoveryCodes regenerateRecoveryCodes;

    public TwoFactorSettingsController(BeginTwoFactorEnrolment beginTwoFactorEnrolment,
                                       EnableTwoFactor enableTwoFactor,
                                       DisableTwoFactor disableTwoFactor,
                                       RegenerateRecoveryCodes regenerateRecoveryCodes) {
        this.beginTwoFactorEnrolment = beginTwoFactorEnrolment;
        this.enableTwoFactor = enableTwoFactor;
        this.disableTwoFactor = disableTwoFactor;
        this.regenerateRecoveryCodes = regenerateRecoveryCodes;
    }

    /** Generates the secret and shows the QR code. Nothing is switched on yet. */
    @PostMapping("/settings/security/two-factor/begin")
    public String begin(@AuthenticationPrincipal SignedInAccount principal,
                        RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("enrolment", beginTwoFactorEnrolment.run(principal.id()));
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return BACK;
    }

    /** One working code switches it on and produces the recovery codes. */
    @PostMapping("/settings/security/two-factor/confirm")
    public String confirm(@AuthenticationPrincipal SignedInAccount principal,
                          @RequestParam(name = "code", required = false) String code,
                          HttpServletRequest request, RedirectAttributes flash) {
        try {
            List<String> codes = enableTwoFactor.run(principal.id(), code,
                    AuditActor.account(principal.id(), principal.email(), request));
            flash.addFlashAttribute("recoveryCodes", codes);
            InertiaFlash.success(flash, "Two-factor authentication is on. Save these recovery "
                    + "codes somewhere safe - they will not be shown again.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return BACK;
    }

    /** Guarded by the password, because this is the change worth taking an account for. */
    @PostMapping("/settings/security/two-factor/disable")
    public String disable(@AuthenticationPrincipal SignedInAccount principal,
                          @RequestParam(name = "currentPassword", required = false)
                          String currentPassword,
                          HttpServletRequest request, RedirectAttributes flash) {
        try {
            disableTwoFactor.run(principal.id(), currentPassword,
                    AuditActor.account(principal.id(), principal.email(), request));
            InertiaFlash.success(flash,
                    "Two-factor authentication is off and your recovery codes have been "
                    + "discarded.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return BACK;
    }

    /** A new batch. The old ones stop working the moment this returns. */
    @PostMapping("/settings/security/recovery-codes")
    public String regenerate(@AuthenticationPrincipal SignedInAccount principal,
                             HttpServletRequest request, RedirectAttributes flash) {
        try {
            List<String> codes = regenerateRecoveryCodes.run(principal.id(),
                    AuditActor.account(principal.id(), principal.email(), request));
            flash.addFlashAttribute("recoveryCodes", codes);
            InertiaFlash.success(flash,
                    "New recovery codes. Your previous ones no longer work.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return BACK;
    }
}
