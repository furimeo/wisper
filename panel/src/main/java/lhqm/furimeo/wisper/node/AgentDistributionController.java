package lhqm.furimeo.wisper.node;

import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Serves the published sasayaki binaries and the manifest that lists their checksums.
 *
 * <p>Public, like {@code /install.sh}, and for the same reason: a machine being installed
 * has no credentials yet, and these are artifacts rather than secrets. What makes the
 * download trustworthy is not who fetched it but the SHA-256 it is checked against - which
 * the installer already holds, baked into the script an operator verified separately, and
 * which a node holds in the {@code UpgradeNode} command before it replaces its own binary.
 *
 * <h2>Path traversal</h2>
 *
 * <p>The file name is matched against the release pattern before it is ever turned into a
 * path, and the resolved path is then checked to still be inside the release directory. A
 * request for {@code ../../etc/shadow} fails the first check and never becomes a path at
 * all. Two checks rather than one because this is a public endpoint that maps a
 * caller-supplied string onto the filesystem, which is the shape of the largest class of
 * bug in this kind of software.
 */
@RestController
public class AgentDistributionController {

    private final ReadAgentReleases releases;

    public AgentDistributionController(ReadAgentReleases releases) {
        this.releases = releases;
    }

    /**
     * Everything published, with checksums.
     *
     * <p>For an offline install: an operator on a machine with no route to the panel
     * fetches this from somewhere that does, downloads the binary by hand and runs
     * {@code sasayaki enroll --token-file} (design §7.1).
     */
    @GetMapping(value = "/dist", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Manifest> manifest(HttpServletRequest request) {
        String base = PanelBaseUrl.of(request);
        List<Entry> entries = releases.all().stream()
                .map(release -> new Entry(release.version(), release.architecture(),
                        release.fileName(), release.sha256(), release.sizeBytes(),
                        release.downloadUrl(base)))
                .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new Manifest(releases.newestVersion().orElse(null), entries));
    }

    /** One binary. */
    @GetMapping("/dist/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        AgentRelease release = releases.byFileName(fileName)
                .orElseThrow(() -> new NotFoundException("No published binary called "
                        + fileName));
        return ResponseEntity.ok()
                // Immutable: a release file name contains its version, so the bytes behind
                // one never change. Replacing a binary in place under the same name would
                // be a mistake this header does not have to protect against, because the
                // checksum the installer holds would stop it.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + release.fileName() + "\"")
                .header("X-Wisper-Sha256", release.sha256())
                .contentLength(release.sizeBytes())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(release.path()));
    }

    /**
     * @param current the newest version published for any architecture, or null when
     *                nothing has been
     */
    public record Manifest(String current, List<Entry> releases) {
    }

    /** One published build. */
    public record Entry(String version, String architecture, String fileName, String sha256,
                        long sizeBytes, String url) {
    }
}
