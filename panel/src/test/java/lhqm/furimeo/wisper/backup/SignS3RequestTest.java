package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The signature, checked against AWS's own published example.
 *
 * <p>A hand-written SigV4 either works or produces {@code SignatureDoesNotMatch}, and that
 * answer says nothing about which of a dozen small rules was broken. A known-answer test is
 * therefore the only kind worth having here: the vector below is the "GET Object" example
 * from the Signature Version 4 documentation, and if a single character of the canonical
 * request changes, the last assertion fails with a different hex string.
 */
class SignS3RequestTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";

    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private static final Instant AT = Instant.parse("2013-05-24T00:00:00Z");

    /** From the AWS documentation's GET Object example, verbatim. */
    private static final String EXPECTED_SIGNATURE =
            "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";

    @Nested
    class TheKnownAnswer {

        @Test
        void matchesTheDocumentedGetObjectExample() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("host", "examplebucket.s3.amazonaws.com");
            headers.put("range", "bytes=0-9");

            Map<String, String> signed = SignS3Request.sign("GET", "/test.txt", Map.of(), headers,
                    new byte[0], ACCESS_KEY, SECRET_KEY, "us-east-1", AT);

            assertThat(signed.get("Authorization"))
                    .isEqualTo("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY
                            + "/20130524/us-east-1/s3/aws4_request, "
                            + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                            + "Signature=" + EXPECTED_SIGNATURE);
        }

        @Test
        void addsTheTwoHeadersEveryS3RequestNeeds() {
            Map<String, String> signed = SignS3Request.sign("GET", "/", Map.of(),
                    Map.of("host", "s3.example.com"), new byte[0], ACCESS_KEY, SECRET_KEY, "",
                    AT);

            assertThat(signed.get("x-amz-date")).isEqualTo("20130524T000000Z");
            assertThat(signed.get("x-amz-content-sha256"))
                    .isEqualTo(SignS3Request.EMPTY_PAYLOAD_SHA256);
        }

        @Test
        void aBlankRegionSignsAgainstTheOneMostStoresExpect() {
            Map<String, String> signed = SignS3Request.sign("GET", "/", Map.of(),
                    Map.of("host", "s3.example.com"), new byte[0], ACCESS_KEY, SECRET_KEY, null,
                    AT);

            assertThat(signed.get("Authorization"))
                    .contains("/" + SignS3Request.DEFAULT_REGION + "/s3/aws4_request");
        }

        @Test
        void aDifferentRegionProducesADifferentSignature() {
            Map<String, String> frankfurt = SignS3Request.sign("GET", "/", Map.of(),
                    Map.of("host", "s3.example.com"), new byte[0], ACCESS_KEY, SECRET_KEY,
                    "eu-central-1", AT);
            Map<String, String> virginia = SignS3Request.sign("GET", "/", Map.of(),
                    Map.of("host", "s3.example.com"), new byte[0], ACCESS_KEY, SECRET_KEY,
                    "us-east-1", AT);

            assertThat(frankfurt.get("Authorization"))
                    .isNotEqualTo(virginia.get("Authorization"));
        }
    }

    @Nested
    class Canonicalisation {

        @Test
        void headersAreSortedAndLowerCasedWhateverOrderTheyArrivedIn() {
            Map<String, String> mixed = new LinkedHashMap<>();
            mixed.put("Range", " bytes=0-9 ");
            mixed.put("Host", "examplebucket.s3.amazonaws.com");

            Map<String, String> signed = SignS3Request.sign("GET", "/test.txt", Map.of(), mixed,
                    new byte[0], ACCESS_KEY, SECRET_KEY, "us-east-1", AT);

            assertThat(signed.get("Authorization")).contains("Signature=" + EXPECTED_SIGNATURE);
        }

        @Test
        void queryParametersAreSortedByName() {
            Map<String, String> unsorted = new LinkedHashMap<>();
            unsorted.put("max-keys", "0");
            unsorted.put("list-type", "2");

            assertThat(SignS3Request.canonicalQuery(unsorted)).isEqualTo("list-type=2&max-keys=0");
        }

        @Test
        void encodingIsRfc3986AndNotFormEncoding() {
            // URLEncoder would give "a+b" for the space and leave the asterisk alone; both
            // change the signature and the store's answer names neither.
            assertThat(SignS3Request.encode("a b")).isEqualTo("a%20b");
            assertThat(SignS3Request.encode("*")).isEqualTo("%2A");
            assertThat(SignS3Request.encode("~-._")).isEqualTo("~-._");
            assertThat(SignS3Request.encode("/")).isEqualTo("%2F");
        }

        @Test
        void percentEscapesAreUpperCase() {
            assertThat(SignS3Request.encode("é")).isEqualTo("%C3%A9");
        }

        @Test
        void pathSegmentsAreEncodedAndSeparatorsAreNot() {
            assertThat(SignS3Request.encodePath("/tenant a/key.txt"))
                    .isEqualTo("/tenant%20a/key.txt");
            assertThat(SignS3Request.encodePath("/")).isEqualTo("/");
            assertThat(SignS3Request.encodePath("")).isEqualTo("/");
            // An empty segment is a real key and must survive rather than being collapsed.
            assertThat(SignS3Request.encodePath("/a//b")).isEqualTo("/a//b");
        }
    }
}
