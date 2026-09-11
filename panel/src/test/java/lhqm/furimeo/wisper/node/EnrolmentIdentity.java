package lhqm.furimeo.wisper.node;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;

/**
 * An Ed25519 identity a test can enrol with, in the exact wire form sasayaki sends.
 *
 * <p>The encoding is the point of this class. {@code EnrollRequest.public_key} is the 32
 * raw bytes of RFC 8032 - the y coordinate little-endian with the sign of x in the top bit
 * of the last byte - and the JDK hands out an {@link EdECPoint} instead. A test that used
 * {@code getEncoded()} would be signing with a real key and sending an X.509 wrapper, and
 * would prove that {@link VerifyEnrolmentProof} rejects something no node ever sends.
 */
final class EnrolmentIdentity {

    private final KeyPair keys;

    private EnrolmentIdentity(KeyPair keys) {
        this.keys = keys;
    }

    static EnrolmentIdentity generate() {
        try {
            return new EnrolmentIdentity(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("This JDK has no Ed25519", impossible);
        }
    }

    /** The 32 raw bytes the wire carries. */
    byte[] publicKey() {
        EdECPoint point = ((EdECPublicKey) keys.getPublic()).getPoint();
        byte[] bigEndianY = point.getY().toByteArray();
        byte[] raw = new byte[VerifyEnrolmentProof.PUBLIC_KEY_BYTES];
        for (int index = 0; index < bigEndianY.length && index < raw.length; index++) {
            raw[index] = bigEndianY[bigEndianY.length - 1 - index];
        }
        if (point.isXOdd()) {
            raw[raw.length - 1] |= (byte) 0x80;
        }
        return raw;
    }

    /** A signature over exactly the message {@code node.proto} specifies. */
    byte[] sign(String bootstrapToken, String machineFingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(VerifyEnrolmentProof.messageFor(bootstrapToken, machineFingerprint));
            return signer.sign();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Signing failed", impossible);
        }
    }
}
