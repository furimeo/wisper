package lhqm.furimeo.wisper.auth;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * Ending sessions from {@code /settings/security}.
 *
 * <p>Revoking a session is a row update, not a call into the servlet container: the
 * browser being signed out is somewhere else, and its {@code HttpSession} is not
 * reachable from this request. {@link SessionGateFilter} is what turns the revoked row
 * into an actual sign-out, on that browser's next request - which is why "sign out
 * everywhere" is described to the customer as taking effect immediately rather than
 * instantly.
 */
@Controller
public class SessionSettingsController {

    private static final String BACK = "redirect:/settings/security";

    private final SessionRepository sessions;
    private final RevokeSession revokeSession;
    private final SignOutEverywhere signOutEverywhere;

    public SessionSettingsController(SessionRepository sessions, RevokeSession revokeSession,
                                     SignOutEverywhere signOutEverywhere) {
        this.sessions = sessions;
        this.revokeSession = revokeSession;
        this.signOutEverywhere = signOutEverywhere;
    }

    /** One device. A session that is not this account's answers 404, like any other. */
    @PostMapping("/settings/sessions/{sessionId}/revoke")
    public String revoke(@AuthenticationPrincipal SignedInAccount principal,
                         @PathVariable UUID sessionId,
                         HttpServletRequest request, RedirectAttributes flash) {

        revokeSession.run(sessionId, principal.id(), SessionRevocationReason.SIGNED_OUT,
                AuditActor.account(principal.id(), principal.email(), request));
        InertiaFlash.success(flash, "That session has been signed out.");
        return BACK;
    }

    /**
     * Everywhere but here.
     *
     * <p>This browser is spared deliberately. The button exists for "I do not know where
     * I am signed in", and answering it by signing the person out of the page they
     * pressed it on reads as the panel having crashed.
     */
    @PostMapping("/settings/sessions/revoke-all")
    public String revokeAll(@AuthenticationPrincipal SignedInAccount principal,
                            HttpServletRequest request, RedirectAttributes flash) {

        int ended = signOutEverywhere.run(principal.id(), principal.email(),
                currentSessionId(request), SessionRevocationReason.SIGNED_OUT_EVERYWHERE,
                AuditActor.account(principal.id(), principal.email(), request));

        InertiaFlash.success(flash, ended == 0
                ? "There were no other sessions to sign out."
                : ended + " other session(s) signed out.");
        return BACK;
    }

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
