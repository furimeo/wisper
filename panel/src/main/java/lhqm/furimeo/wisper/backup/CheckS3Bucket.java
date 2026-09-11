package lhqm.furimeo.wisper.backup;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Asks an object store whether a destination's bucket exists and whether its credentials
 * open it - by making one real, signed request.
 *
 * <p>{@code GET /?list-type=2&max-keys=0}. It transfers nothing, exercises the endpoint,
 * the bucket name, the region, the path style and both halves of the credential, and every
 * S3-compatible store answers it. Anything less - checking that the fields are non-empty -
 * would be a "verify" button that verifies the form, which is the kind of door this project
 * exists not to build.
 *
 * <p>Redirects are deliberately <strong>not</strong> followed. A 301 from AWS means the
 * bucket is in another region, and following it would produce a signature failure whose
 * message says nothing about the region - which is the single most common way an S3
 * destination is misconfigured.
 */
@Component
public class CheckS3Bucket {

    /** Enough of an error body to carry the store's own error code, and no more. */
    private static final int BODY_EXCERPT = 400;

    private final HttpClient http;
    private final BackupSettings settings;

    public CheckS3Bucket(BackupSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.destinationCheckTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Tries the bucket.
     *
     * @return empty when the store answered and the bucket is usable, or one sentence
     *         saying what went wrong. Never throws: a destination that cannot be reached is
     *         an answer, not a failure of the panel
     */
    public Optional<String> probe(DestinationCredentials credentials) {
        URI endpoint;
        try {
            endpoint = new URI(credentials.endpoint());
        } catch (URISyntaxException malformed) {
            return Optional.of("\"" + credentials.endpoint() + "\" is not a URL.");
        }
        if (endpoint.getScheme() == null || endpoint.getHost() == null) {
            return Optional.of("The endpoint needs a scheme and a host, like "
                    + "https://s3.example.com.");
        }

        String host = credentials.pathStyle()
                ? hostHeader(endpoint)
                : credentials.bucket() + "." + hostHeader(endpoint);
        String path = credentials.pathStyle() ? "/" + credentials.bucket() : "/";

        Map<String, String> query = new LinkedHashMap<>();
        query.put("list-type", "2");
        query.put("max-keys", "0");
        if (!credentials.prefix().isEmpty()) {
            query.put("prefix", credentials.prefix());
        }

        Map<String, String> headers = SignS3Request.sign("GET", path, query,
                Map.of("host", host), new byte[0], credentials.accessKeyId(),
                credentials.secretAccessKey(), credentials.region(), Instant.now());

        URI url;
        try {
            url = new URI(endpoint.getScheme(), null, hostOnly(host), portOf(endpoint),
                    SignS3Request.encodePath(path), null, null);
            url = URI.create(url + "?" + SignS3Request.canonicalQuery(query));
        } catch (URISyntaxException malformed) {
            return Optional.of("That endpoint and bucket do not make a valid URL together.");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .GET()
                .timeout(settings.destinationCheckTimeout());
        // The host header is set by the client from the URL and may not be set by hand.
        headers.forEach((name, value) -> {
            if (!"host".equalsIgnoreCase(name)) {
                request.header(name, value);
            }
        });

        try {
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return interpret(response, credentials);
        } catch (IOException unreachable) {
            return Optional.of("Could not reach " + credentials.endpoint() + ": "
                    + reasonOf(unreachable));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.of("The check was interrupted before the store answered.");
        }
    }

    /** What each answer means, in words an operator can act on. */
    private static Optional<String> interpret(HttpResponse<String> response,
                                              DestinationCredentials credentials) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Optional.empty();
        }
        String code = errorCodeIn(response.body());
        return Optional.of(switch (status) {
            case 301, 307, 400 -> "The store answered " + status + " (" + code + "). The bucket "
                    + "is usually in a different region from \"" + credentials.region() + "\", "
                    + "or needs the other addressing style.";
            case 403 -> "Access denied (" + code + "). The key is wrong, or it is not allowed "
                    + "to list \"" + credentials.bucket() + "\".";
            case 404 -> "There is no bucket called \"" + credentials.bucket() + "\" at that "
                    + "endpoint (" + code + ").";
            default -> "The store answered " + status + " (" + code + ").";
        });
    }

    /**
     * The {@code <Code>} element every S3 error body carries.
     *
     * <p>Parsed by hand and not with an XML reader. This is one element of a document from
     * a remote server: an XML parser here would be an XXE surface for a "verify" button, and
     * the value is a short token in a known position.
     */
    private static String errorCodeIn(String body) {
        if (body == null) {
            return "no detail";
        }
        int open = body.indexOf("<Code>");
        int close = body.indexOf("</Code>");
        if (open < 0 || close <= open) {
            String excerpt = body.strip();
            return excerpt.isEmpty() ? "no detail"
                    : excerpt.substring(0, Math.min(BODY_EXCERPT, excerpt.length()));
        }
        return body.substring(open + "<Code>".length(), close);
    }

    /** The host as the signature must see it: the name, and the port when it is not the default. */
    private static String hostHeader(URI endpoint) {
        int port = endpoint.getPort();
        boolean defaultPort = port < 0
                || ("https".equals(endpoint.getScheme()) && port == 443)
                || ("http".equals(endpoint.getScheme()) && port == 80);
        return defaultPort ? endpoint.getHost() : endpoint.getHost() + ":" + port;
    }

    private static String hostOnly(String host) {
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static int portOf(URI endpoint) {
        return endpoint.getPort();
    }

    private static String reasonOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
