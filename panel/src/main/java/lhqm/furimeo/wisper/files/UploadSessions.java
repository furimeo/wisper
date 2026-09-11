package lhqm.furimeo.wisper.files;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The uploads this panel process is currently tracking.
 *
 * <p>What it is for: turning a chunk request into four fields instead of eight, and knowing
 * which uploads have been abandoned so the node can be told to drop their parts rather than
 * waiting out its own TTL.
 *
 * <h2>Why it is in memory, and why losing it is survivable</h2>
 *
 * <p>There is no {@code upload_session} table in the schema, deliberately: the authoritative
 * state of a partial upload is the bytes on the node's disk, and a second copy in PostgreSQL
 * would be a second thing that can disagree. What lives here is only the metadata needed to
 * address that state.
 *
 * <p>So a panel restart loses the registry, and the recovery path is the same one a
 * customer's phone already exercises. The browser asks for the session; the panel does not
 * know it; the answer is {@code known = false}; the browser calls
 * {@link BeginUpload} again with the metadata it still has, and <em>that</em> asks the node,
 * which does know, and returns the ranges already on disk. The upload resumes. Nothing was
 * lost except a round trip - which is exactly the design's own answer to a dropped
 * connection, reused for a dropped panel.
 *
 * <p>Keyed by service <em>and</em> session id. The browser mints the session id, so two
 * customers can produce the same one; without the service in the key, one of them would find
 * the other's upload.
 */
@Component
public class UploadSessions {

    private final Map<Key, UploadTicket> tickets = new ConcurrentHashMap<>();

    /** Remembers a session, replacing anything under the same key. */
    public UploadTicket register(UploadTicket ticket) {
        tickets.put(new Key(ticket.serviceId(), ticket.sessionId()), ticket);
        return ticket;
    }

    /** The session, if this panel knows it. */
    public Optional<UploadTicket> find(UUID serviceId, String sessionId) {
        if (serviceId == null || sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tickets.get(new Key(serviceId, sessionId)));
    }

    /**
     * The session, insisting it is known.
     *
     * @throws UploadSessionUnknown when it is not, which the client recovers from by
     *         starting the session again rather than by showing an error
     */
    public UploadTicket require(UUID serviceId, String sessionId) {
        return find(serviceId, sessionId)
                .orElseThrow(() -> new UploadSessionUnknown(sessionId));
    }

    /** Records that a chunk arrived, so the sweep leaves this one alone. */
    public UploadTicket touch(UploadTicket ticket, Instant at) {
        return register(ticket.touched(at));
    }

    /** Drops a session that has finished or been abandoned. */
    public void forget(UUID serviceId, String sessionId) {
        tickets.remove(new Key(serviceId, sessionId));
    }

    /** Every session that has had no chunk since the cut-off. */
    public List<UploadTicket> idleSince(Instant cutoff) {
        return tickets.values().stream()
                .filter(ticket -> ticket.isIdleSince(cutoff))
                .toList();
    }

    /** How many uploads are in flight. For the sweep's log line and for tests. */
    public int tracked() {
        return tickets.size();
    }

    private record Key(UUID serviceId, String sessionId) {
    }
}
