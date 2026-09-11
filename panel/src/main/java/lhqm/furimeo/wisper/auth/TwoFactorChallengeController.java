package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The second step of signing in: six digits from the phone, or one recovery code.
 *
 * <p>Reachable only by a session that has already passed the password step and has
 * {@code second_factor_at} still null. {@link SessionGateFilter} keeps such a session away
 * from everything else, and sends it back here if it tries.
 *
 * <p>One input accepts both a TOTP code and a recovery code. Two fields would make a
 * person choose which kind of thing they are holding before they type it, and the two are
 * unmistakable from each other: six digits, or ten letters and digits with a hyphen.
 *
 * <h2>The attempt limit</h2>
 *
 * <p>Six digits is a million possibilities, and the drift window makes three of them
 * valid at any moment. Without a limit, a session that has the password could grind
 * through that. The counter lives in the {@code HttpSession} rather than on the account,
 * because the password has already been proven here - what is being limited is this
 * sign-in, not the account - and because putting it on the account would let somebody who
 * knows a password lock the owner out by failing the second step over and over.
 */
@Controller
public class TwoFactorChallengeController {

    /** Wrong codes allowed before the half-finished session is thrown away. */
    private static final int MAX_ATTEMPTS = 5;

    private static final String ATTEMPTS = "wisper.auth.two-factor-attempts";

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AccountRecoveryCodeRepository recoveryCodes;
    private final VerifyTwoFactorCode verifyTwoFactorCode;
    private final ConsumeRecoveryCode consumeRecoveryCode;
    private final CompleteSecondFactor completeSecondFactor;
    private final RevokeSession revokeSession;

    public TwoFactorChallengeController(AccountRepository accounts, SessionRepository sessions,
                                        AccountRecoveryCodeRepository recoveryCodes,
                                        VerifyTwoFactorCode verifyTwoFactorCode,
                                        ConsumeRecoveryCode consumeRecoveryCode,
                                        CompleteSecondFactor completeSecondFactor,
                                        RevokeSession revokeSession) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
        this.verifyTwoFactorCode = verifyTwoFactorCode;
        this.consumeRecoveryCode = consumeRecoveryCode;
        this.completeSecondFactor = completeSecondFactor;
        this.revokeSession = revokeSession;
    }

    @GetMapping("/login/two-factor")
    public String challenge(@AuthenticationPrincipal SignedInAccount principal,
                            HttpServletRequest request, Model model) {
        if (principal == null) {
            return "redirect:/login";
        }
        if (currentSession(request).map(Session::isSecondFactorSatisfied).orElse(false)) {
            return "redirect:/";
        }
        model.addAttribute("email", principal.email());
        model.addAttribute("recoveryCodesRemaining",
                recoveryCodes.countByAccountIdAndUsedAtIsNull(principal.id()));
        return "auth/TwoFactorChallenge";
    }

    @PostMapping("/login/two-factor")
    public String verify(@AuthenticationPrincipal SignedInAccount principal,
                         @RequestParam(name = "code", required = false) String code,
                         HttpServletRequest request, RedirectAttributes flash) {

        if (principal == null) {
            return "redirect:/login";
        }
        Optional<Account> account = accounts.findById(principal.id());
        if (account.isEmpty()) {
            return "redirect:/login";
        }
        if (!account.get().hasSecondFactor()) {
            /*
             * The second factor was turned off from somewhere else while this challenge
             * was on screen. There is nothing left to prove, so let the session through
             * rather than asking for a code no authenticator can produce.
             */
            completeSecondFactor.run(request.getSession().getId());
            return "redirect:/";
        }

        AuditActor actor = AuditActor.account(principal.id(), principal.email(), request);
        boolean accepted = verifyTwoFactorCode.run(account.get(), code)
                || consumeRecoveryCode.run(principal.id(), principal.email(), code, actor);
        if (accepted) {
            completeSecondFactor.run(request.getSession().getId());
            clearAttempts(request);
            return "redirect:/";
        }

        if (countAttempt(request) >= MAX_ATTEMPTS) {
            abandonSignIn(principal, request, actor);
            return "redirect:/login?error=code";
        }
        InertiaFlash.errors(flash, Map.of("code",
                "That code is not right. Check the clock on your phone, then try the next one."));
        return "redirect:/login/two-factor";
    }

    /**
     * Throws the half-finished sign-in away after too many wrong codes.
     *
     * <p>The row is revoked and the container session invalidated, so the password step
     * has to be repeated. That is the point: it turns an unlimited guessing budget into
     * five guesses per password entry.
     */
    private void abandonSignIn(SignedInAccount principal, HttpServletRequest request,
                               AuditActor actor) {
        currentSession(request).ifPresent(session ->
                revokeSession.run(session.id(), principal.id(),
                        SessionRevocationReason.SIGNED_OUT, actor));
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private Optional<Session> currentSession(HttpServletRequest request) {
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            return Optional.empty();
        }
        return sessions.findBySessionIdHash(TokenDigest.of(httpSession.getId()))
                .filter(session -> session.isLiveAt(Instant.now()));
    }

    private static int countAttempt(HttpServletRequest request) {
        HttpSession httpSession = request.getSession();
        Object held = httpSession.getAttribute(ATTEMPTS);
        int attempts = (held instanceof Integer count ? count : 0) + 1;
        httpSession.setAttribute(ATTEMPTS, attempts);
        return attempts;
    }

    private static void clearAttempts(HttpServletRequest request) {
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.removeAttribute(ATTEMPTS);
        }
    }
}
