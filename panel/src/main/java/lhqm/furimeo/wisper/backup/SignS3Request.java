package lhqm.furimeo.wisper.backup;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, for the one request this panel makes to an object store:
 * checking that a destination's bucket and credentials work before a customer schedules
 * backups against them.
 *
 * <p>Written out rather than pulled in. The AWS SDK is tens of megabytes of dependency, a
 * transitive Netty and an HTTP client this application already has, for a hundred lines of
 * HMAC. AGENTS.md §6 asks for a concrete reason for a dependency; "one signed GET" is not
 * one, and the algorithm is fully specified and does not change.
 *
 * <p>The signature is the same for every S3-compatible store - MinIO, Backblaze, Wasabi,
 * Ceph - which is exactly why they are all reachable through one code path.
 *
 * <h2>The recipe, and the four places it is easy to get wrong</h2>
 *
 * <ol>
 * <li>Header names are lower-cased and <strong>sorted</strong>, values trimmed. A store
 *     rejects the signature with no hint about which header was out of order.</li>
 * <li>Query parameters are sorted by name and RFC 3986 encoded, with {@code +} meaning a
 *     plus sign and not a space.</li>
 * <li>The payload hash is the hash of the empty string for a body-less request, not an
 *     empty string, and it goes in {@code x-amz-content-sha256} as well as in the canonical
 *     request.</li>
 * <li>The signing key is chained date, region, service, {@code aws4_request} - and the
 *     region has to be the one the bucket is actually in, which is why an empty region
 *     falls back to {@code us-east-1} rather than to the empty string.</li>
 * </ol>
 */
public final class SignS3Request {

    /** What the algorithm is called on the wire. */
    public static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /** The hash every body-less request carries. */
    public static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** What most S3-compatible stores expect when no region is configured. */
    public static final String DEFAULT_REGION = "us-east-1";

    private static final String SERVICE = "s3";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Digests and the signature are lower-case hex; the specification says so. */
    private static final HexFormat HEX = HexFormat.of();

    /** Percent escapes are upper-case hex; the specification says that too. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    private SignS3Request() {
    }

    /**
     * Signs a request and returns every header it has to be sent with.
     *
     * @param canonicalUri the absolute path, already in the form the store will see it -
     *                     {@code /} for a bucket in virtual-hosted style, {@code /bucket}
     *                     in path style. Each segment is encoded here
     * @param query        query parameters, unencoded; sorted and encoded here
     * @param headers      headers to sign. Must contain {@code host}; anything else is
     *                     signed as given
     * @param payload      the body, empty for a GET
     * @param region       the bucket's region, or blank for {@link #DEFAULT_REGION}
     * @return the input headers plus {@code x-amz-date}, {@code x-amz-content-sha256} and
     *         {@code Authorization}, in a new map
     */
    public static Map<String, String> sign(String method, String canonicalUri,
                                           Map<String, String> query,
                                           Map<String, String> headers, byte[] payload,
                                           String accessKeyId, String secretAccessKey,
                                           String region, Instant at) {
        String signingRegion = region == null || region.isBlank() ? DEFAULT_REGION : region.strip();
        String amzDate = AMZ_DATE.format(at);
        String scopeDate = SCOPE_DATE.format(at);
        String payloadHash = HEX.formatHex(sha256(payload));

        Map<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT),
                value == null ? "" : value.strip()));
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-content-sha256", payloadHash);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : signed.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue())
                    .append('\n');
            if (!signedHeaders.isEmpty()) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        String canonicalRequest = method + "\n"
                + encodePath(canonicalUri) + "\n"
                + canonicalQuery(query) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String scope = scopeDate + "/" + signingRegion + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), scopeDate);
        key = hmac(key, signingRegion);
        key = hmac(key, SERVICE);
        key = hmac(key, "aws4_request");
        String signature = HEX.formatHex(hmac(key, stringToSign));

        Map<String, String> result = new LinkedHashMap<>(signed);
        result.put("Authorization", ALGORITHM + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return result;
    }

    /** The canonical request, exposed so a test can assert the part stores disagree over. */
    static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(query).forEach((name, value) -> {
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(encode(name)).append('=').append(encode(value == null ? "" : value));
        });
        return canonical.toString();
    }

    /**
     * Each segment encoded, the separators left alone.
     *
     * <p>Joined by index rather than by "is the buffer empty yet", because an empty segment
     * is legitimate - {@code /a//b} is a real key - and the buffer-empty version silently
     * swallows a leading run of them.
     */
    static String encodePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(encode(segments[i]));
        }
        String result = encoded.toString();
        return result.startsWith("/") ? result : "/" + result;
    }

    /**
     * RFC 3986, which is not what {@code URLEncoder} does.
     *
     * <p>{@code URLEncoder} is {@code application/x-www-form-urlencoded}: it turns a space
     * into {@code +} and leaves {@code *} alone. Both differences change the signature, and
     * the store's answer is {@code SignatureDoesNotMatch} with no indication which
     * character was at fault. The percent escapes are upper case, as the specification
     * requires and for the same reason.
     */
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            char character = (char) (raw & 0xFF);
            if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == '.'
                    || character == '~') {
                encoded.append(character);
            } else {
                encoded.append('%')
                        .append(HEX_UPPER.toHighHexDigit(raw))
                        .append(HEX_UPPER.toLowHexDigit(raw));
            }
        }
        return encoded.toString();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", impossible);
        }
    }
}
