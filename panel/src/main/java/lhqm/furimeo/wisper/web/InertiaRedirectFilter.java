package lhqm.furimeo.wisper.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Answers a redirect after PUT, PATCH or DELETE with 303 instead of 302.
 *
 * <p>The Inertia client follows redirects with the browser's own fetch, and fetch
 * repeats the original method on a 302. So a successful {@code DELETE /services/7}
 * that redirects to the service list turns into {@code DELETE /services} - which either
 * 405s or, far worse, deletes something. 303 is defined as "follow this with GET", and
 * it is the only status that makes the pattern safe.
 *
 * <p>Spring's {@code redirect:} produces 302 through {@code sendRedirect}, so the
 * correction happens here rather than in forty controllers that would each have to
 * remember. POST redirects are left alone: fetch already downgrades those to GET.
 */
public class InertiaRedirectFilter extends OncePerRequestFilter {

    private static final Set<String> NEEDS_SEE_OTHER = Set.of("PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !NEEDS_SEE_OTHER.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, new SeeOtherResponse(response));
    }

    private static final class SeeOtherResponse extends HttpServletResponseWrapper {

        private SeeOtherResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            setStatus(HttpStatus.SEE_OTHER.value());
            setHeader(HttpHeaders.LOCATION, location);
            flushBuffer();
        }

        @Override
        public void setStatus(int statusCode) {
            super.setStatus(statusCode == HttpStatus.FOUND.value()
                    ? HttpStatus.SEE_OTHER.value()
                    : statusCode);
        }
    }
}
