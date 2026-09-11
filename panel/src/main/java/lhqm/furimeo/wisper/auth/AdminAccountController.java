package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * {@code /admin/accounts} - where accounts come from.
 *
 * <p>There is no public sign-up, so this screen is the only door {@link RegisterUser} has
 * (apart from {@link BootstrapAdminAccount}, which runs once on an empty database). Gated
 * by {@code ROLE_ADMIN} through the request map in {@code SecurityConfig}; nothing here
 * repeats that check, because a second copy of an authorization rule is a second place
 * for it to be wrong.
 *
 * <p>Suspending and reactivating live here too rather than in their own controller: all
 * four actions are "an operator administering accounts", they share one screen, and
 * splitting them would put the list and the buttons that change it in different files
 * for no gain.
 */
@Controller
public class AdminAccountController {

    private static final String BACK = "redirect:/admin/accounts";

    private final AccountRepository accounts;
    private final RegisterUser registerUser;
    private final SuspendAccount suspendAccount;
    private final ReactivateAccount reactivateAccount;

    public AdminAccountController(AccountRepository accounts, RegisterUser registerUser,
                                  SuspendAccount suspendAccount,
                                  ReactivateAccount reactivateAccount) {
        this.accounts = accounts;
        this.registerUser = registerUser;
        this.suspendAccount = suspendAccount;
        this.reactivateAccount = reactivateAccount;
    }

    @GetMapping("/admin/accounts")
    public String list(Model model) {
        List<AccountProfile> all = accounts.findAllOrderedByEmail().stream()
                .map(AccountProfile::of)
                .toList();
        model.addAttribute("accounts", all);
        // The page uses this to grey out "suspend" on the last operator rather than
        // letting the attempt come back as an error.
        model.addAttribute("activeAdminCount", accounts.countActiveAdmins());
        model.addAttribute("roles", List.of(PlatformRole.CUSTOMER.name(),
                PlatformRole.ADMIN.name()));
        return "auth/AdminAccounts";
    }

    @PostMapping("/admin/accounts")
    public String create(@AuthenticationPrincipal SignedInAccount principal,
                         @RequestParam(name = "email", required = false) String email,
                         @RequestParam(name = "displayName", required = false) String displayName,
                         @RequestParam(name = "password", required = false) String password,
                         @RequestParam(name = "platformRole", defaultValue = "CUSTOMER")
                         String platformRole,
                         HttpServletRequest request, RedirectAttributes flash) {
        try {
            Account created = registerUser.run(email, displayName, password, parseRole(platformRole),
                    AuditActor.account(principal.id(), principal.email(), request));
            InertiaFlash.success(flash, "Created " + created.email()
                    + ". Tell them the password over a channel that is not this panel, and ask "
                    + "them to change it.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return BACK;
    }

    @PostMapping("/admin/accounts/{accountId}/suspend")
    public String suspend(@AuthenticationPrincipal SignedInAccount principal,
                          @PathVariable UUID accountId,
                          HttpServletRequest request, RedirectAttributes flash) {
        try {
            Account suspended = suspendAccount.run(accountId,
                    AuditActor.account(principal.id(), principal.email(), request));
            InertiaFlash.success(flash, suspended.email()
                    + " is suspended and every session they had is signed out.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.failure(flash, rejected.getMessage());
        }
        return BACK;
    }

    @PostMapping("/admin/accounts/{accountId}/reactivate")
    public String reactivate(@AuthenticationPrincipal SignedInAccount principal,
                             @PathVariable UUID accountId,
                             HttpServletRequest request, RedirectAttributes flash) {
        Account active = reactivateAccount.run(accountId,
                AuditActor.account(principal.id(), principal.email(), request));
        InertiaFlash.success(flash, active.email() + " can sign in again.");
        return BACK;
    }

    private static PlatformRole parseRole(String value) {
        try {
            return PlatformRole.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw CredentialRejected.of("platformRole", "Choose a role from the list.");
        }
    }
}
