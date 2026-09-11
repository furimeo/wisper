package lhqm.furimeo.wisper.service;

import java.util.UUID;

/**
 * Nothing is holding this service yet, and the thing being asked for needs a node.
 *
 * <p>A terminal, a file listing, a log stream and a build all have to be sent somewhere.
 * A service that has never been started has no {@code ACTIVE} placement, which is an
 * ordinary state and not a fault - so this is a distinct exception rather than a
 * {@code NotFoundException}: the service exists, the customer may see it, and the answer
 * is "start it first" rather than "no such thing".
 *
 * <p>Controllers catch it, write the message with {@code InertiaFlash.failure} and
 * redirect back to the overview, where the start button is.
 */
public class ServiceNotPlaced extends RuntimeException {

    private final UUID serviceId;

    public ServiceNotPlaced(UUID serviceId, String name) {
        super((name == null || name.isBlank() ? "This service" : name)
                + " is not running on a node yet, so there is nothing to reach. "
                + "Start it and try again.");
        this.serviceId = serviceId;
    }

    public UUID serviceId() {
        return serviceId;
    }
}
