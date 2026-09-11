package lhqm.furimeo.wisper.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Takes an uploaded zip off the request and parks it under {@code wisper.storage.root}.
 *
 * <p>Panel-side and temporary. The bytes that matter end up on the node, pushed there by
 * {@link PushDeploymentArchive} when a builder is chosen; this copy exists because the
 * upload and the build are separated by a queue, and holding a multipart stream open
 * across a job boundary is not a thing that works.
 *
 * <h2>What is checked, and why here</h2>
 *
 * <ul>
 * <li><strong>The size</strong>, against {@code wisper.deploy.max-archive-size}, counted
 *     while writing rather than trusted from a header. A client can claim any length.</li>
 * <li><strong>The first four bytes</strong>, which must be a local file header. Refusing
 *     a file that is not a zip at the form is a sentence the customer can act on; letting
 *     it through is a build that fails on a node in three minutes' time.</li>
 * <li><strong>The checksum</strong>, computed on the way past. The node re-checks it
 *     before unpacking, so a transfer that corrupted in the middle fails loudly instead
 *     of publishing a site with half a file in it.</li>
 * </ul>
 *
 * <p>Names are generated, never taken from the upload. A filename off a browser is
 * attacker-controlled, and this is the code path where {@code ../../etc} would matter.
 */
@Component
public class StoreDeploymentArchive {

    private static final Logger log = LoggerFactory.getLogger(StoreDeploymentArchive.class);

    /** {@code PK\003\004}: the local file header every zip starts with. */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** Where under the storage root these live. One directory, so a sweep is one listing. */
    private static final String FOLDER = "deploy-archives";

    private final Path root;
    private final DeploySettings settings;

    public StoreDeploymentArchive(@Value("${wisper.storage.root}") Path storageRoot,
                                  DeploySettings settings) {
        this.root = storageRoot.toAbsolutePath().normalize().resolve(FOLDER);
        this.settings = settings;
    }

    /**
     * Copies the upload to disk and returns where it went.
     *
     * <p>Written to a {@code .part} file and moved into place at the end, so a failed or
     * abandoned upload never leaves something that looks like a complete archive.
     *
     * @throws RequestRejected  if it is empty, too large, or not a zip
     * @throws UncheckedIOException if the panel's own disk refused it. Not a
     *         {@code RequestRejected}: nothing the customer did caused it and no advice
     *         would help
     */
    public StoredArchive store(UUID serviceId, InputStream upload) {
        Path directory = root.resolve(serviceId.toString());
        String name = UUID.randomUUID() + ".zip";
        Path target = directory.resolve(name);
        Path partial = directory.resolve(name + ".part");
        long limit = settings.maxArchiveSize().toBytes();

        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            byte[] first = new byte[ZIP_MAGIC.length];
            int firstFilled = 0;

            try (OutputStream out = Files.newOutputStream(partial)) {
                int read;
                while ((read = upload.read(buffer)) > 0) {
                    written += read;
                    if (written > limit) {
                        throw new RequestRejected("archive", "That archive is larger than the "
                                + limit / (1024 * 1024) + " MB this platform accepts.");
                    }
                    // The magic number can straddle two reads on a slow connection, so
                    // it is copied from this read's own offset rather than from the
                    // running total.
                    for (int i = 0; firstFilled < first.length && i < read; i++) {
                        first[firstFilled++] = buffer[i];
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (written == 0) {
                throw new RequestRejected("archive", "That file is empty.");
            }
            requireZip(first, firstFilled);

            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredArchive(serviceId + "/" + name,
                    HexFormat.of().formatHex(digest.digest()), written);
        } catch (RequestRejected refused) {
            deleteQuietly(partial);
            throw refused;
        } catch (IOException failed) {
            deleteQuietly(partial);
            throw new UncheckedIOException("Could not store the uploaded archive", failed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * The absolute path of a stored archive, checked to be inside the storage root.
     *
     * <p>The value comes from the panel's own {@code deployment.archive_path} column, so
     * it should always be inside. "Should" is not a check, and this is the one place that
     * turns a database value into a file handle.
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Archive path \"" + relativePath + "\" resolves outside the storage root");
        }
        return resolved;
    }

    /**
     * Re-reads a stored archive to answer how big it is and what it hashes to.
     *
     * <p>The upload and the build are separated by a queue and, usually, by a panel
     * restart or two, so the figures computed when the bytes arrived are not in memory by
     * the time a node asks for them. Hashing a file on local disk is cheap next to
     * sending it over a tunnel, and re-reading it is also what catches an archive that
     * was corrupted or truncated while it sat there.
     *
     * @throws UncheckedIOException if it is no longer on disk, which is what a build
     *         needs to be told rather than to discover as an empty upload
     */
    public StoredArchive inspect(String relativePath) {
        Path file = resolve(relativePath);
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                size += read;
                digest.update(buffer, 0, read);
            }
            return new StoredArchive(relativePath, HexFormat.of().formatHex(digest.digest()),
                    size);
        } catch (IOException gone) {
            throw new UncheckedIOException(
                    "The uploaded archive " + relativePath + " is no longer readable", gone);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * Deletes the panel's copy once the node has it, or once the deployment is over.
     *
     * <p>Never throws. This runs after the deployment has already been decided, and
     * failing a deployment that worked because a temporary file would not delete would be
     * absurd; the sweep in {@link PruneOldReleases} picks up what is left behind.
     */
    public void discard(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            deleteQuietly(resolve(relativePath));
        } catch (IllegalArgumentException outside) {
            log.warn("Refusing to delete archive path outside the storage root: {}",
                    relativePath);
        }
    }

    private static void requireZip(byte[] first, int filled) {
        if (filled < ZIP_MAGIC.length) {
            throw new RequestRejected("archive", "That file is too short to be a zip archive.");
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (first[i] != ZIP_MAGIC[i]) {
                throw new RequestRejected("archive",
                        "That does not look like a zip archive. Upload the built site as a .zip.");
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            log.warn("Could not delete {}: {}", path, leftBehind.getMessage());
        }
    }
}
