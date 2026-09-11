package lhqm.furimeo.wisper.node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lists the sasayaki binaries the panel is publishing, with their checksums.
 *
 * <p>The directory is the source of truth: drop a file in, and the install script, the
 * upgrade command and the node pages all start offering it. There is no separate manifest
 * to update, so there is no state in which the panel advertises a checksum for a binary
 * that is not there.
 *
 * <p>An empty or missing directory is a normal state and not an error. It means nothing
 * has been published yet, and the two endpoints that need a binary say so plainly rather
 * than serving an installer that will fail halfway through on a machine somebody is
 * watching.
 *
 * <h2>The checksum cache</h2>
 *
 * <p>Hashing a thirty-megabyte binary on every page load would be a self-inflicted denial
 * of service on {@code /install.sh}, which is public. Results are cached against the
 * file's size and modification time, so replacing a binary in place - the thing a release
 * script actually does - invalidates the entry without anybody having to remember to.
 */
@Component
public class ReadAgentReleases {

    private static final Logger log = LoggerFactory.getLogger(ReadAgentReleases.class);

    private final NodeSettings settings;
    private final ConcurrentHashMap<String, String> checksums = new ConcurrentHashMap<>();

    public ReadAgentReleases(NodeSettings settings) {
        this.settings = settings;
    }

    /** Everything published, newest first. Empty when nothing has been. */
    public List<AgentRelease> all() {
        Path directory = settings.releaseDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<AgentRelease> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)
                        || !AgentRelease.isReleaseFile(file.getFileName().toString())) {
                    continue;
                }
                AgentRelease release = describe(file);
                if (release != null) {
                    found.add(release);
                }
            }
        } catch (IOException unreadable) {
            log.error("Could not read the agent release directory {}: {}", directory,
                    unreadable.getMessage());
            return List.of();
        }
        found.sort(AgentRelease.NEWEST_FIRST);
        return List.copyOf(found);
    }

    /** The newest build for one GOARCH, which is what a node of that shape should run. */
    public Optional<AgentRelease> newestFor(String architecture) {
        return all().stream()
                .filter(release -> release.architecture().equals(architecture))
                .findFirst();
    }

    /**
     * The newest version published for any architecture.
     *
     * <p>What the fleet screen compares a node's {@code agent_version} against to decide
     * whether to offer an upgrade. Comparing per architecture would have a machine on an
     * architecture nobody has rebuilt for showing as up to date when it is not.
     */
    public Optional<String> newestVersion() {
        return all().stream().map(AgentRelease::version).findFirst();
    }

    /**
     * One release by file name, for serving it.
     *
     * <p>The name is matched against the release pattern before the path is built, so a
     * request for {@code ../../etc/shadow} does not match and never becomes a path at all.
     */
    public Optional<AgentRelease> byFileName(String fileName) {
        if (fileName == null || !AgentRelease.isReleaseFile(fileName)) {
            return Optional.empty();
        }
        Path file = settings.releaseDirectory().resolve(fileName).normalize();
        if (!file.startsWith(settings.releaseDirectory().normalize())
                || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(describe(file));
    }

    private AgentRelease describe(Path file) {
        try {
            long size = Files.size(file);
            String key = file.toAbsolutePath() + "|" + size + "|"
                    + Files.getLastModifiedTime(file).toMillis();
            String sha256 = checksums.computeIfAbsent(key, ignored -> hash(file));
            if (sha256 == null) {
                return null;
            }
            return AgentRelease.describe(file, sha256, size);
        } catch (IOException unreadable) {
            log.error("Could not read {}: {}", file, unreadable.getMessage());
            return null;
        }
    }

    /** Streams the file through SHA-256 so a large binary never lands in memory whole. */
    private static String hash(Path file) {
        try (InputStream raw = Files.newInputStream(file);
             DigestInputStream digesting =
                     new DigestInputStream(raw, MessageDigest.getInstance("SHA-256"))) {
            byte[] window = new byte[64 * 1024];
            while (digesting.read(window) != -1) {
                // The digest is updated as a side effect of reading.
            }
            return HexFormat.of().formatHex(digesting.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException failed) {
            log.error("Could not checksum {}: {}", file, failed.getMessage());
            return null;
        }
    }
}
