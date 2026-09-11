package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code node} table. */
public interface NodeRepository extends ListCrudRepository<Node, UUID> {

    Optional<Node> findByName(String name);

    boolean existsByName(String name);

    /**
     * The lookup behind every single gRPC call a node makes, which is why
     * {@code node_credential_hash_key} exists and is the hottest index in the schema.
     */
    Optional<Node> findByCredentialHash(String credentialHash);

    /**
     * The clone detector's read half. A fingerprint arriving from a machine that is not
     * the one that enrolled it finds the original here (design §7.3).
     */
    Optional<Node> findByFingerprint(String fingerprint);

    /** The admin list. Ordered by name because that is what an operator scans by. */
    @Query("SELECT * FROM node ORDER BY name")
    List<Node> findAllByName();

    /**
     * The scheduler's candidate set, which is exactly the predicate
     * {@code node_placeable_idx} was made partial for.
     */
    @Query("SELECT * FROM node WHERE schedulable AND lifecycle = 'ENROLLED' ORDER BY name")
    List<Node> findPlaceable();

    /**
     * Takes the next generation for one node and returns it, in a single statement.
     *
     * <p>An increment written as read-then-write would let two publishers - a deployment
     * and a domain change landing together - read the same value and publish two different
     * specs under one generation, which the node would resolve by applying whichever
     * arrived second and then reporting convergence on a spec nobody sent.
     * {@code UPDATE ... RETURNING} makes the counter atomic without a lock the rest of
     * the row has to queue behind.
     *
     * <p>It deliberately leaves {@code version} alone: this is the panel's own counter,
     * not a user-visible edit, and routing every concurrent publisher through an
     * optimistic-lock failure would turn a normal double publish into a retry loop.
     */
    @Query("""
            UPDATE node
               SET desired_generation = desired_generation + 1,
                   spec_updated_at    = :at,
                   updated_at         = :at
             WHERE id = :nodeId
             RETURNING desired_generation
            """)
    Long bumpGeneration(@Param("nodeId") UUID nodeId, @Param("at") Instant at);

    /** What the panel last published, read without loading the whole row. */
    @Query("SELECT desired_generation FROM node WHERE id = :nodeId")
    Optional<Long> desiredGenerationOf(@Param("nodeId") UUID nodeId);

    /** Nodes an operator has to look at: suspended, or draining and not finished. */
    @Query("""
            SELECT * FROM node
             WHERE lifecycle IN ('SUSPENDED', 'DRAINING')
             ORDER BY COALESCE(suspended_at, drain_requested_at) DESC
            """)
    List<Node> findNeedingAttention();

    long countByLifecycle(NodeLifecycle lifecycle);
}
