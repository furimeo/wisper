package lhqm.furimeo.wisper.web;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Makes sure the XSRF-TOKEN cookie exists before the first form is rendered.
 *
 * <p>Spring Security defers the CSRF token: the cookie is written only when something
 * actually reads the token. Nothing on a server-rendered page does, because there are
 * no hidden form fields any more - the Inertia client reads the cookie from JavaScript
 * and echoes it in a header. Without this filter that cookie never appears, and the
 * first write the customer attempts is rejected with a 403 whose message says nothing
 * about cookies.
 *
 * <p>Touching the token is the whole job. {@code getToken()} is what triggers the
 * repository to render it into the response.
 */
public class EagerCsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
