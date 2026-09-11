package lhqm.furimeo.wisper.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code certificate} table. Only the node's reports write it. */
public interface CertificateRepository extends ListCrudRepository<Certificate, UUID> {

    /**
     * The one row {@code certificate_live_per_domain_idx} allows in force at a time.
     *
     * <p>Superseded and revoked rows stay for the history, so this is the only query that
     * answers "what is being served right now".
     */
    @Query("""
            SELECT * FROM certificate
             WHERE domain_id = :domainId
               AND state IN ('ISSUED', 'RENEWING')
            """)
    Optional<Certificate> findLiveFor(@Param("domainId") UUID domainId);

    /**
     * The most recent row for a hostname, live or not.
     *
     * <p>What the panel shows when there is nothing live: a customer whose issuance keeps
     * failing needs the error, and a screen that renders nothing because the only row is
     * {@code FAILED} is the blank frame this project exists to avoid.
     *
     * <p>Also where a report is applied when there is no live row, which is what stops a
     * hostname accumulating a new {@code PENDING} row every fifteen seconds.
     */
    @Query("""
            SELECT * FROM certificate
             WHERE domain_id = :domainId
             ORDER BY updated_at DESC, created_at DESC
             LIMIT 1
            """)
    Optional<Certificate> findNewestFor(@Param("domainId") UUID domainId);

    /**
     * Every certificate row belonging to a set of hostnames, in one statement.
     *
     * <p>The domains page shows one certificate per hostname and a service can have a dozen
     * hostnames; asking twice per hostname would be two dozen round trips for one screen.
     * The picking is done in Java by {@link ListServiceDomains}, which is the only caller.
     */
    List<Certificate> findByDomainIdIn(Collection<UUID> domainIds);
}
