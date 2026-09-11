package lhqm.furimeo.wisper.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Vite's build manifest so the HTML shell can name the hashed asset files, and
 * doubles as Inertia's asset version.
 *
 * <p>The alternative to a manifest is a fixed filename with no content hash, which
 * means choosing between a stale bundle after every deploy and no caching at all. The
 * entry's hashed filename changes whenever anything it imports changes, which is
 * exactly the semantics Inertia's version check wants: a client holding an older hash
 * is holding an older bundle.
 */
public class ViteManifest {

    private static final String MANIFEST = "static/app/.vite/manifest.json";

    /** Matches the Vite entry, which is frontend/index.html. */
    private static final String ENTRY = "index.html";

    /** Matches `base` in vite.config.ts and the resource handler in WebMvcConfig. */
    private static final String BASE = "/assets/app/";

    private final String scriptUrl;
    private final List<String> stylesheetUrls;
    private final String version;

    public ViteManifest(ObjectMapper objectMapper) {
        String script = null;
        List<String> styles = new ArrayList<>();
        String hash = "dev";

        ClassPathResource resource = new ClassPathResource(MANIFEST);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                JsonNode entry = root.get(ENTRY);
                if (entry != null) {
                    script = BASE + entry.path("file").asString();
                    collectCss(root, entry, styles, new LinkedHashSet<>());
                    hash = entry.path("file").asString();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unreadable Vite manifest at " + MANIFEST, e);
            }
        }

        this.scriptUrl = script;
        this.stylesheetUrls = List.copyOf(styles);
        this.version = hash;
    }

    /** Walks the entry's imports so a stylesheet pulled in by a lazy chunk is still linked. */
    private static void collectCss(JsonNode root, JsonNode node, List<String> out, Set<String> seen) {
        for (JsonNode css : node.path("css")) {
            String url = BASE + css.asString();
            if (!out.contains(url)) {
                out.add(url);
            }
        }
        for (JsonNode importName : node.path("imports")) {
            String name = importName.asString();
            if (seen.add(name)) {
                JsonNode imported = root.get(name);
                if (imported != null) {
                    collectCss(root, imported, out, seen);
                }
            }
        }
    }

    /**
     * @return true when a client bundle was built into this jar; false during a
     *         backend-only build, where the shell says so rather than rendering nothing
     */
    public boolean isBuilt() {
        return scriptUrl != null;
    }

    public String scriptUrl() {
        return scriptUrl;
    }

    public List<String> stylesheetUrls() {
        return stylesheetUrls;
    }

    public String version() {
        return version;
    }
}
