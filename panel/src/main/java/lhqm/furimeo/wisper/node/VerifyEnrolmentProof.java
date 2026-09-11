package lhqm.furimeo.wisper.node;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks that whoever sent an {@code EnrollRequest} holds the private half of the key in
 * it.
 *
 * <p>Without this, a bootstrap token is a bearer token in the worst sense: anyone who
 * reads it off a terminal, out of a shell history or from a log line can enrol with a key
 * they generated a second ago, and the panel will happily record their machine as the
 * node. The signature turns the token into one factor of two - possession of the token,
 * and possession of the key the enrolling machine is about to be identified by.
 *
 * <p>The signed message is fixed by {@code node.proto} and is domain-separated so a
 * signature produced for any other purpose cannot be replayed here:
 *
 * <pre>{@code "wisper-enroll-v1\n" + bootstrap_token + "\n" + machine_fingerprint}</pre>
 *
 * <p>Binding the fingerprint into the message is what stops a machine-in-the-middle
 * forwarding a legitimate machine's signature while substituting its own hardware
 * identity, which would defeat the clone detection in {@code DetectClonedNode}.
 *
 * <h2>Why the key decoding is by hand</h2>
 *
 * <p>The wire carries the 32 raw bytes every Ed25519 implementation calls a public key.
 * The JDK's {@code KeyFactory} wants an {@link EdECPoint}, so the raw form has to be
 * unpacked: RFC 8032 encodes it as the y coordinate little-endian with the sign of x in
 * the top bit of the last byte. That is six lines and no dependency, which is the trade
 * this project makes everywhere.
 */
@Component
public class VerifyEnrolmentProof {

    private static final Logger log = LoggerFactory.getLogger(VerifyEnrolmentProof.class);

    /** Domain separation, byte for byte as {@code node.proto} specifies it. */
    public static final String CONTEXT = "wisper-enroll-v1\n";

    /** An Ed25519 public key is exactly this many bytes, always. */
    public static final int PUBLIC_KEY_BYTES = 32;

    /** An Ed25519 signature is exactly this many bytes, always. */
    public static final int SIGNATURE_BYTES = 64;

    /**
     * Whether {@code signature} is a valid Ed25519 signature by {@code publicKey} over the
     * message this enrolment implies.
     *
     * <p>Returns false rather than throwing for every way it can fail, including a
     * malformed key: to the caller, "the proof did not check out" is one outcome with one
     * response, and a request built by hand should not be able to choose between a refusal
     * and a stack trace.
     */
    public boolean holds(byte[] publicKey, byte[] signature, String bootstrapToken,
                         String machineFingerprint) {
        if (publicKey == null || publicKey.length != PUBLIC_KEY_BYTES) {
            log.debug("Enrolment proof rejected: public key is {} bytes, not {}",
                    publicKey == null ? 0 : publicKey.length, PUBLIC_KEY_BYTES);
            return false;
        }
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            log.debug("Enrolment proof rejected: signature is {} bytes, not {}",
                    signature == null ? 0 : signature.length, SIGNATURE_BYTES);
            return false;
        }
        byte[] message = messageFor(bootstrapToken, machineFingerprint);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(decode(publicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException rejected) {
            log.debug("Enrolment proof rejected: {}", rejected.getMessage());
            return false;
        }
    }

    /** The exact bytes both sides sign, exposed so a test can sign them the same way. */
    public static byte[] messageFor(String bootstrapToken, String machineFingerprint) {
        return (CONTEXT + bootstrapToken + "\n" + machineFingerprint)
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Turns the 32 raw RFC 8032 bytes into a JDK key.
     *
     * <p>Little-endian y, with the top bit of the last byte carrying whether x is odd.
     * {@code generatePublic} rejects a y that is not on the curve, so a key made of random
     * bytes fails here rather than producing a verifier that always says no.
     */
    private static PublicKey decode(byte[] raw) throws GeneralSecurityException {
        byte[] littleEndian = raw.clone();
        boolean xOdd = (littleEndian[PUBLIC_KEY_BYTES - 1] & 0x80) != 0;
        littleEndian[PUBLIC_KEY_BYTES - 1] &= 0x7F;
        for (int head = 0, tail = littleEndian.length - 1; head < tail; head++, tail--) {
            byte swap = littleEndian[head];
            littleEndian[head] = littleEndian[tail];
            littleEndian[tail] = swap;
        }
        BigInteger y = new BigInteger(1, littleEndian);
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519,
                        new EdECPoint(xOdd, y)));
    }
}
