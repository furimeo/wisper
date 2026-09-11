package lhqm.furimeo.wisper.backup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The rules a destination has to satisfy before a row exists, in one place because the
 * create form and the edit form both need every one of them.
 *
 * <p>Each rule mirrors a CHECK constraint in V26 - {@code backup_destination_s3_complete},
 * {@code backup_destination_local_complete},
 * {@code backup_destination_local_path_absolute} - with a message that names the input
 * instead of the constraint. The database is still the thing that guarantees them; this is
 * what turns a guarantee into a sentence under a field.
 */
@Component
public class ValidateDestinationDraft {

    /** Directories a backup must never be written into, whatever an operator types. */
    private static final List<String> FORBIDDEN_LOCAL_PATHS =
            List.of("/", "/etc", "/proc", "/sys", "/dev", "/usr", "/bin", "/sbin", "/lib",
                    "/boot", "/root");

    /**
     * @param secretRequired true when creating, false when editing - where a blank secret
     *                       means "keep the stored one"
     * @throws RequestRejected naming the field at fault
     */
    public void check(DestinationDraft draft, boolean secretRequired) {
        if (draft.name().isEmpty()) {
            throw new RequestRejected("name", "Give this destination a name.");
        }
        if (draft.name().length() > 100) {
            throw new RequestRejected("name", "That name is longer than 100 characters.");
        }
        if (draft.kind() == null) {
            throw new RequestRejected("kind", "Choose S3 or a directory on the node.");
        }
        if (draft.kind() == DestinationKind.LOCAL) {
            requireLocalPath(draft.localPath());
            return;
        }
        checkS3(draft, secretRequired);
    }

    private void checkS3(DestinationDraft draft, boolean secretRequired) {
        if (draft.endpoint().isEmpty()) {
            throw new RequestRejected("endpoint",
                    "Give the store's URL, like https://s3.eu-central-1.amazonaws.com.");
        }
        URI endpoint;
        try {
            endpoint = new URI(draft.endpoint());
        } catch (URISyntaxException malformed) {
            throw new RequestRejected("endpoint", "\"" + draft.endpoint() + "\" is not a URL.");
        }
        String scheme = endpoint.getScheme() == null
                ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RequestRejected("endpoint",
                    "The endpoint has to start with http:// or https://.");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new RequestRejected("endpoint", "The endpoint has no host in it.");
        }
        if (endpoint.getPath() != null && !endpoint.getPath().isEmpty()
                && !endpoint.getPath().equals("/")) {
            // The bucket and the key prefix are separate fields, and a path here would be
            // signed as part of neither - producing SignatureDoesNotMatch at the first
            // backup rather than here.
            throw new RequestRejected("endpoint", "Leave the path off the endpoint; the bucket "
                    + "and the prefix are separate fields.");
        }
        if (draft.bucket().isEmpty()) {
            throw new RequestRejected("bucket", "Which bucket?");
        }
        if (!draft.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new RequestRejected("bucket", "A bucket name is 3 to 63 characters of lower "
                    + "case letters, digits, dots and dashes.");
        }
        if (draft.pathPrefix().startsWith("/") || draft.pathPrefix().contains("..")) {
            throw new RequestRejected("pathPrefix", "A prefix is a key inside the bucket: no "
                    + "leading slash and no \"..\".");
        }
        if (draft.accessKeyId().isEmpty()) {
            throw new RequestRejected("accessKeyId", "The access key id is missing.");
        }
        if (secretRequired && !draft.hasNewSecret()) {
            throw new RequestRejected("secretAccessKey", "The secret access key is missing.");
        }
        if (!draft.storageClass().isEmpty()
                && !draft.storageClass().matches("[A-Z0-9_]{1,32}")) {
            throw new RequestRejected("storageClass", "A storage class is upper case, like "
                    + "STANDARD or GLACIER_IR.");
        }
    }

    /**
     * The absolute directory a local destination names.
     *
     * <p>Absolute because {@code backup_destination_local_path_absolute} says so, and
     * because a relative path in this column would be resolved against whatever a node
     * happens to consider its working directory. What travels to the node is the part after
     * {@link DestinationCredentials#NODE_BACKUP_ROOT}, and the node range-checks it again -
     * a path that arrives over a network and reaches a filesystem call is the bug class
     * this platform is built to avoid.
     *
     * @return the path, stripped
     * @throws RequestRejected naming {@code localPath}
     */
    public String requireLocalPath(String localPath) {
        String path = localPath == null ? "" : localPath.strip();
        if (path.isEmpty()) {
            throw new RequestRejected("localPath",
                    "Give an absolute directory, such as " + DestinationCredentials.NODE_BACKUP_ROOT
                            + "/nightly.");
        }
        if (!path.startsWith("/")) {
            throw new RequestRejected("localPath", "That has to be an absolute path.");
        }
        if (path.contains("..")) {
            throw new RequestRejected("localPath", "A path with \"..\" in it is refused.");
        }
        String withoutTrailingSlash = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        if (FORBIDDEN_LOCAL_PATHS.contains(withoutTrailingSlash)) {
            throw new RequestRejected("localPath",
                    withoutTrailingSlash + " is a system directory. Backups go under "
                            + DestinationCredentials.NODE_BACKUP_ROOT + ".");
        }
        if (DestinationCredentials.localPrefixOf(withoutTrailingSlash).isEmpty()) {
            throw new RequestRejected("localPath", "Name a directory inside "
                    + DestinationCredentials.NODE_BACKUP_ROOT
                    + ", so two destinations cannot write over each other.");
        }
        return withoutTrailingSlash;
    }
}
