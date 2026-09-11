package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.EnrollRequest;
import lhqm.furimeo.wisper.proto.v1.EnrollResponse;

/**
 * Turns a single-use bootstrap token into a permanent node credential (design §7.3).
 *
 * <p>This is the one unauthenticated call in the whole gRPC surface, so it is the one that
 * has to be careful. Six things are checked before anything is written, in an order chosen
 * so that the cheapest refusals happen first and no check leaks information the previous
 * one would not have:
 *
 * <ol>
 * <li>the request is complete and the key and signature are the right sizes;</li>
 * <li>the agent speaks a protocol this panel speaks - refused here, once, rather than at
 *     every {@code Connect} for the rest of the machine's life (design §7.5);</li>
 * <li>the token exists, is not revoked, is not spent, and has not expired;</li>
 * <li>the node record it belongs to has not already been enrolled;</li>
 * <li>the sender holds the private half of the key it is asking to be identified by;</li>
 * <li>no other node already reports this machine's fingerprint.</li>
 * </ol>
 *
 * <p>Only then is a credential minted. Everything below happens in one transaction, which
 * is what makes the replay rule true rather than aspirational: {@code used_at} on the
 * token and {@code credential_hash} on the node are written together, so a second machine
 * presenting the same token finds it already spent, and a failure anywhere rolls both back
 * and leaves the operator's token still usable.
 */
@Component
public class EnrolNode {

    private static final Logger log = LoggerFactory.getLogger(EnrolNode.class);

    /** A machine fingerprint is a hex SHA-256, so it is this long and nothing else. */
    private static final int FINGERPRINT_LENGTH = 64;

    private final NodeRepository nodes;
    private final NodeEnrollmentTokenRepository tokens;
    private final VerifyEnrolmentProof proof;
    private final DetectClonedNode clones;
    private final RecordMachineFacts machineFacts;
    private final AuditTrail audit;
    private final NodeSettings settings;

    public EnrolNode(NodeRepository nodes, NodeEnrollmentTokenRepository tokens,
                     VerifyEnrolmentProof proof, DetectClonedNode clones,
                     RecordMachineFacts machineFacts, AuditTrail audit, NodeSettings settings) {
        this.nodes = nodes;
        this.tokens = tokens;
        this.proof = proof;
        this.clones = clones;
        this.machineFacts = machineFacts;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * @param request                 the {@code Enroll} body, token and all
     * @param remoteAddress           where it came from; recorded on the token row so an
     *                                operator can see whether that address was them
     * @param panelCertificateSha256  hex SHA-256 of the TLS leaf the node's connection was
     *                                terminated with, echoed back so the node can compare
     *                                it with what it actually saw and refuse to write a
     *                                credential if something else terminated the handshake
     *                                (design §7.1)
     * @throws EnrolmentRefused for every way this can be said no to; the reason is
     *                          specific on purpose, because an operator is watching an
     *                          installer's output and "enrolment failed" costs an hour
     */
    @Transactional
    public EnrollResponse enrol(EnrollRequest request, String remoteAddress,
                                String panelCertificateSha256) {
        String token = request.getBootstrapToken();
        String fingerprint = request.getMachineFingerprint().toLowerCase(Locale.ROOT);
        requireWellFormed(request, token, fingerprint);

        if (!NodeProtocol.canSpeak(request.getProtocolVersion())) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.PROTOCOL_UNSUPPORTED,
                    NodeProtocol.mismatchMessage(request.getProtocolVersion()));
        }

        Instant now = Instant.now();
        NodeEnrollmentToken bootstrap = tokens.findByTokenHash(NodeSecret.hashOf(token))
                .orElseThrow(() -> new EnrolmentRefused(EnrolmentRefused.Reason.TOKEN_UNKNOWN));
        requireUsable(bootstrap, now);

