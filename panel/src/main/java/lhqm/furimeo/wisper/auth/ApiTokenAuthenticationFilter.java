package lhqm.furimeo.wisper.auth;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates {@code /api/v1/**} from an {@code Authorization: Bearer} header.
 *
 * <p>Stateless by construction: nothing here touches the {@code HttpSession}, and the
 * security context is put in the holder for the duration of the request without being
 * saved to a repository. A program calling the API and being handed a session cookie is
 * how a token client accidentally becomes a browser client with a longer-lived
 * credential than the one it presented.
 *
 * <p>A header that is present and does not authenticate is answered here with 401 rather
 * than being left to the chain. The chain's entry point for a browser is a redirect to
 * the sign-in page, and a curl script following a 302 to an HTML form is the least
 * useful error message it is possible to produce.
 *
 * <p>A missing header is left alone: {@code anyRequest().authenticated()} refuses it, and
 * {@link AuthSecurityContribution} points {@code /api/**} at a 401 entry point so that
 * refusal is also a status code rather than a page.
 *
 * <p>Deliberately not a {@code @Component}, for the reason given on
 * {@link SessionGateFilter}: a {@code Filter} bean is also registered with the servlet
 * container and would run a second time outside the security chain.
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    /** The prefix this filter answers for. Must line up with the CSRF exemption list. */
    static final String API_PREFIX = "/api/";

    private static final String BEARER = "Bearer ";

    private final AuthenticateApiToken authenticateApiToken;

    public ApiTokenAuthenticationFilter(AuthenticateApiToken authenticateApiToken) {
        this.authenticateApiToken = authenticateApiToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiTokenPrincipal> principal = authenticateApiToken.run(
                header.substring(BEARER.length()), request.getRemoteAddr());
        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiTokenAuthentication(principal.get()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Cleared rather than left for the container: with virtual threads the
            // carrier is reused, and a context left behind would leak one caller's
            // identity into the next request that lands on it.
            SecurityContextHolder.clearContext();
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"unauthorized\","
                + "\"message\":\"That API token is not valid, has been revoked, or has expired.\"}");
    }

    /**
     * The {@code Authentication} an accepted token produces.
     *
     * <p>Its own type rather than a {@code UsernamePasswordAuthenticationToken} carrying
     * an {@link ApiTokenPrincipal}: {@link AccountAuthenticationProvider} declares support
     * for that class, and a token request reaching the password provider would be a
     * sign-in attempt with an empty password.
     */
    static final class ApiTokenAuthentication extends AbstractAuthenticationToken {

        private final transient ApiTokenPrincipal principal;

        ApiTokenAuthentication(ApiTokenPrincipal principal) {
            super(principal.authorities());
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            // The token value is not kept past the comparison that accepted it.
            return "";
        }

        @Override
        public ApiTokenPrincipal getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return principal.accountEmail();
        }
    }
}
