package lhqm.furimeo.wisper.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The one row read that answers the three questions every browser request asks: is this
 * session still allowed, has it passed the second factor, and when was it last seen.
 *
 * <p>All three come from the same {@code session} row, so this reads it once. Three
 * separate filters would have been three lookups on the hottest path in the panel, and
 * three places for the definition of "live" to drift apart.
 *
 * <h2>Why the second factor is enforced here and not by the URL map</h2>
 *
 * <p>A session that has passed the password step but not the TOTP step is
 * <em>authenticated</em> as far as Spring Security is concerned, so
 * {@code anyRequest().authenticated()} lets it everywhere. The gate has to be a filter
 * that runs before {@code AuthorizationFilter} and turns it back. Putting the rule in
 * {@code SecurityConfig} instead would have meant enumerating every protected path there,
 * and the one that got forgotten would be reachable with half a sign-in.
 *
 * <h2>Ordering</h2>
 *
 * <p>Registered before {@code AuthorizationFilter} by {@link AuthSecurityContribution}.
 * It has to run after the security context has been restored - it reads the principal -
 * and before authorization, which is exactly the window between
 * {@code SecurityContextHolderFilter} and {@code AuthorizationFilter}.
 *
 * <p>Deliberately not a {@code @Component}. Spring Boot registers every {@code Filter}
 * bean with the servlet container mapped to {@code /*}, so a filter that is both a bean
 * and part of the security chain runs twice per request - once outside the chain, where
 * the security context has not been restored yet and the redirect it issues comes from
 * nowhere. {@link AuthSecurityContribution} constructs it instead.
 */
public class SessionGateFilter extends OncePerRequestFilter {

    /**
     * Paths this filter never touches.
     *
     * <p>Static assets and the error page, because redirecting a stylesheet request to
     * the sign-in form produces a page with no styling and no explanation.
     * {@code /api/} because a token call has no {@code HttpSession} and no session row -
     * {@link ApiTokenAuthenticationFilter} owns that surface.
     */
    private static final String[] UNGATED_PREFIXES = {
        "/assets/", "/api/", "/webhooks/", "/dist/", "/health", "/error", "/favicon.ico",
        "/install.sh"
    };

    /** What a session with an outstanding challenge may still reach. */
    private static final String[] CHALLENGE_ALLOWED_PREFIXES = {"/login", "/logout"};

    private final SessionRepository sessions;
    private final AuthSettings settings;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public SessionGateFilter(SessionRepository sessions, AuthSettings settings) {
        this.sessions = sessions;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : UNGATED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        HttpSession httpSession = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (httpSession == null || authentication == null
                || !(authentication.getPrincipal() instanceof SignedInAccount)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Session> found =
                sessions.findBySessionIdHash(TokenDigest.of(httpSession.getId()));
        if (found.isEmpty()) {
            /*
             * Authenticated with no row. That is the sign-in request itself, which
             * authenticates and writes the row in the same exchange, and it is also what
             * a container session that outlived its row looks like. Letting it through is
             * safe: without a row there is nothing to revoke, and the next sign-in writes
             * one.
             */
            chain.doFilter(request, response);
            return;
        }

        Session session = found.get();
        Instant now = Instant.now();
        if (!session.isLiveAt(now)) {
            endSession(request, response, session);
            return;
        }
        if (!session.isSecondFactorSatisfied() && !isChallengeAllowed(request)) {
            redirectStrategy.sendRedirect(request, response,
                    SignInSuccessHandler.SECOND_FACTOR_PATH);
            return;
        }
        touchIfStale(session, now);
        chain.doFilter(request, response);
    }

    /**
     * Turns a revoked or expired row into an actual sign-out on this browser.
     *
     * <p>The reason travels in the query string so the sign-in page can say which of the
     * two it was - "your password was changed" and "you were signed out everywhere" are
     * different pieces of news, and one of them means somebody else did it.
     */
    private void endSession(HttpServletRequest request, HttpServletResponse response,
                            Session session) throws IOException {
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.invalidate();
        }
        SecurityContextHolder.clearContext();
        String reason = session.revokedReason() == null
                ? SessionRevocationReason.EXPIRED.name()
                : session.revokedReason().name();
        redirectStrategy.sendRedirect(request, response, "/login?ended=" + reason.toLowerCase());
    }

    /**
     * Moves {@code last_seen_at} forward, but not on every request.
     *
     * <p>Without the window a page made of six partial reloads would be six writes to one
     * row, and the value is used to order a list - a minute of staleness in it is
     * invisible.
     */
    private void touchIfStale(Session session, Instant now) {
        if (session.lastSeenAt().plus(settings.sessionTouchWindow()).isBefore(now)) {
            sessions.touch(session.id(), now);
        }
    }

    private static boolean isChallengeAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : CHALLENGE_ALLOWED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
