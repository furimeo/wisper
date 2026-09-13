package lhqm.furimeo.wisper.node;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves {@code /install.sh}.
 *
 * <p>Public, because it is fetched by {@code curl} on a machine that has no session and,
 * at that point, no credentials either. It is a public artifact; the bootstrap token is
 * what grants anything, and the token is not in here.
 *
 * <p>The script is rendered per request with this panel's URL and the checksum of every
 * binary currently published baked in (see {@link InstallScript}). That is what makes the
 * verification real: a script that fetched its checksum at run time would be checking a
 * download against a value from the same server.
 *
 * <p>{@code X-Wisper-Install-Sha256} carries the script's own hash, so an automated
 * fetch can check it without a second request - and the same value is on the node's page,
 * which is where a person compares it before running the thing as root (design §7.1).
 *
 * <p><strong>Never cached.</strong> Publishing a new binary changes the checksums inside
 * the script, and a proxy holding yesterday's copy would hand an operator a script that
 * fails its own verification.
 */
@RestController
public class InstallScriptController {

    /** So a client can verify the script without asking the panel a second time. */
    private static final String CHECKSUM_HEADER = "X-Wisper-Install-Sha256";

    private final ReadAgentReleases releases;
    private final NodeSettings settings;

    public InstallScriptController(ReadAgentReleases releases, NodeSettings settings) {
        this.releases = releases;
        this.settings = settings;
    }

    @GetMapping(value = "/install.sh", produces = "text/x-shellscript")
    public ResponseEntity<byte[]> script(HttpServletRequest request) {
        List<AgentRelease> published = releases.all();
        String panelBaseUrl = PanelBaseUrl.of(request);
        String dialEndpoint = settings.dialEndpointFor(hostOf(request));

        if (published.isEmpty()) {
            // A working endpoint that says the true thing, rather than a script that gets
            // halfway through on a machine somebody is watching and then cannot verify a
            // download it has already made.
            byte[] message = ("#!/bin/sh\n"
                    + "echo 'This panel has not published a sasayaki binary yet.' >&2\n"
                    + "echo 'Put one in the release directory and try again.' >&2\n"
                    + "exit 1\n").getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.valueOf("text/x-shellscript"))
                    .body(message);
        }

        String downloadBaseUrl = settings.downloadBaseFor(panelBaseUrl);
        InstallScript script = InstallScript.render(panelBaseUrl, downloadBaseUrl, dialEndpoint, published);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(CHECKSUM_HEADER, script.sha256())
                .contentLength(script.byteLength())
                .contentType(MediaType.valueOf("text/x-shellscript"))
                .body(script.text().getBytes(StandardCharsets.UTF_8));
    }

    /** The host the installer reached us on, for the dial endpoint's fallback. */
    private static String hostOf(HttpServletRequest request) {
        String host = request.getServerName();
        return host == null || host.isBlank() ? "localhost" : host;
    }
}
