package lhqm.furimeo.wisper.files;

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
 * The directories one service exposes to the file manager.
 *
 * <p>Derived from the same two facts {@code placement.BuildFileRoots} derives the node's
 * copy from - the service's volumes, and whether it is a static site - so the list the
 * browser is shown and the list the node will accept are the same list. Anything else is a
 * file manager that offers a folder the node refuses, or worse, does not offer one it would
 * have allowed.
 *
 * <p>The node's {@code UPLOAD_STAGING} root is deliberately absent. It exists so
 * {@code deploy} can push an archive over the same resumable path, it is not a customer's
 * directory, and putting it in this list would put a "Upload staging" folder in a
 * customer's file manager containing other people's build artefacts.
 *
 * <p>A static site has no volumes and an app has no releases tree, so in practice a service
 * has one kind or the other. The code does not assume that: it lists what is there.
 */
@Component
public class ListFileRoots {

    private final ServiceRepository services;
    private final VolumeRepository volumes;

    public ListFileRoots(ServiceRepository services, VolumeRepository volumes) {
        this.services = services;
        this.volumes = volumes;
    }

    /**
     * Every root this service exposes, in the order the browser should show them.
     *
     * @throws NotFoundException if there is no such service
     */
    @Transactional(readOnly = true)
    public List<FileRootRef> forService(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        List<FileRootRef> roots = new ArrayList<>();
        for (Volume volume : volumes.findByServiceIdOrderByName(serviceId)) {
            roots.add(FileRootRef.volume(volume.id(),
                    volume.name() + " (" + volume.mountPath() + ")",
                    volume.readOnly(), volume.sizeBytes()));
        }
        if (service.isSite()) {
            roots.add(FileRootRef.site(service.id(), service.name() + " releases",
                    service.diskBytes()));
        }
        return List.copyOf(roots);
    }

    /**
     * One root of this service, by the id the browser sent.
     *
     * <p>Empty rather than throwing, so the caller decides whether an unknown root is a 404
     * or a refusal. It is always one of those two and never a pass-through: a root id the
     * panel cannot account for must not be forwarded to a node, because the node checks it
     * against its own spec - which contains every tenant's roots on that machine.
     */
    @Transactional(readOnly = true)
    public Optional<FileRootRef> find(UUID serviceId, String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return Optional.empty();
        }
        String wanted = rootId.strip();
        return forService(serviceId).stream()
                .filter(root -> root.id().equals(wanted))
                .findFirst();
    }

    /**
     * The root the file manager should open on.
     *
     * <p>The first volume if there is one, otherwise the releases tree. A service with
     * neither has nothing to browse, which the page says rather than showing an empty
     * folder that looks like data loss.
     */
    @Transactional(readOnly = true)
    public Optional<FileRootRef> defaultFor(UUID serviceId) {
        return forService(serviceId).stream().findFirst();
    }
}
