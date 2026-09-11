package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A machine running sasayaki, as the panel sees it.
 *
 * <p>This record is the panel's half of the relationship and nothing else: the record, the
 * enrolment identity, and what an operator has decided. Everything the machine says about
 * itself is {@link NodeStatus}, written only by the gRPC handlers. A column written from
 * both directions is the bug that split is here to prevent (AGENTS.md §4.2).
 *
 * <p>Every state change is a method returning a new record, so the pairs the database
 * enforces cannot be half-applied. {@code node_suspension_consistent} says
 * {@code lifecycle = 'SUSPENDED'} and {@code suspended_at IS NOT NULL} are the same fact;
 * {@code node_enrolled_identity_complete} says a node past {@code CREATED} holds all three
 * of fingerprint, public key and credential hash. A caller who sets one field and forgets
 * the other never gets the chance.
 *
 * @param id                 generated in Java before the insert (schema.md §1)
 * @param name               what the operator typed, and types again to confirm deletion
 * @param description        free text shown on the node's page
 * @param fingerprint        machine-id plus hardware serials, hashed. Unique across the
 *                           table, and that uniqueness <em>is</em> the clone detector.
 * @param publicKey          base64 Ed25519 public key; the private half never leaves the
 *                           machine
 * @param credentialHash     SHA-256 of the long-lived credential presented on every RPC
 * @param credentialIssuedAt when that credential was minted
 * @param lifecycle          the operator's intent
 * @param schedulable        the placement filter; false stops new work without draining
 * @param drainRequestedAt   when a drain was asked for, so a stuck drain is visible
 * @param suspendedAt        set exactly when {@code lifecycle} is {@code SUSPENDED}
 * @param suspensionReason   which of the four suspensions this is
 * @param publicAddress      where customers' DNS points. The panel never dials it.
 * @param dialledEndpoint    the gRPC endpoint this node was told to use, for display
 * @param tags               placement filters, matched with array containment
 * @param desiredGeneration  the monotonic spec counter; never decreased, never reset
 * @param specUpdatedAt      when the spec at that generation was published
 * @param version            null means new; see schema.md §1
 */
