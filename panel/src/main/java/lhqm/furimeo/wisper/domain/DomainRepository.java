package lhqm.furimeo.wisper.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code domain} table. */
public interface DomainRepository extends ListCrudRepository<Domain, UUID> {

    /**
     * Every hostname on a service, primary first and then alphabetical.
     *
     * <p>Primary first because that is the one the customer thinks of as the address; the
     * rest are alphabetical so the list does not reshuffle itself when a certificate is
     * renewed.
     */
    @Query("""
            SELECT * FROM domain
             WHERE service_id = :serviceId
             ORDER BY (kind = 'PRIMARY') DESC, hostname
            """)
    List<Domain> findByServiceId(@Param("serviceId") UUID serviceId);

    /**
     * The global lookup {@code domain_hostname_key} exists for.
     *
     * <p>Not scoped to an organization, on purpose: the whole point of the index is that a
     * hostname belongs to exactly one service across the entire platform.
     */
    Optional<Domain> findByHostname(String hostname);

    boolean existsByHostname(String hostname);

    /** The one {@code domain_primary_per_service_idx} allows, if the service has one. */
    @Query("SELECT * FROM domain WHERE service_id = :serviceId AND kind = 'PRIMARY'")
    Optional<Domain> findPrimaryOf(@Param("serviceId") UUID serviceId);

    long countByServiceId(UUID serviceId);

    /**
     * A domain, but only if it hangs off a service this organization owns.
     *
     * <p>The ownership join written once rather than in each of the four use-cases, and the
     * reason none of them takes an organization id and a domain id and hopes the caller
     * checked. An empty result is a {@code NotFoundException}, which is the same answer a
     * domain that does not exist produces.
     */
    @Query("""
            SELECT d.* FROM domain d
              JOIN service s ON s.id = d.service_id
              JOIN project p ON p.id = s.project_id
             WHERE d.id = :domainId
               AND d.service_id = :serviceId
               AND p.organization_id = :organizationId
            """)
    Optional<Domain> findOwnedBy(@Param("domainId") UUID domainId,
                                 @Param("serviceId") UUID serviceId,
                                 @Param("organizationId") UUID organizationId);

    /**
     * Where customers' DNS has to point for this hostname: the public address of the node
     * with the service's {@code ACTIVE} placement.
     *
     * <p>Empty when nothing holds the service yet, which is the normal state of a service
     * created a minute ago. The panel never dials this address; it only tells the customer
     * what to put in an A record.
     */
    @Query("""
            SELECT n.public_address
              FROM domain d
              JOIN placement pl ON pl.service_id = d.service_id AND pl.state = 'ACTIVE'
              JOIN node n ON n.id = pl.node_id
             WHERE d.id = :domainId
            """)
    Optional<String> findNodeAddress(@Param("domainId") UUID domainId);

    /**
     * The node with a service's {@code ACTIVE} placement.
     *
     * <p>{@code placement_one_active_per_service_idx} is unique and partial on that state, so
     * this is at most one row and there is no {@code LIMIT} papering over a duplicate. A
     * {@code DRAINING} placement is deliberately not the answer: the customer is being told
     * where to point DNS, and that is the machine that will still be holding the service
     * afterwards.
     */
    @Query("SELECT node_id FROM placement WHERE service_id = :serviceId AND state = 'ACTIVE'")
    Optional<UUID> findActiveNodeOf(@Param("serviceId") UUID serviceId);

    /** That node's public address, which is what the customer puts in an A record. */
    @Query("""
            SELECT n.public_address
              FROM placement pl
              JOIN node n ON n.id = pl.node_id
             WHERE pl.service_id = :serviceId
               AND pl.state = 'ACTIVE'
            """)
    Optional<String> findActiveNodeAddressOf(@Param("serviceId") UUID serviceId);

    /**
     * Every hostname a node is currently expected to answer for, keyed the way a status
     * report names them.
     *
     * <p>The states match {@code PlacementRepository.findLiveOn}, which is what
     * {@code BuildRoutes} sends, so a node is asked about exactly the routes it was given -
     * and a report naming anything else is ignored rather than written.
     */
    @Query("""
            SELECT d.* FROM domain d
              JOIN placement pl ON pl.service_id = d.service_id
             WHERE pl.node_id = :nodeId
               AND pl.state IN ('PLANNED', 'ACTIVE', 'DRAINING')
            """)
    List<Domain> findRoutedTo(@Param("nodeId") UUID nodeId);
}
