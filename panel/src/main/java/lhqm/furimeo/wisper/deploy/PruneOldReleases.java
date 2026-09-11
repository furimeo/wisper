package lhqm.furimeo.wisper.deploy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceRepository;

/**
 * Removes the deployment rows for releases that can no longer be rolled back to.
 *
 * <p>The node is the one that deletes bytes. It keeps
 * {@code RetentionPolicy.keep_releases} directories under {@code releases/} and sweeps the
 * rest, and it reports which ones it removed on the {@code BuildCompleted} that caused the
 * sweep. A row for a directory that no longer exists is a rollback button that fails, and
 * a button that fails is worse than no button - so the row goes with the directory.
 *
 * <p>Two inputs, because there are two ways to learn a release is gone: the node saying so,
 * and the panel applying the same policy the node was given. The second exists for the
 * deploys where no {@code BuildCompleted} arrives at all - a rollback, an app - and for the
 * gap between a node pruning and the panel hearing about it.
 *
 * <h2>What is never pruned</h2>
 *
 * <ul>
 * <li>The live deployment, whatever its position in the list.</li>
 * <li>The release a live <strong>rollback</strong> points back at, which is where the bytes
 *     actually are. A customer who rolled back to release three and then deployed six more
 *     times is being served by a release nowhere near the top of the list.</li>
 * <li>Anything still in flight, which has no release yet and is about to.</li>
 * <li>An app's history. An app has no release directory, so nothing on a node has gone
 *     away, and deleting the rows would only delete the record of what was deployed when.</li>
 * </ul>
 */
@Component
public class PruneOldReleases {

    private static final Logger log = LoggerFactory.getLogger(PruneOldReleases.class);

    private final DeploymentRepository deployments;
    private final ServiceRepository services;
    private final StoreDeploymentArchive archives;

    public PruneOldReleases(DeploymentRepository deployments, ServiceRepository services,
                            StoreDeploymentArchive archives) {
        this.deployments = deployments;
        this.services = services;
        this.archives = archives;
    }

    /**
     * Brings the panel's release history in line with what the node keeps.
     *
     * @param removedByNode release ids from {@code BuildCompleted.pruned_releases}. A
     *                      release id is the deployment id that produced it, so these are
     *                      deployment ids in string form; anything unparseable is ignored
     *                      rather than allowed to fail a deployment that worked
     * @return how many rows were deleted, for the log line and for tests
     */
    @Transactional
    public int forService(UUID serviceId, Collection<String> removedByNode) {
        Optional<Service> found = services.findById(serviceId);
        if (found.isEmpty()) {
            return 0;
        }
        Service service = found.get();

        Set<Deployment> doomed = new LinkedHashSet<>();
        if (service.kind() == ServiceKind.SITE) {
            doomed.addAll(deployments.findPrunable(serviceId, service.keepReleases()));
        }
        doomed.addAll(namedByNode(serviceId, removedByNode));

        List<Deployment> removable = new ArrayList<>();
        UUID protectedByRollback = deployments.findCurrent(serviceId)
                .map(Deployment::rolledBackFromDeploymentId)
                .orElse(null);
        for (Deployment candidate : doomed) {
            if (candidate.isCurrent() || candidate.isInFlight()
                    || candidate.id().equals(protectedByRollback)) {
                continue;
            }
            removable.add(candidate);
        }
        if (removable.isEmpty()) {
            return 0;
        }

        for (Deployment gone : removable) {
            // The panel's copy of an uploaded zip outlives nothing: the release it
            // produced is being forgotten, so there is nothing left to rebuild from it.
            archives.discard(gone.archivePath());
        }
        // Cascades deployment_log, which is the only child and is what makes this one
        // statement rather than two.
        deployments.deleteAll(removable);
        log.debug("Pruned {} release rows for service {}", removable.size(), serviceId);
        return removable.size();
    }

    /**
     * The rows for the release ids a node says it deleted, filtered to this service.
     *
     * <p>Filtered because a release id arrives as a string off the wire, and a node that
     * named a deployment belonging to somebody else's service must not be able to delete
     * it.
     */
    private List<Deployment> namedByNode(UUID serviceId, Collection<String> releaseIds) {
        if (releaseIds == null || releaseIds.isEmpty()) {
            return List.of();
        }
        List<Deployment> named = new ArrayList<>();
        for (String releaseId : releaseIds) {
            UUID id = parse(releaseId);
            if (id == null) {
                log.debug("Node reported a pruned release that is not a deployment id: {}",
                        releaseId);
                continue;
            }
            deployments.findById(id)
                    .filter(deployment -> deployment.serviceId().equals(serviceId))
                    .ifPresent(named::add);
        }
        return named;
    }

    private static UUID parse(String releaseId) {
        if (releaseId == null || releaseId.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(releaseId);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