public record Node(
        @Id UUID id,
        String name,
        String description,
        String fingerprint,
        String publicKey,
        String credentialHash,
        Instant credentialIssuedAt,
        NodeLifecycle lifecycle,
        boolean schedulable,
        Instant drainRequestedAt,
        Instant suspendedAt,
        NodeSuspensionReason suspensionReason,
        String publicAddress,
        String dialledEndpoint,
        String[] tags,
        long desiredGeneration,
        Instant specUpdatedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** The shape a node name has to have, matching {@code node_name_shape}. */
    public static final String NAME_PATTERN = "^[a-z0-9][a-z0-9.-]{1,62}$";

    private static final String[] NO_TAGS = new String[0];

    /** A record an operator just created. No machine has enrolled against it yet. */
    public static Node created(UUID id, String name, String description, String publicAddress,
                               List<String> tags) {
        return new Node(id, name, description == null ? "" : description, null, null, null, null,
                NodeLifecycle.CREATED, true, null, null, null,
                publicAddress == null ? "" : publicAddress, "", tags.toArray(String[]::new),
                0L, null, null, null, null);
    }

    /** Tags as a list, because callers filter and render, they do not index. */
    public List<String> tagList() {
        return tags == null ? List.of() : List.of(tags);
    }

    public boolean isEnrolled() {
        return lifecycle != NodeLifecycle.CREATED;
    }

    public boolean isSuspended() {
        return lifecycle == NodeLifecycle.SUSPENDED;
    }

    /** Whether the scheduler may place new work here. */
    public boolean isPlaceable() {
        return schedulable && lifecycle.acceptsPlacements();
    }

    /**
     * The three halves of the identity plus the credential, applied at once.
     *
     * <p>Called from {@code EnrolNode} inside the transaction that also spends the
     * bootstrap token, which is what makes a replay of that token find it already gone.
     */
    public Node enrolled(String machineFingerprint, String publicKeyBase64,
                         String newCredentialHash, String endpoint, Instant at) {
        return new Node(id, name, description, machineFingerprint, publicKeyBase64,
                newCredentialHash, at, NodeLifecycle.ENROLLED, schedulable, null, null, null,
                publicAddress, endpoint, tags, desiredGeneration, specUpdatedAt,
                createdAt, updatedAt, version);
    }

    /** Both halves of {@code node_suspension_consistent} in one move. */
    public Node suspended(NodeSuspensionReason reason, Instant at) {
        return new Node(id, name, description, fingerprint, publicKey, credentialHash,
                credentialIssuedAt, NodeLifecycle.SUSPENDED, false, drainRequestedAt, at, reason,
                publicAddress, dialledEndpoint, tags, desiredGeneration, specUpdatedAt,
                createdAt, updatedAt, version);
    }

    /**
     * Lifts a suspension, clearing both halves.
     *
     * <p>Goes back to {@code ENROLLED} rather than to whatever it was before, because the
     * only thing that can be suspended is a node that finished enrolling, and a record
     * that never did has no credential to come back with.
     */
    public Node resumed() {
        return new Node(id, name, description, fingerprint, publicKey, credentialHash,
                credentialIssuedAt, NodeLifecycle.ENROLLED, true, null, null, null,
                publicAddress, dialledEndpoint, tags, desiredGeneration, specUpdatedAt,
                createdAt, updatedAt, version);
    }

    /** Stops new placements and records when the drain was asked for. */
    public Node draining(Instant at) {
        return new Node(id, name, description, fingerprint, publicKey, credentialHash,
                credentialIssuedAt, NodeLifecycle.DRAINING, false, at, suspendedAt,
                suspensionReason, publicAddress, dialledEndpoint, tags, desiredGeneration,
                specUpdatedAt, createdAt, updatedAt, version);
    }

    /** The node reported that nothing evacuable is left. */
    public Node drained() {
        return new Node(id, name, description, fingerprint, publicKey, credentialHash,
                credentialIssuedAt, NodeLifecycle.DRAINED, false, drainRequestedAt, suspendedAt,
                suspensionReason, publicAddress, dialledEndpoint, tags, desiredGeneration,
                specUpdatedAt, createdAt, updatedAt, version);
    }

    /** Editable detail. The name is not here: it is the deletion confirmation phrase. */
    public Node withDetails(String newDescription, String newPublicAddress, List<String> newTags,
                            boolean nowSchedulable) {
        return new Node(id, name, newDescription == null ? "" : newDescription, fingerprint,
                publicKey, credentialHash, credentialIssuedAt, lifecycle, nowSchedulable,
                drainRequestedAt, suspendedAt, suspensionReason,
                newPublicAddress == null ? "" : newPublicAddress, dialledEndpoint,
                newTags == null ? NO_TAGS : newTags.toArray(String[]::new), desiredGeneration,
                specUpdatedAt, createdAt, updatedAt, version);
    }

    /**
     * Records that a spec at {@code generation} was published.
     *
     * <p>Monotonic is enforced here as well as documented: a caller passing a generation
     * that is not greater than the one on the row would let a node be rolled backwards,
     * which is the one thing the counter exists to make impossible.
     */
    public Node publishedGeneration(long generation, Instant at) {
        if (generation <= desiredGeneration) {
            throw new IllegalArgumentException("Generation " + generation + " does not advance "
                    + name + ", which is already at " + desiredGeneration);
        }
        return new Node(id, name, description, fingerprint, publicKey, credentialHash,
                credentialIssuedAt, lifecycle, schedulable, drainRequestedAt, suspendedAt,
                suspensionReason, publicAddress, dialledEndpoint, tags, generation, at,
                createdAt, updatedAt, version);
    }

    /**
     * Arrays make the generated {@code equals} identity-based, which turns an innocent
     * assertion in a test into a false negative. Comparing the contents is what every
     * caller means.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Node node
                && java.util.Objects.equals(id, node.id)
                && java.util.Objects.equals(name, node.name)
                && java.util.Objects.equals(description, node.description)
                && java.util.Objects.equals(fingerprint, node.fingerprint)
                && java.util.Objects.equals(publicKey, node.publicKey)
                && java.util.Objects.equals(credentialHash, node.credentialHash)
                && java.util.Objects.equals(credentialIssuedAt, node.credentialIssuedAt)
                && lifecycle == node.lifecycle
                && schedulable == node.schedulable
                && java.util.Objects.equals(drainRequestedAt, node.drainRequestedAt)
                && java.util.Objects.equals(suspendedAt, node.suspendedAt)
                && suspensionReason == node.suspensionReason
                && java.util.Objects.equals(publicAddress, node.publicAddress)
                && java.util.Objects.equals(dialledEndpoint, node.dialledEndpoint)
                && Arrays.equals(tags, node.tags)
                && desiredGeneration == node.desiredGeneration
                && java.util.Objects.equals(specUpdatedAt, node.specUpdatedAt)
                && java.util.Objects.equals(version, node.version);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, name, lifecycle, desiredGeneration,
                Arrays.hashCode(tags));
    }

    @Override
    public String toString() {
        // Never the credential hash: it is a comparison value, and a log line holding one
        // is a log line worth stealing.
        return "Node[" + name + " " + id + " " + lifecycle + " gen=" + desiredGeneration + "]";
    }
}
