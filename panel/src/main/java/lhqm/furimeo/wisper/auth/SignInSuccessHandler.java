package lhqm.furimeo.wisper.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * What happens the moment a password is accepted: the session row is written, and the
 * browser goes either to the panel or to the TOTP challenge.
 *
 * <p>This runs after Spring Security's session-fixation protection has replaced the
 * container's session id, which is why the hash stored here is the one every later
 * request will present. Writing the row before that point would record an id that is
 * already dead, and every subsequent request would find no session row at all.
 *
 * <p>{@code /login/two-factor} is not a redirect the browser could skip. The row is
 * written with {@code second_factor_at} null, and {@link SessionGateFilter} refuses
 * everything but {@code /login/**} and {@code /logout} while it stays null - so typing a
 * different URL after the password step gets a person back to the challenge, not into
 * the panel.
 */
@Component
public class SignInSuccessHandler implements AuthenticationSuccessHandler {

    /** Where the challenge lives. Referenced by the gate filter as well. */
    public static final String SECOND_FACTOR_PATH = "/login/two-factor";

    private final AccountRepository accounts;
    private final RecordSignIn recordSignIn;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private final SavedRequestAwareAuthenticationSuccessHandler wherever;

    public SignInSuccessHandler(AccountRepository accounts, RecordSignIn recordSignIn) {
        this.accounts = accounts;
        this.recordSignIn = recordSignIn;
        this.wherever = new SavedRequestAwareAuthenticationSuccessHandler();
        // Honour the page the visitor was trying to reach; fall back to the dashboard.
        this.wherever.setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        SignedInAccount principal = (SignedInAccount) authentication.getPrincipal();
        Account account = accounts.findById(principal.id())
                .orElseThrow(() -> NotFoundException.of("account", principal.id()));

        boolean challengeOutstanding = account.hasSecondFactor();
        recordSignIn.run(principal.id(), request.getSession().getId(), challengeOutstanding,
                request);

        if (challengeOutstanding) {
            redirectStrategy.sendRedirect(request, response, SECOND_FACTOR_PATH);
            return;
        }
        wherever.onAuthenticationSuccess(request, response, authentication);
    }
}
