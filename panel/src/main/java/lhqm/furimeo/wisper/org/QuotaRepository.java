package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/** Reads and writes the {@code quota} table: the limits a plan grants. */
public interface QuotaRepository extends ListCrudRepository<Quota, UUID> {

    /**
     * Every limit on a plan.
     *
     * <p>Read whole rather than one row at a time, because the plan page needs all
     * thirteen and {@link EnforceQuota#allowances} would otherwise be thirteen round
     * trips for one screen. A resource missing from this list is limited to zero.
     */
    List<Quota> findByPlanId(UUID planId);

    Optional<Quota> findByPlanIdAndResource(UUID planId, QuotaResource resource);
}
