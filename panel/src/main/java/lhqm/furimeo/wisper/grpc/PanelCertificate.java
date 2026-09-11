package lhqm.furimeo.wisper.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The fingerprint of the TLS certificate the panel presents on the gRPC port, if it
 * presents one.
 *
 * <p>It goes into {@code EnrollResponse.panel_certificate_sha256} and the node pins it on
 * first use. From then on, a hijacked tunnel or a stolen DNS record cannot become a
 * machine-in-the-middle: whatever certificate the node is offered next has to be the one
 * it already wrote down (design §7.1).
 *
 * <p>The value is what makes that check possible, and it can only come from the end that
 * owns the certificate. Which is the reason this is empty in the normal deployment: a
 * tunnel terminates TLS and forwards plaintext, so the certificate the node saw was never
 * the panel's, and the panel claiming a fingerprint it did not present would be worse than
 * useless - the node would pin a value that never matches and refuse to enrol. An empty
 * string is the honest answer and the node skips the comparison.
 *
 * <p>Read once at startup. A certificate that is replaced on disk needs a restart, which
 * is also true of the server socket it belongs to.
 */
@Component
public class PanelCertificate {

    private static final Logger log = LoggerFactory.getLogger(PanelCertificate.class);

    private final String sha256;

    public PanelCertificate(GrpcSettings settings) {
        this.sha256 = settings.terminatesTls()
                ? fingerprintOf(Path.of(settings.certificateChainFile()))
                : "";
        if (sha256.isEmpty()) {
            log.info("The panel does not terminate TLS on the gRPC port, so nodes are told no "
                    + "certificate fingerprint to pin. Whatever sits in front of it does.");
        } else {
            log.info("Nodes will pin the panel certificate sha256:{}", sha256);
        }
    }

    /** Lower-case hex SHA-256 of the leaf certificate's DER, or empty when there is none. */
    public String sha256() {
        return sha256;
    }

    /**
     * The <em>first</em> certificate in the chain, which is the leaf by PEM convention and
     * the one a client actually validates. Hashing the whole file instead would produce a
     * value that changes when an intermediate is added, and no node could verify it.
     */
    private static String fingerprintOf(Path chain) {
        try (InputStream pem = Files.newInputStream(chain)) {
            X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(pem);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (IOException | CertificateException | NoSuchAlgorithmException
                 unreadable) {
            // Not fatal. The server below will fail to start on the same file if it is
            // genuinely broken, with a message about TLS rather than about a hash.
            log.error("Could not read the panel certificate at {}: {}", chain,
                    unreadable.getMessage());
            return "";
        }
    }
}
