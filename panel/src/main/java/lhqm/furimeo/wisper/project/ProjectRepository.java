package lhqm.furimeo.wisper.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the {@code project} table.
 *
 * <p>Every lookup takes the organization as well as the id. That is not belt and braces:
 * a caller which has resolved a {@code Membership} has proved what it may do <em>in one
 * tenant</em>, and loading a project by primary key alone would happily hand it one from
 * another. Pairing the two in the query means the ownership check and the row it protects
 * cannot come apart.
 *
 * <p>The dashboard's own query is not here - it needs service counts and a join to
 * {@code organization}, and lives in {@link ListProjects}.
 */
public interface ProjectRepository extends ListCrudRepository<Project, UUID> {

    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);
}
