package lhqm.furimeo.wisper.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.View;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders one page as an Inertia response.
 *
 * <p>The protocol is small enough to implement directly, which is why there is no
 * adapter dependency: a first visit gets an HTML shell carrying the page object in a
 * JSON script tag, and every navigation after that is the page object on its own. Both
 * come from the same controller, the same model and the same Spring Security rules -
 * which is the whole reason for choosing Inertia over a separate JSON API. An API would
 * have meant a second copy of every authorization decision, on the client, where it can
 * be read.
 */
class InertiaPage implements View {

    private final String component;
    private final ObjectMapper objectMapper;
    private final ViteManifest manifest;
    private final ViteDevServer devServer;
    private final SharedProps sharedProps;

    InertiaPage(String component, ObjectMapper objectMapper, ViteManifest manifest,
                ViteDevServer devServer, SharedProps sharedProps) {
        this.component = component;
        this.objectMapper = objectMapper;
        this.manifest = manifest;
        this.devServer = devServer;
        this.sharedProps = sharedProps;
    }

    @Override
    public String getContentType() {
        return MediaType.TEXT_HTML_VALUE;
    }

    @Override
    public void render(Map<String, ?> model, HttpServletRequest request,
                       HttpServletResponse response) throws Exception {

        Map<String, Object> props = sharedProps.forRequest(request);
        if (model != null) {
            model.forEach((key, value) -> {
                // Spring puts its own machinery in the model; none of it is page data.
                if (!key.startsWith("org.springframework") && !"view".equals(key)) {
                    props.put(key, value);
                }
            });
        }
        applyPartialReload(request, props);

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("component", component);
        page.put("props", props);
        page.put("url", fullPath(request));
        page.put("version", manifest.version());

        // The same URL answers with HTML or with JSON depending on this header, so any
        // cache in front of the panel has to key on it.
        response.setHeader(HttpHeaders.VARY, InertiaHeaders.INERTIA);

        if (InertiaHeaders.isInertiaRequest(request)) {
            response.setHeader(InertiaHeaders.INERTIA, "true");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), page);
            return;
        }

        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(shell(objectMapper.writeValueAsString(page)));
    }

    /**
     * Narrows the props to what a partial reload asked for.
     *
     * <p>This is what makes "refresh just the deployment log" cost one small response
     * instead of rebuilding the whole page's data. The component name is checked because
     * a partial request that lands on a different component after a redirect must get
     * the full set - otherwise the new page renders with three of its props missing.
     */
    private void applyPartialReload(HttpServletRequest request, Map<String, Object> props) {
        if (!component.equals(request.getHeader(InertiaHeaders.PARTIAL_COMPONENT))) {
            return;
        }
        Set<String> only = names(request.getHeader(InertiaHeaders.PARTIAL_DATA));
        Set<String> except = names(request.getHeader(InertiaHeaders.PARTIAL_EXCEPT));
        if (!only.isEmpty()) {
            props.keySet().removeIf(key -> !only.contains(key));
        }
        props.keySet().removeAll(except);
    }

    private static Set<String> names(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        Arrays.stream(header.split(",")).map(String::strip).filter(name -> !name.isEmpty()).forEach(names::add);
        return names;
    }

    /** The path the client should consider itself on, query string included. */
    private static String fullPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null ? uri : uri + "?" + query;
    }

    /**
     * The only HTML the server writes.
     *
     * <p>Mobile-first, so the viewport meta is not optional and {@code viewport-fit} is
     * set for phones with a notch - the terminal's extra key bar sits at the bottom of
     * the screen and would otherwise be under the home indicator. The theme is resolved
     * before React mounts so a reader on a dark theme never sees a white flash.
     */
    private String shell(String pageJson) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, ")
            .append("viewport-fit=cover\">\n")
            .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
            .append("<link rel=\"icon\" href=\"/assets/img/favicon.svg\" type=\"image/svg+xml\">\n")
            .append("<title>wisper</title>\n")
            .append("<script>").append(THEME_BOOTSTRAP).append("</script>\n");

        if (devServer.isRunning()) {
            /*
             * Development. The URLs stay same-origin and ViteDevProxyFilter forwards
             * them, so the browser never learns the dev server exists. No stylesheet
             * links: Vite injects CSS through the module graph in this mode, which is
             * what makes an edit to a stylesheet apply without a reload.
             *
             * The preamble has to come first. Vite normally injects it while
             * transforming an HTML entry, and this response is not one - without it
             * every component fails to load with "can't detect preamble".
             */
            html.append(REACT_REFRESH_PREAMBLE)
                .append("<script type=\"module\" src=\"/assets/app/@vite/client\"></script>\n")
                .append("<script type=\"module\" src=\"/assets/app/src/main.tsx\"></script>\n");
        } else {
            for (String href : manifest.stylesheetUrls()) {
                html.append("<link rel=\"stylesheet\" href=\"").append(href).append("\">\n");
            }
            if (manifest.isBuilt()) {
                html.append("<script type=\"module\" src=\"").append(manifest.scriptUrl())
                    .append("\" defer></script>\n");
            }
        }

        /*
         * Inertia 3 reads the page object from a JSON script tag keyed by the root
         * element's id, not from a data attribute on the element. Writing the attribute
         * form mounts nothing at all: a blank page, and a null dereference in the
         * console as the only clue.
         */
        html.append("</head>\n<body>\n")
            .append("<script data-page=\"app\" type=\"application/json\">")
            .append(escapeForScript(pageJson))
            .append("</script>\n")
            .append("<div id=\"app\"></div>\n");

        if (!manifest.isBuilt() && !devServer.isRunning()) {
            html.append("<noscript>The client bundle is missing. Run `npm run dev` in "
                    + "panel/frontend, or build with `./gradlew bootJar`.</noscript>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /**
     * Makes JSON safe inside a script element.
     *
     * <p>A script element's content is not HTML-escaped by the parser, so entity
     * escaping would corrupt the JSON. The only sequence that can end the element early
     * is a literal "&lt;", and writing it as a JSON unicode escape leaves the parsed
     * value identical while making "&lt;/script&gt;" impossible to produce.
     *
     * <p>The replacement is written with a doubled backslash on purpose: Java expands a
     * unicode escape in the source before tokenising, even inside a string literal, so
     * the single-backslash form would compile to "&lt;" and replace a character with
     * itself.
     */
    private static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    /**
     * React Fast Refresh's bootstrap, matching what @vitejs/plugin-react injects into an
     * HTML entry. Only meaningful while the dev server is running.
     */
    private static final String REACT_REFRESH_PREAMBLE = """
            <script type="module">
            import RefreshRuntime from "/assets/app/@react-refresh"
            RefreshRuntime.injectIntoGlobalHook(window)
            window.$RefreshReg$ = () => {}
            window.$RefreshSig$ = () => (type) => type
            window.__vite_plugin_react_preamble_installed__ = true
            </script>
            """;

    /** Resolves light/dark before first paint. Mirrors the `dark` variant in styles.css. */
    private static final String THEME_BOOTSTRAP = """
            (function(){var p="system";try{p=localStorage.getItem("wisper-theme")||"system"}catch(e){}\
            var d=p==="dark"||(p==="system"&&window.matchMedia("(prefers-color-scheme: dark)").matches);\
            document.documentElement.classList.toggle("dark",d);})();""";
}
