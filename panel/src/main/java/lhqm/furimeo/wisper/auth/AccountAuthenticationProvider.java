package lhqm.furimeo.wisper.auth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * The bridge between Spring Security's form-login filter and {@link AuthenticateUser}.
 *
 * <p>Thin on purpose. Everything that decides whether somebody may sign in - the
 * throttle, the suspension check, the audit entries - is in the use-case, where it can be
 * tested without a servlet. This class translates: an {@code Authentication} in, a
 * {@code SecurityContext}-ready {@code Authentication} out, and the framework's
 * exceptions on the way through.
 *
 * <p>The remote address comes from {@code WebAuthenticationDetails}, which the form-login
 * filter attaches. That is the only route to it here: there is no
 * {@code HttpServletRequest} in an {@code AuthenticationProvider}, and reaching for
 * {@code RequestContextHolder} would make the provider depend on a filter order nothing
 * declares.
 */
@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticateUser authenticateUser;

    public AccountAuthenticationProvider(AuthenticateUser authenticateUser) {
        this.authenticateUser = authenticateUser;
    }

    /**
     * @throws AuthenticationException as thrown by {@link AuthenticateUser}:
     *         {@code BadCredentialsException}, {@code LockedException} or
     *         {@code DisabledException}, which {@link SignInFailureHandler} turns into
     *         the message on the sign-in page
     */
    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = authentication.getName();
        Object credentials = authentication.getCredentials();
        String remoteAddress = authentication.getDetails() instanceof WebAuthenticationDetails web
                ? web.getRemoteAddress()
                : null;

        SignedInAccount account = authenticateUser.run(email,
                credentials == null ? "" : credentials.toString(), remoteAddress);

        UsernamePasswordAuthenticationToken authenticated =
                UsernamePasswordAuthenticationToken.authenticated(account, null,
                        account.authorities());
        authenticated.setDetails(authentication.getDetails());
        return authenticated;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
