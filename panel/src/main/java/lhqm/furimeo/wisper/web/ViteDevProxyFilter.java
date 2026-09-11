package lhqm.furimeo.wisper.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Forwards client asset requests to the Vite dev server while one is running.
 *
 * <p>Vite's own middleware mode embeds it in a Node HTTP server; the backend here is a
 * JVM, so this is the equivalent. The point is that the whole application stays on one
 * origin during development: no CORS to configure, no second address to remember, and
 * cookies and same-origin rules behave exactly as they do against the packaged jar.
 *
 * <p>Only paths under the client's base are proxied, so nothing the application serves
 * can be shadowed. With no dev server running the filter is one volatile read and the
 * request continues to the resource handler that serves the bundle from the jar.
 */
public class ViteDevProxyFilter extends OncePerRequestFilter {

    /** Matches `base` in vite.config.ts and the resource handler in WebMvcConfig. */
    private static final String PREFIX = "/assets/app/";

    /**
     * Headers the servlet container owns. Copying them through produces a response that
     * contradicts what is actually on the wire - most visibly a Content-Length that no
     * longer matches after the container re-encodes the body.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "keep-alive", "transfer-encoding",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");

    private final ViteDevServer devServer;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            /*
             * HTTP/1.1, explicitly. The default is HTTP/2, which over cleartext means
             * sending an h2c upgrade that Vite does not answer - the connection is
             * accepted and then goes nowhere, so every module request fails with an
             * IOException that reads like the server is down.
             */
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ViteDevProxyFilter(ViteDevServer devServer) {
        this.devServer = devServer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = devServer.origin();
        if (origin == null) {
            chain.doFilter(request, response);
            return;
        }

        String query = request.getQueryString();
        URI target = URI.create(origin + request.getRequestURI() + (query == null ? "" : "?" + query));

        HttpRequest.Builder forward = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .GET();
        // Vite answers 304 for an unchanged module, which is most of them after the
        // first load, so the conditional headers are worth passing through.
        copyIfPresent(request, forward, "if-none-match");
        copyIfPresent(request, forward, "if-modified-since");
        copyIfPresent(request, forward, "accept");

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(forward.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while proxying to the Vite dev server", e);
        } catch (IOException e) {
            /*
             * The hot file outlives a dev server that was killed hard enough to skip its
             * own cleanup. Saying so is far more useful than a stack trace about a
             * refused connection, and it names the one thing that fixes it.
             */
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write(
                    "The Vite dev server at " + origin + " is not answering.\n\n"
                    + "Start it with `npm run dev` in panel/frontend, or delete\n"
                    + "panel/build/frontend-dev/hot to go back to the packaged bundle.\n");
            return;
        }

        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    response.addHeader(name, value);
                }
            }
        });

        try (InputStream body = upstream.body()) {
            body.transferTo(response.getOutputStream());
        }
    }

    private static void copyIfPresent(HttpServletRequest request, HttpRequest.Builder builder,
                                      String header) {
        String value = request.getHeader(header);
        if (value != null) {
            builder.header(header, value);
        }
    }
}
