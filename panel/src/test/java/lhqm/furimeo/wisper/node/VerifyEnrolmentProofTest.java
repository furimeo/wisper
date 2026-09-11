package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The signature is what turns a bootstrap token from a bearer secret into one factor of
 * two. These are the ways it has to fail.
 */
class VerifyEnrolmentProofTest {

    private static final String TOKEN = "wsp_ZXhhbXBsZS10b2tlbg";
    private static final String FINGERPRINT =
            "3d0f7a1b5c2e9d8476a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f607";

    private final VerifyEnrolmentProof proof = new VerifyEnrolmentProof();
    private final EnrolmentIdentity identity = EnrolmentIdentity.generate();

    @Test
    void aSignatureOverTheAgreedMessageHolds() {
        assertThat(proof.holds(identity.publicKey(), identity.sign(TOKEN, FINGERPRINT), TOKEN,
                FINGERPRINT)).isTrue();
    }

    @Test
    void aSignatureMadeForADifferentTokenDoesNotHold() {
        byte[] signature = identity.sign("wsp_some-other-token", FINGERPRINT);

        assertThat(proof.holds(identity.publicKey(), signature, TOKEN, FINGERPRINT)).isFalse();
    }

    @Test
    void aSignatureMadeForADifferentMachineDoesNotHold() {
        // The fingerprint is inside the signed message precisely so a relay cannot forward
        // a legitimate machine's signature while substituting its own hardware identity -
        // which would defeat the clone detection entirely.
        byte[] signature = identity.sign(TOKEN,
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(proof.holds(identity.publicKey(), signature, TOKEN, FINGERPRINT)).isFalse();
    }

    @Test
    void anotherKeyPairCannotVouchForThisOne() {
        EnrolmentIdentity impostor = EnrolmentIdentity.generate();

        assertThat(proof.holds(identity.publicKey(), impostor.sign(TOKEN, FINGERPRINT), TOKEN,
                FINGERPRINT)).isFalse();
    }

    @Test
    void aKeyOfTheWrongSizeIsRefusedRatherThanThrown() {
        byte[] signature = identity.sign(TOKEN, FINGERPRINT);

        assertThat(proof.holds(new byte[31], signature, TOKEN, FINGERPRINT)).isFalse();
        assertThat(proof.holds(null, signature, TOKEN, FINGERPRINT)).isFalse();
    }

    @Test
    void aSignatureOfTheWrongSizeIsRefusedRatherThanThrown() {
        assertThat(proof.holds(identity.publicKey(), new byte[63], TOKEN, FINGERPRINT)).isFalse();
        assertThat(proof.holds(identity.publicKey(), null, TOKEN, FINGERPRINT)).isFalse();
    }

    @Test
    void randomBytesThatAreNotAPointAreRefusedRatherThanThrown() {
        byte[] notAKey = new byte[VerifyEnrolmentProof.PUBLIC_KEY_BYTES];
        Arrays.fill(notAKey, (byte) 0xFF);

        assertThat(proof.holds(notAKey, identity.sign(TOKEN, FINGERPRINT), TOKEN, FINGERPRINT))
                .isFalse();
    }

    @Test
    void theSignedMessageIsDomainSeparated() {
        String message = new String(VerifyEnrolmentProof.messageFor(TOKEN, FINGERPRINT),
                java.nio.charset.StandardCharsets.US_ASCII);

        assertThat(message).isEqualTo("wisper-enroll-v1\n" + TOKEN + "\n" + FINGERPRINT);
    }
}
