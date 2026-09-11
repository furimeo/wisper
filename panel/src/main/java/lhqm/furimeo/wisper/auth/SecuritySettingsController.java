package lhqm.furimeo.wisper.auth;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * {@code GET /settings/security} - the second factor, the recovery codes and the list of
 * signed-in browsers, on one screen.
 *
 * <p>Read-only. The writes belong to {@link TwoFactorSettingsController} and
 * {@link SessionSettingsController}, which both redirect back here; this class builds the
 * page and nothing else, so the four props it assembles are all in one place instead of
 * being rebuilt slightly differently by each write that returns to this URL.
 *
 * <p>Two of the props arrive as flash attributes rather than being read from the
 * database, and they are the two that must never be readable twice: {@code enrolment}
 * carries the TOTP secret being set up, and {@code recoveryCodes} carries a freshly
 * generated batch. Spring merges the flash map into the model, so both appear as props on
 * exactly the render that follows the redirect and are gone on a refresh.
 */
@Controller
public class SecuritySettingsController {

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AccountRecoveryCodeRepository recoveryCodes;

    public SecuritySettingsController(AccountRepository accounts, SessionRepository sessions,
                                      AccountRecoveryCodeRepository recoveryCodes) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
    }

    @GetMapping("/settings/security")
    public String security(@AuthenticationPrincipal SignedInAccount principal,
                           HttpServletRequest request, Model model) {

        Account account = accounts.findById(principal.id())
                .orElseThrow(() -> NotFoundException.of("account", principal.id()));

        String currentHash = currentSessionHash(request);
        List<SessionView> live = sessions
                .findByAccountIdAndRevokedAtIsNullOrderByLastSeenAtDesc(principal.id())
                .stream()
                .map(session -> SessionView.of(session,
                        session.sessionIdHash().equals(currentHash)))
                .toList();

        model.addAttribute("profile", AccountProfile.of(account));
        model.addAttribute("sessions", live);
        model.addAttribute("recoveryCodesRemaining",
                recoveryCodes.countByAccountIdAndUsedAtIsNull(principal.id()));
        model.addAttribute("recoveryCodesIssued",
                recoveryCodes.findByAccountId(principal.id()).size());
        // True between "start setup" and "confirm": the page has to offer the code box
        // rather than the "turn it on" button, even after a reload lost the QR code.
        model.addAttribute("enrolmentPending",
                account.totpSecret() != null && account.totpConfirmedAt() == null);
        return "auth/Security";
    }

    private static String currentSessionHash(HttpServletRequest request) {
        HttpSession httpSession = request.getSession(false);
        return httpSession == null ? "" : TokenDigest.of(httpSession.getId());
    }
}
