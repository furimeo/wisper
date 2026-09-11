package lhqm.furimeo.wisper.deploy;

/**
 * A zip the panel has taken off a browser and parked, on its way to a node.
 *
 * <p>The path is <strong>relative to {@code wisper.storage.root}</strong>, never absolute.
 * A row holding an absolute path stops being true the moment an operator moves the data
 * directory, and it is exactly the kind of value that then gets concatenated into
 * something that opens a file.
 *
 * @param relativePath forward-slashed, {@code <service-id>/<upload-id>.zip}
 * @param sha256       hex, lower case. Recomputed by the node before it unpacks, so a
 *                     corrupt transfer fails loudly instead of producing a plausible site
 * @param sizeBytes    what actually landed on disk, not what the browser claimed
 */
public record StoredArchive(String relativePath, String sha256, long sizeBytes) {

    public StoredArchive {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("A stored archive needs a path");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "An archive checksum is 64 lower-case hex characters");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("An empty archive has nothing to deploy");
        }
    }

    /** The name the archive is given in the node's staging root. */
    public String fileName() {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
