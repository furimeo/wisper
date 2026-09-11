package lhqm.furimeo.wisper.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * "Which node runs this service?" - and, if none does yet, decides.
 *
 * <p>Published to {@code service} and {@code deploy} (panel-ports.md §4), which both need
 * an answer before they can do anything: one is starting a workload, the other is handing
 * a build to a machine. Both call it on every attempt, so it has to be idempotent, and it
 * is: an existing binding is returned untouched and nothing is rescheduled.
 *
 * <p>That stickiness is the design, not an optimisation. A service that has storage cannot
 * move without its bytes moving, and a service without storage still has a container, logs
 * and a release tree that a customer expects to find where they left them. Moving between
 * machines is {@link MigrateService}, which somebody has to ask for (design §7.8).
 *
 * <p>The one case where a new binding is chosen despite an existing row is a drain: a
 * placement that is {@link PlacementState#DRAINING} is on its way off that machine, so the
 * machine is excluded from the candidates and the service lands somewhere else.
 */
@Component
public class ChooseNode {

    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final PlacementRepository placements;
    private final ReserveCapacity reserve;
    private final PlaceService placeService;

    public ChooseNode(ServiceRepository services, VolumeRepository volumes,
                      PlacementRepository placements, ReserveCapacity reserve,
                      PlaceService placeService) {
        this.services = services;
        this.volumes = volumes;
        this.placements = placements;
        this.reserve = reserve;
        this.placeService = placeService;
    }

    /**
     * The node holding this service, placing it if nothing is.
     *
     * @throws NotFoundException if there is no such service
     * @throws NoNodeFits        if the fleet cannot take it
     */
    @Transactional
    public UUID forService(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        List<Placement> live = placements.findLiveFor(serviceId);
        Optional<Placement> current = live.stream().filter(Placement::isCurrent).findFirst();
        if (current.isPresent()) {
            return current.get().nodeId();
        }

        List<Volume> storage = volumes.findByServiceIdOrderByName(serviceId);
        ResourceDemand demand = ResourceDemand.of(service, storage);
        List<UUID> draining = new ArrayList<>();
        for (Placement placement : live) {
            draining.add(placement.nodeId());
        }

        UUID nodeId = reserve.bestFit(service, demand, draining);
        placeService.onNode(serviceId, nodeId, !storage.isEmpty(),
                storage.isEmpty()
                        ? "scheduled for " + service.slug()
                        : "scheduled for " + service.slug() + "; pinned by " + storage.size()
                                + " volume(s)");
        return nodeId;
    }
}
