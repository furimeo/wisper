package lhqm.furimeo.wisper.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a Vite dev server is running, and where.
 *
 * <p>Detected from a file the dev server writes while it is alive rather than from a
 * configuration flag, because a flag has to be remembered twice - set when starting to
 * work on the client and unset afterwards - and forgetting the second half means
 * staring at a stale bundle wondering why nothing changes.
 *
 * <p>The check is skipped entirely when the application is running from a jar. That is
 * the production case, where there is no dev server and never will be, and it keeps a
 * filesystem stat off the request path.
 */
public class ViteDevServer {

    /** Written by the hotFile() plugin in frontend/vite.config.ts. */
    private static final Path HOT_FILE = Path.of("build", "frontend-dev", "hot");

    /** How long the hot file's contents are trusted before it is read again. */
    private static final long CACHE_MILLIS = 1000;

    private final boolean possible;

    private volatile String origin;
    private volatile long checkedAt;

    public ViteDevServer() {
        this.possible = !runningFromJar();
    }

    /**
     * @return the dev server's origin, for example {@code http://127.0.0.1:5173}, or
     *         null when the packaged bundle should be served instead
     */
    public String origin() {
        if (!possible) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt > CACHE_MILLIS) {
            origin = read();
            checkedAt = now;
        }
        return origin;
    }

    public boolean isRunning() {
        return origin() != null;
    }

    private static String read() {
        try {
            if (!Files.isRegularFile(HOT_FILE)) {
                return null;
            }
            String contents = Files.readString(HOT_FILE).strip();
            return contents.isEmpty() ? null : contents;
        } catch (IOException e) {
            // The dev server deleting the file mid-read is normal, not an error.
            return null;
        }
    }

    private static boolean runningFromJar() {
        var source = ViteDevServer.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return true;
        }
        String location = source.getLocation().toString();
        return location.endsWith(".jar") || location.startsWith("jar:");
    }
}
