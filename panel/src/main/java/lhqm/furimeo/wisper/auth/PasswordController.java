package lhqm.furimeo.wisper.auth;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * {@code POST /settings/password} - the change-password form on the profile page.
 *
 * <p>A separate controller from {@link ProfileController} because it is a separate thing:
 * one saves a preference, the other rotates a credential and signs several browsers out.
 * They share a screen, not a responsibility.
 *
 * <p>The browser making the change keeps its session; every other one is ended. That is
 * decided here rather than in {@link ChangePassword} because only the request knows which
 * session is "this one".
 */
@Controller
public class PasswordController {

    private final ChangePassword changePassword;
    private final SessionRepository sessions;

    public PasswordController(ChangePassword changePassword, SessionRepository sessions) {
        this.changePassword = changePassword;
        this.sessions = sessions;
    }

    @PostMapping("/settings/password")
    public String change(@AuthenticationPrincipal SignedInAccount principal,
                         @RequestParam(name = "currentPassword", required = false)
                         String currentPassword,
                         @RequestParam(name = "newPassword", required = false) String newPassword,
                         @RequestParam(name = "confirmPassword", required = false)
                         String confirmPassword,
                         HttpServletRequest request, RedirectAttributes flash) {

        if (newPassword != null && !newPassword.equals(confirmPassword)) {
            InertiaFlash.errors(flash, Map.of("confirmPassword",
                    "The two passwords do not match."));
            return "redirect:/settings/profile";
        }

        try {
            int ended = changePassword.run(principal.id(), currentPassword, newPassword,
                    currentSessionId(request),
                    AuditActor.account(principal.id(), principal.email(), request));
            InertiaFlash.success(flash, ended == 0
                    ? "Your password has been changed."
                    : "Your password has been changed, and " + ended
                      + " other session(s) were signed out.");
        } catch (CredentialRejected rejected) {
            InertiaFlash.errors(flash, Map.of(rejected.field(), rejected.getMessage()));
        }
        return "redirect:/settings/profile";
    }

    /**
     * The session row for the browser making the request, so it is not signed out by its
     * own password change. Null when there is somehow no row, which ends every session -
     * the safe direction to be wrong in.
     */
    private UUID currentSessionId(HttpServletRequest request) {
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            return null;
        }
        return sessions.findBySessionIdHash(TokenDigest.of(httpSession.getId()))
                .map(Session::id)
                .orElse(null);
    }
}
