package lhqm.furimeo.wisper.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * {@code /settings/tokens} - the list, the create form and the revoke button.
 *
 * <p>The token value appears exactly once, as a flash attribute on the render that
 * follows creation. It is deliberately not a prop on a page that can be reloaded and not
 * a query parameter: both survive a back button, and one of them survives in the
 * browser's history and in whatever sits in front of the panel.
 */
@Controller
public class ApiTokenSettingsController {

    private static final String BACK = "redirect:/settings/tokens";

    /** The longest life a token may be given, so "no expiry" is a deliberate choice. */
    private static final int MAX_EXPIRY_DAYS = 3650;

    private final ApiTokenRepository tokens;
    private final AccountOrganizations accountOrganizations;
    private final IssueApiToken issueApiToken;
    private final RevokeApiToken revokeApiToken;

    public ApiTokenSettingsController(ApiTokenRepository tokens,
                                      AccountOrganizations accountOrganizations,
                                      IssueApiToken issueApiToken,
                                      RevokeApiToken revokeApiToken) {
        this.tokens = tokens;
        this.accountOrganizations = accountOrganizations;
        this.issueApiToken = issueApiToken;
        this.revokeApiToken = revokeApiToken;
    }

    @GetMapping("/settings/tokens")
    public String list(@AuthenticationPrincipal SignedInAccount principal, Model model) {
        Instant now = Instant.now();
        List<ApiTokenView> all = tokens.findByAccountIdOrderByCreatedAtDesc(principal.id())
                .stream()
                .map(token -> ApiTokenView.of(token, now))
                .toList();

        model.addAttribute("tokens", all);
        model.addAttribute("organizations", accountOrganizations.forAccount(principal.id()));
        model.addAttribute("scopes", availableScopes(principal));
        // Only a platform operator may leave the organization blank; the form needs to
        // know so it can require the field for everybody else rather than letting the
        // submission bounce back with an error.
        model.addAttribute("mayIssueUnscoped", principal.isPlatformAdmin());
        return "auth/ApiTokens";
    }

    @PostMapping("/settings/tokens")
    public String create(@AuthenticationPrincipal SignedInAccount principal,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "organizationId", required = false)
                         String organizationId,
                         @RequestParam(name = "scopes", required = false) List<String> scopes,
                         @RequestParam(name = "expiresInDays", required = false)
                         Integer expiresInDays,
                         HttpServletRequest request, RedirectAttributes flash) {
        try {
            IssueApiToken.Issued issued = issueApiToken.run(principal.id(), name,
                    parseOrganization(organizationId), parseScopes(scopes),
                    parseExpiry(expiresInDays),
                    AuditActor.account(principal.id(), principal.email(), request));

            flash.addFlashAttribute("issuedToken", issued.value());
            flash.addFlashAttribute("issuedTokenName", issued.token().name());
            InertiaFlash.success(flash, "Copy the token now. It cannot be shown again.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        } catch (QuotaExceeded exceeded) {
            InertiaFlash.failure(flash, exceeded.message());
        }
        return BACK;
    }

    @PostMapping("/settings/tokens/{tokenId}/revoke")
    public String revoke(@AuthenticationPrincipal SignedInAccount principal,
                         @PathVariable UUID tokenId,
                         @RequestParam(name = "reason", required = false) String reason,
                         HttpServletRequest request, RedirectAttributes flash) {

        revokeApiToken.run(tokenId, principal.id(), reason,
                AuditActor.account(principal.id(), principal.email(), request));
        InertiaFlash.success(flash, "That token no longer works.");
        return BACK;
    }

    /** Node permissions are the platform's; offering them to a customer is a dead end. */
    private static List<String> availableScopes(SignedInAccount principal) {
        return ApiScope.allWireNames().stream()
                .filter(wire -> principal.isPlatformAdmin()
                        || ApiScope.ofWireName(wire).map(scope -> !scope.isPlatformOnly())
                                .orElse(false))
                .toList();
    }

    private static UUID parseOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(organizationId.strip());
        } catch (IllegalArgumentException notAUuid) {
            throw CredentialRejected.of("organizationId", "Choose an organization from the list.");
        }
    }

    private static Set<ApiScope> parseScopes(List<String> wireNames) {
        if (wireNames == null) {
            return Set.of();
        }
        Set<ApiScope> parsed = new LinkedHashSet<>();
        for (String wire : wireNames) {
            parsed.add(ApiScope.ofWireName(wire).orElseThrow(() -> CredentialRejected.of(
                    "scopes", "\"" + wire + "\" is not a permission this panel knows about.")));
        }
        return parsed;
    }

    private static Instant parseExpiry(Integer days) {
        if (days == null || days <= 0) {
            return null;
        }
        if (days > MAX_EXPIRY_DAYS) {
            throw CredentialRejected.of("expiresInDays",
                    "Choose at most " + MAX_EXPIRY_DAYS + " days, or leave it blank for a token "
                    + "that does not expire.");
        }
        return Instant.now().plus(Duration.ofDays(days));
    }
}
