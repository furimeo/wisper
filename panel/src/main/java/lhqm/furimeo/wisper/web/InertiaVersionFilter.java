package lhqm.furimeo.wisper.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Forces a full page load when the client is running a bundle from a previous build.
 *
 * <p>Without this, someone who left a tab open across a deploy keeps making requests
 * with old JavaScript against new props, and the failures look like random rendering
 * bugs. Inertia's answer is a 409 carrying the location to reload, which the client
 * turns into a hard navigation.
 *
 * <p>Only GET is checked. Answering 409 to a POST would silently discard whatever the
 * customer just submitted; the redirect that follows the POST is where the stale
 * version gets noticed instead, one navigation later and with nothing lost.
 */
public class InertiaVersionFilter extends OncePerRequestFilter {

    private final ViteManifest manifest;

    public InertiaVersionFilter(ViteManifest manifest) {
        this.manifest = manifest;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientVersion = request.getHeader(InertiaHeaders.VERSION);
        boolean stale = InertiaHeaders.isInertiaRequest(request)
                && "GET".equals(request.getMethod())
                && clientVersion != null
                && !clientVersion.equals(manifest.version());

        if (stale) {
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setHeader(InertiaHeaders.LOCATION, request.getRequestURI());
            return;
        }

        chain.doFilter(request, response);
    }
}
