package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/** Reads and writes the {@code plan} table. */
public interface PlanRepository extends ListCrudRepository<Plan, UUID> {

    Optional<Plan> findByCode(String code);

    /**
     * The plan a new organization gets when nobody picks one.
     *
     * <p>Empty is a real answer and {@link CreateOrganization} refuses rather than
     * inventing one: a tenant on no plan has every limit at zero, which looks like a
     * broken panel rather than like a missing seed row.
     */
    @Query("SELECT * FROM plan WHERE is_default LIMIT 1")
    Optional<Plan> findDefault();

    /** The plans an operator may still put somebody on. */
    @Query("SELECT * FROM plan WHERE archived_at IS NULL ORDER BY name")
    List<Plan> findSelectable();

    /** Every plan, archived ones last, for the admin screen. */
    @Query("SELECT * FROM plan ORDER BY (archived_at IS NOT NULL), name")
    List<Plan> findAllSelectableFirst();
}
