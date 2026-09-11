package lhqm.furimeo.wisper.auth;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * {@code /settings/profile} - who you are, as far as the panel is concerned.
 *
 * <p>{@code /settings} on its own redirects here, so the entry in the navigation does not
 * have to know which of the settings pages is the first one.
 */
@Controller
public class ProfileController {

    private final AccountRepository accounts;
    private final UpdateDisplayName updateDisplayName;

    public ProfileController(AccountRepository accounts, UpdateDisplayName updateDisplayName) {
        this.accounts = accounts;
        this.updateDisplayName = updateDisplayName;
    }

    /** The settings section's landing page. */
    @GetMapping("/settings")
    public String settings() {
        return "redirect:/settings/profile";
    }

    @GetMapping("/settings/profile")
    public String profile(@AuthenticationPrincipal SignedInAccount principal, Model model) {
        Account account = accounts.findById(principal.id())
                .orElseThrow(() -> NotFoundException.of("account", principal.id()));
        model.addAttribute("profile", AccountProfile.of(account));
        return "auth/Profile";
    }

    @PostMapping("/settings/profile")
    public String rename(@AuthenticationPrincipal SignedInAccount principal,
                         @RequestParam(name = "displayName", required = false) String displayName,
                         HttpServletRequest request, RedirectAttributes flash) {
        try {
            updateDisplayName.run(principal.id(), displayName,
                    AuditActor.account(principal.id(), principal.email(), request));
            InertiaFlash.success(flash, "Saved.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return "redirect:/settings/profile";
    }
}
