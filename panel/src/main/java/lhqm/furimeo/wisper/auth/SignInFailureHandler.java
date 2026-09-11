package lhqm.furimeo.wisper.auth;

import java.io.IOException;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sends a failed sign-in back to the form with a reason it can render.
 *
 * <p>The reason travels in the query string rather than in a flash attribute, because
 * this runs inside the security filter chain - before the {@code DispatcherServlet} has
 * set up the attributes Spring's {@code FlashMap} needs, so writing one here would throw.
 * {@link SignInController} turns the parameter back into a sentence.
 *
 * <h2>What the three outcomes may say</h2>
 *
 * <p>A wrong password and an unknown address produce the same {@code ?error=credentials}.
 * Distinguishing them would turn the form into a "does this person have an account here"
 * oracle, which is the same reason {@link AuthenticateUser} spends the cost of a BCrypt
 * comparison on an address that does not exist.
 *
 * <p>Locked and suspended are told apart, and that is deliberate. Both only happen to an
 * account that exists, so neither leaks anything the attempt itself did not already
 * establish - and a person who cannot get in needs to know whether to wait fifteen
 * minutes or to contact an operator.
 */
@Component
public class SignInFailureHandler implements AuthenticationFailureHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String reason;
        if (exception instanceof LockedException) {
            reason = "locked";
        } else if (exception instanceof DisabledException) {
            reason = "suspended";
        } else {
            reason = "credentials";
        }
        redirectStrategy.sendRedirect(request, response, "/login?error=" + reason);
    }
}
