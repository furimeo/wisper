package lhqm.furimeo.wisper.service;

import java.util.UUID;

/**
 * Where a service sits: its project, its tenant, and the node currently holding it.
 *
 * <p>Published to {@code deploy}, {@code domain}, {@code files}, {@code stats} and
 * {@code backup} (panel-ports.md §4). All five need the same four ids before they can do
 * anything - the organization to authorise against, the node to send an RPC to, the
 * project for a breadcrumb - and all five would otherwise write the same two-join query.
 *
 * <p>{@code nodeId} is null when nothing is placed yet, which is the normal state of a
 * service created a minute ago and not an error. A caller that needs a node says so with
 * {@code LocateService.onANode}, which throws instead of handing back a null for the
 * caller to forget.
 *
 * @param nodeId the node with the {@code ACTIVE} placement, or null when there is none.
 *               A {@code DRAINING} placement is deliberately not reported here: the
 *               service is being moved, and the answer to "where do I send this" is the
 *               node that will still be holding it afterwards.
 */
public record ServiceLocation(
        UUID serviceId,
        UUID projectId,
        UUID organizationId,
        UUID nodeId,
        String slug,
        String name,
        ServiceKind kind) {

    /** Whether a node is holding this service right now. */
    public boolean isPlaced() {
        return nodeId != null;
    }
}
