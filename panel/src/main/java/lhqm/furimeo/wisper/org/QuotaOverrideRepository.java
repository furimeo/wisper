package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/** Reads and writes the {@code quota_override} table: per-organization exceptions. */
public interface QuotaOverrideRepository extends ListCrudRepository<QuotaOverride, UUID> {

    /**
     * Every override on the organization, expired ones included.
     *
     * <p>Expiry is applied in {@link EnforceQuota} rather than in SQL so that the admin
     * screen can show an exception that has lapsed - which is the one an operator most
     * often wants to extend - instead of it silently vanishing from the list.
     */
    List<QuotaOverride> findByOrganizationId(UUID organizationId);

    Optional<QuotaOverride> findByOrganizationIdAndResource(UUID organizationId,
                                                           QuotaResource resource);
}
