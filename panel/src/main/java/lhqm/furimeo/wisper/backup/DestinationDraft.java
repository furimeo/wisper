package lhqm.furimeo.wisper.backup;

/**
 * What the destination form submits.
 *
 * <p>Ten fields, so a record rather than ten parameters: a method whose signature is ten
 * strings is a method somebody eventually calls with {@code region} and {@code bucket} the
 * wrong way round, and the compiler cannot help them.
 *
 * <p>{@link #secretAccessKey} arrives in the clear - it has just been typed - and is
 * encrypted before anything is written. {@link #toString()} is overridden for the same
 * reason it is on {@link DestinationCredentials}: a record prints every component, and a
 * binding failure or a debug line would otherwise put the key in a log.
 *
 * @param secretAccessKey blank on an edit means "keep the key that is already stored". A
 *                        form that had to re-type a 40-character secret to change a bucket
 *                        name is a form people paste the wrong thing into
 */
public record DestinationDraft(
        String name,
        DestinationKind kind,
        String endpoint,
        String region,
        String bucket,
        String pathPrefix,
        String accessKeyId,
        String secretAccessKey,
        String storageClass,
        String localPath) {

    public DestinationDraft {
        name = strip(name);
        endpoint = strip(endpoint);
        region = strip(region);
        bucket = strip(bucket);
        pathPrefix = strip(pathPrefix);
        accessKeyId = strip(accessKeyId);
        secretAccessKey = secretAccessKey == null ? "" : secretAccessKey;
        storageClass = strip(storageClass);
        localPath = strip(localPath);
    }

    /** Whether the customer typed a new secret, as opposed to leaving the old one alone. */
    public boolean hasNewSecret() {
        return !secretAccessKey.isBlank();
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** Everything except the secret key. */
    @Override
    public String toString() {
        return "DestinationDraft[name=" + name + ", kind=" + kind + ", endpoint=" + endpoint
                + ", region=" + region + ", bucket=" + bucket + ", pathPrefix=" + pathPrefix
                + ", accessKeyId=" + accessKeyId + ", secretAccessKey=<redacted>"
                + ", storageClass=" + storageClass + ", localPath=" + localPath + "]";
    }
}