        Node node = nodes.findById(bootstrap.nodeId()).orElseThrow(() ->
                new EnrolmentRefused(EnrolmentRefused.Reason.TOKEN_UNKNOWN,
                        "That token belongs to a node record that no longer exists."));
        if (!node.lifecycle().acceptsEnrolment()) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.NODE_ALREADY_ENROLLED,
                    node.name() + " is already " + node.lifecycle() + ".");
        }

        if (!proof.holds(request.getPublicKey().toByteArray(),
                request.getSignature().toByteArray(), token, request.getMachineFingerprint())) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.PROOF_INVALID);
        }
        if (!request.getDoctor().getRequiredChecksPassed()) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.DOCTOR_FAILED,
                    firstRequiredFailure(request));
        }

        // Throws, and suspends both records, when this machine is a clone (design §7.3).
        clones.atEnrolment(node.id(), fingerprint, remoteAddress);

        NodeSecret credential = NodeSecret.credential();
        String endpoint = node.dialledEndpoint() == null || node.dialledEndpoint().isBlank()
                ? settings.dialEndpointFor(hostOf(remoteAddress))
                : node.dialledEndpoint();
        Node enrolled = nodes.save(node.enrolled(fingerprint,
                Base64.getEncoder().encodeToString(request.getPublicKey().toByteArray()),
                credential.hash(), endpoint, now));
        tokens.save(bootstrap.spent(now, remoteAddress));
        machineFacts.accept(enrolled.id(), request.getDoctor().getMachine(), request.getDoctor(),
                now);

        log.info("Node {} enrolled from {} running {} (protocol {})", enrolled.name(),
                remoteAddress, request.getAgentVersion(), request.getProtocolVersion());
        audit.record(AuditEntry.succeeded(
                AuditActor.node(enrolled.id(), enrolled.name(), remoteAddress), "node.enrol",
                AuditTarget.of("node", enrolled.id(), enrolled.name()), null,
                "Enrolled " + request.getAgentVersion() + " on " + request.getHostname()
                        + "; runsc " + (request.getDoctor().getMachine().getRunscAvailable()
                        ? "available" : "missing")));

        return EnrollResponse.newBuilder()
                .setNodeId(enrolled.id().toString())
                .setCredential(credential.text())
                .setPanelCertificateSha256(panelCertificateSha256 == null
                        ? "" : panelCertificateSha256)
                .setNodeName(enrolled.name())
                .setProtocolVersion(NodeProtocol.SUPPORTED)
                .setReconcileIntervalSeconds(settings.reconcileInterval().toSeconds())
                .setHeartbeatIntervalSeconds(settings.heartbeatInterval().toSeconds())
                .build();
    }

    private static void requireWellFormed(EnrollRequest request, String token,
                                          String fingerprint) {
        if (token == null || token.isBlank()) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.MALFORMED,
                    "The request carries no bootstrap token.");
        }
        if (fingerprint.length() != FINGERPRINT_LENGTH) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.MALFORMED,
                    "The machine fingerprint is not a hex SHA-256.");
        }
        if (request.getPublicKey().size() != VerifyEnrolmentProof.PUBLIC_KEY_BYTES) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.MALFORMED,
                    "An Ed25519 public key is 32 bytes.");
        }
        if (request.getSignature().size() != VerifyEnrolmentProof.SIGNATURE_BYTES) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.MALFORMED,
                    "An Ed25519 signature is 64 bytes.");
        }
    }

    /**
     * The three ways a token that exists is still no good, in the order that produces the
     * most useful message: revoked beats spent beats expired, because a revoked token was
     * a decision and the other two are just time passing.
     */
    private static void requireUsable(NodeEnrollmentToken bootstrap, Instant now) {
        if (bootstrap.isRevoked()) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.TOKEN_REVOKED);
        }
        if (bootstrap.isSpent()) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.TOKEN_SPENT,
                    "That bootstrap token was already used at " + bootstrap.usedAt() + " from "
                            + bootstrap.usedFromAddress() + ". A token enrols one machine, once.");
        }
        if (bootstrap.hasExpired(now)) {
            throw new EnrolmentRefused(EnrolmentRefused.Reason.TOKEN_EXPIRED,
                    "That bootstrap token expired at " + bootstrap.expiresAt()
                            + ". Issue a fresh one from the node's page.");
        }
    }

    /** The first required check that failed, so the installer's output names it. */
    private static String firstRequiredFailure(EnrollRequest request) {
        return request.getDoctor().getChecksList().stream()
                .filter(check -> check.getOutcome()
                        == lhqm.furimeo.wisper.proto.v1.DoctorOutcome.DOCTOR_OUTCOME_FAIL)
                .findFirst()
                .map(check -> check.getTitle() + ": " + check.getDetail()
                        + (check.getRemedy().isBlank() ? "" : " (" + check.getRemedy() + ")"))
                .orElse(EnrolmentRefused.Reason.DOCTOR_FAILED.defaultDetail());
    }

    /** The host half of an address, so a dial endpoint is not built with a source port. */
    private static String hostOf(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "localhost";
        }
        int lastColon = remoteAddress.lastIndexOf(':');
        // An IPv6 literal has several colons and is bracketed when it carries a port.
        if (lastColon > 0 && remoteAddress.indexOf(':') == lastColon) {
            return remoteAddress.substring(0, lastColon);
        }
        return remoteAddress;
    }
}
