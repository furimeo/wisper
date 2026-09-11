package lhqm.furimeo.wisper.files;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.AbortUpload;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * Abandons uploads nobody is continuing.
 *
 * <p>A phone that lost signal in a lift and never came back leaves parts on a node. The node
 * sweeps them itself after {@code RetentionPolicy.orphan_upload_ttl_seconds}, which is the
 * backstop and works even if the panel is gone - but that TTL is a day, and a day of a
 * customer's quota spent on an upload that was abandoned twenty minutes ago is a customer
 * who cannot upload anything else.
 *
 * <p>So the panel, which knows when each session it registered last received a chunk, tells
 * the node to drop the parts as soon as the session has been quiet for
 * {@code wisper.files.upload-idle-timeout}. Belt and braces, with the braces on the side
 * that survives the panel dying.
 *
 * <p>A node that is offline is skipped and its ticket kept: the session may resume when the
 * node comes back, and if it does not, the node's own TTL takes the parts. "Cannot see it"
 * is not "does not exist" (AGENTS.md §4.5).
 */
@Component
public class SweepStaleUploads {

    private static final Logger log = LoggerFactory.getLogger(SweepStaleUploads.class);

    private final DispatchFileRequest dispatch;
    private final UploadSessions sessions;
    private final FilesSettings settings;
    private final Clock clock;

    @Autowired
    public SweepStaleUploads(DispatchFileRequest dispatch, UploadSessions sessions,
                             FilesSettings settings) {
        this(dispatch, sessions, settings, Clock.systemUTC());
    }

    SweepStaleUploads(DispatchFileRequest dispatch, UploadSessions sessions,
                      FilesSettings settings, Clock clock) {
        this.dispatch = dispatch;
        this.sessions = sessions;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Drops every session that has been quiet too long.
     *
     * @return how many were abandoned
     */
    public int sweep() {
        Instant cutoff = Instant.now(clock).minus(settings.uploadIdleTimeout());
        List<UploadTicket> stale = sessions.idleSince(cutoff);
        int abandoned = 0;
        for (UploadTicket ticket : stale) {
            if (abandon(ticket)) {
                abandoned++;
            }
        }
        if (abandoned > 0) {
            log.info("Upload sweep: abandoned {} of {} idle session(s); {} still in flight",
                    abandoned, stale.size(), sessions.tracked());
        }
        return abandoned;
    }

    private boolean abandon(UploadTicket ticket) {
        try {
            dispatch.callOnNode(ticket.nodeId(), ticket.rootId(), FileRequest.newBuilder()
                    .setAbort(AbortUpload.newBuilder().setSessionId(ticket.nodeSessionId())));
            sessions.forget(ticket.serviceId(), ticket.sessionId());
            return true;
        } catch (NodeOffline away) {
            // The node will sweep the parts itself when it comes back and the TTL passes.
            // Keeping the ticket means the customer can still resume if it returns sooner.
            log.debug("Node {} is away; leaving upload {} for its own sweeper", ticket.nodeId(),
                    ticket.sessionId());
            return false;
        } catch (FileOperationFailed | FileOperationTimedOut refused) {
            // The node has already forgotten it, or could not answer. Either way this panel
            // has nothing left to do with the ticket.
            log.debug("Node {} did not abandon upload {}: {}", ticket.nodeId(),
                    ticket.sessionId(), refused.getMessage());
            sessions.forget(ticket.serviceId(), ticket.sessionId());
            return false;
        }
    }
}
