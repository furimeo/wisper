package lhqm.furimeo.wisper.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

import lhqm.furimeo.wisper.web.HttpSecurityContribution;

/**
 * Everything the {@code auth} package adds to the filter chain.
 *
 * <p>{@code SecurityConfig} answers one question - who may reach which URL - and this
 * answers the other one: how the panel works out who is asking. Keeping them apart is
 * what the {@link HttpSecurityContribution} seam exists for
 * (docs/contracts/panel-http.md), and it means the request-to-role map is not in the same
 * file as a token parser.
 *
 * <h2>Why the authentication manager is set explicitly</h2>
 *
 * <p>{@link AccountAuthenticationProvider} is a bean, so Spring Boot would normally wire
 * it into the chain on its own. It would also be added a second time here if this class
 * called {@code authenticationProvider(...)}, and a {@code ProviderManager} holding the
 * same provider twice counts every wrong password twice - which halves the sign-in
 * throttle without changing the number in the configuration. Handing the chain a manager
 * built from exactly one provider removes the ambiguity rather than relying on which
 * registration path happens to win.
 */
@Component
public class AuthSecurityContribution implements HttpSecurityContribution {

    /** A program calling the API gets a status code, never a redirect to a sign-in form. */
    private static final RequestMatcher API_REQUESTS = new RequestMatcher() {
        @Override
        public boolean matches(HttpServletRequest request) {
            return request.getRequestURI().startsWith(ApiTokenAuthenticationFilter.API_PREFIX);
        }
    };

    private final AccountAuthenticationProvider accountAuthenticationProvider;
    private final SignInSuccessHandler signInSuccessHandler;
    private final SignInFailureHandler signInFailureHandler;
    private final SessionSignOutHandler sessionSignOutHandler;
    private final SessionGateFilter sessionGateFilter;
    private final ApiTokenAuthenticationFilter apiTokenAuthenticationFilter;

    /**
     * The two filters are built here rather than injected, because a {@code Filter} that
     * is also a Spring bean is registered with the servlet container as well as with the
     * security chain, and then runs twice on every request.
     */
    public AuthSecurityContribution(AccountAuthenticationProvider accountAuthenticationProvider,
                                    SignInSuccessHandler signInSuccessHandler,
                                    SignInFailureHandler signInFailureHandler,
                                    SessionSignOutHandler sessionSignOutHandler,
                                    SessionRepository sessions,
                                    AuthSettings settings,
                                    AuthenticateApiToken authenticateApiToken) {
        this.accountAuthenticationProvider = accountAuthenticationProvider;
        this.signInSuccessHandler = signInSuccessHandler;
        this.signInFailureHandler = signInFailureHandler;
        this.sessionSignOutHandler = sessionSignOutHandler;
        this.sessionGateFilter = new SessionGateFilter(sessions, settings);
        this.apiTokenAuthenticationFilter = new ApiTokenAuthenticationFilter(authenticateApiToken);
    }

    @Override
    public void apply(HttpSecurity http) throws Exception {
        http.authenticationManager(new ProviderManager(accountAuthenticationProvider));

        // Re-entering formLogin() and logout() returns the configurers SecurityConfig
        // already applied, so this adds handlers to them rather than replacing the URLs.
        http.formLogin(form -> form
                .successHandler(signInSuccessHandler)
                .failureHandler(signInFailureHandler));

        http.logout(logout -> logout.addLogoutHandler(sessionSignOutHandler));

        http.exceptionHandling(handling -> handling
                .defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API_REQUESTS));

        /*
         * Two positions, two reasons.
         *
         * The token filter sits ahead of the form-login filter, the classic place for a
         * per-request credential: the security context is already restored, and a request
         * carrying a bearer token never reaches the machinery that would treat it as a
         * sign-in attempt with an empty password.
         *
         * The session gate sits immediately before authorization, which is the only
         * window where the principal exists and the request has not yet been allowed
         * through. A session with an outstanding second factor is authenticated as far as
         * the request map is concerned, so the gate has to be what turns it back.
         */
        http.addFilterBefore(apiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(sessionGateFilter, AuthorizationFilter.class);
    }
}
