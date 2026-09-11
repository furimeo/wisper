package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.node.PublishNodeSpec;

/**
 * What happens to a project's services when the project is archived, and when it comes
 * back.
 *
 * <p>{@code project} calls this rather than reaching into the {@code service} table
 * itself. Archiving a service is not something a customer does directly - there is no
 * button for it and no use-case - so the only way the flag is ever set is here, which is
 * why "restore everything archived in this project" is a correct undo rather than a
 * guess at which services were already put away.
 *
 * <p>Archiving sets {@code archived_at} <em>and</em> {@code desired_state = STOPPED},
 * because those two are one intention. The quota measurement excludes archived services
 * from the service, memory and CPU totals; a service that was excluded from the bill and
 * left running on a node would be a hole in the plan, not a feature.
 *
 * <p>Each service republishes its own spec. That is one generation per service rather
 * than one for the project, which is more traffic than strictly necessary and is the
 * shape the seam allows: {@code PublishNodeSpec.forService} is the published call and it
 * takes one service. Archiving a project is rare, and a node that receives four specs in
 * a row reconciles to the last one.
 */
@Component
public class ArchiveProjectServices {

    private final ServiceRepository services;
    private final PublishNodeSpec specs;

    public ArchiveProjectServices(ServiceRepository services, PublishNodeSpec specs) {
        this.services = services;
        this.specs = specs;
    }

    /**
     * Archives every live service in the project and asks its node to stop it.
     *
     * @return how many were archived
     */
    @Transactional
    public int archiveAll(UUID projectId, String reason) {
        Instant now = Instant.now();
        List<Service> live = services.findLiveIn(projectId);
        for (Service service : live) {
            Service archived = services.save(service.archived(now));
            specs.forService(archived.id(), reason);
        }
        return live.size();
    }

    /**
     * Un-archives every archived service in the project. They stay stopped.
     *
     * <p>The spec is republished anyway. Nothing about the workload changed - it was
     * stopped and it still is - but a service coming out of the archive is exactly when a
     * customer looks at the node's view of it, and a republished spec is the cheapest way
     * to be sure the panel and the node agree before they do.
     *
     * @return how many came back
     */
    @Transactional
    public int restoreAll(UUID projectId, String reason) {
        List<Service> archived = services.findArchivedIn(projectId);
        for (Service service : archived) {
            Service live = services.save(service.restored());
            specs.forService(live.id(), reason);
        }
        return archived.size();
    }
}
