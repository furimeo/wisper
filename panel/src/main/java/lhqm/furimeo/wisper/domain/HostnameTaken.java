package lhqm.furimeo.wisper.domain;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Somebody already has this hostname.
 *
 * <p>{@code domain_hostname_key} is global and not per organization, and that is the
 * single most important line in V18: if two tenants could both hold {@code example.com},
 * whichever service the node's route table happened to load last would answer for it, and
 * one customer would be serving another's traffic and obtaining certificates in their
 * name. The uniqueness is the defence; this exception is what it feels like from the form.
 *
 * <p>A {@link RequestRejected}, so every controller's existing refusal path renders it
 * under the {@code hostname} input and records a {@code DENIED} audit entry with no extra
 * catch block.
 *
 * <h2>What the message may and may not say</h2>
 *
 * <p>It says the name is taken. It does not say by whom, does not name the other service
 * and does not say whether the holder is in the same organization: an error that
 * distinguishes "taken by you" from "taken by somebody else" is a way to enumerate which
 * hostnames the platform hosts. The customer's next step is the same either way - use a
 * different name, or ask an operator, who can see the whole table.
 */
public class HostnameTaken extends RequestRejected {

    private final String hostname;

    public HostnameTaken(String hostname) {
        super("hostname", hostname + " is already in use on this platform. A hostname belongs to "
                + "exactly one service, so it has to be released before it can be added again. "
                + "If it is yours, remove it from the service that holds it first.");
        this.hostname = hostname;
    }

    /** The canonical hostname that was refused. */
    public String hostname() {
        return hostname;
    }
}
