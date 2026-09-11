package lhqm.furimeo.wisper.backup;

/**
 * A destination with its secret decrypted, on its way to a node or to a reachability
 * check. It exists for the length of one call and is never stored.
 *
 * <p>{@link #toString()} is overridden and the test that asserts so is not decoration. A
 * record prints every component, so one {@code log.debug("sending {}", credentials)} - or
 * an exception message built from a record, or a stack trace from a library that formats
 * its arguments - would put a customer's object-store secret key in a log file. This is the
 * only object in the panel that holds one in the clear.
 *
 * @param prefix      key prefix inside the bucket. The node appends its own object name
 * @param localPrefix for a {@link DestinationKind#LOCAL} destination, the directory
 *                    <em>relative to the node's backup root</em>, which is what
 *                    {@code BackupDestination.local_prefix} carries. Never absolute: no
 *                    path in this contract is, so that a node resolving one cannot be
 *                    talked out of its own state directory
 */
public record DestinationCredentials(
        DestinationKind kind,
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String accessKeyId,
        String secretAccessKey,
        boolean pathStyle,
        String serverSideEncryption,
        String localPrefix) {

    /**
     * The node's backup directory, which every local destination is resolved under.
     *
     * <p>A sibling of {@code /var/lib/wisper/volumes} and {@code /var/lib/wisper/sites},
     * both fixed by design §11.1. An operator types an absolute path because that is what a
     * path looks like; anything at or under this root is sent as the part after it, and
     * anything outside it is sent with its leading slash removed and lands under this root
     * anyway. The node is the side that resolves and range-checks it.
     */
    public static final String NODE_BACKUP_ROOT = "/var/lib/wisper/backups";

    public DestinationCredentials {
        endpoint = orEmpty(endpoint);
        region = orEmpty(region);
        bucket = orEmpty(bucket);
        prefix = orEmpty(prefix);
        accessKeyId = orEmpty(accessKeyId);
        secretAccessKey = orEmpty(secretAccessKey);
        serverSideEncryption = orEmpty(serverSideEncryption);
        localPrefix = orEmpty(localPrefix);
    }

    /** Whether the object store is being asked to encrypt what it stores. */
    public boolean isEncryptedAtRest() {
        return kind == DestinationKind.S3 && !serverSideEncryption.isEmpty();
    }

    /**
     * Turns the absolute path an operator typed into the relative prefix the wire carries.
     *
     * <p>{@code /var/lib/wisper/backups/nightly} becomes {@code nightly}. A path outside
     * the root loses its leading slash and lands under the root regardless, which is the
     * behaviour the form tells the operator about rather than a surprise: the alternative
     * is letting a panel-side string decide where a node writes.
     */
    public static String localPrefixOf(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return "";
        }
        String trimmed = localPath.strip();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.equals(NODE_BACKUP_ROOT)) {
            return "";
        }
        if (trimmed.startsWith(NODE_BACKUP_ROOT + "/")) {
            trimmed = trimmed.substring(NODE_BACKUP_ROOT.length() + 1);
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Everything except the secret key. */
    @Override
    public String toString() {
        return "DestinationCredentials[kind=" + kind + ", endpoint=" + endpoint
                + ", region=" + region + ", bucket=" + bucket + ", prefix=" + prefix
                + ", accessKeyId=" + accessKeyId + ", secretAccessKey=<redacted>"
                + ", pathStyle=" + pathStyle
                + ", serverSideEncryption=" + serverSideEncryption
                + ", localPrefix=" + localPrefix + "]";
    }
}
