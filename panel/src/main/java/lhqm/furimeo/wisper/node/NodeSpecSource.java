package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lhqm.furimeo.wisper.proto.v1.NodeSpec;

/**
 * Turns the database into the document a node must converge to.
 *
 * <p>This is the seam between "what the panel has decided" and "what gets published".
 * The counter and the delivery belong to {@code node}, which owns the {@code node} row
 * and the control stream; the content belongs to {@code placement}, which is the package
 * that knows which service runs where and can read across {@code service},
 * {@code volume}, {@code domain}, {@code env_var}, {@code secret}, {@code cron_task} and
 * {@code managed_database} to assemble it.
 *
 * <p>The interface is declared here and implemented in
 * {@code lhqm.furimeo.wisper.placement.BuildNodeSpec} for the ordinary
 * dependency-inversion reason: {@code placement} already depends on {@code node}, so the
 * dependency has to run in that direction or the two packages cannot both compile.
 *
 * <p>Everything about a spec is whole. There is no delta anywhere in this contract: a
 * node that has been unreachable for a week is caught up by one document, and a node
 * that reconnects twice in a second is sent the same document twice and reconciles to
 * the same place (design §5.2). See {@code docs/contracts/node-spec.md} for the
 * row-by-row mapping and the generation rules.
 */
public interface NodeSpecSource {

    /**
     * Builds the complete desired state for one node.
     *
     * <p>The caller has already bumped {@code node.desired_generation} and passes the
     * new value in, so the counter has exactly one owner and a spec can never be built
     * with a generation that was not actually recorded. {@code issuedAt} is likewise
     * supplied rather than read from the clock here, so the value stored on the row and
     * the value on the wire are the same instant.
     *
     * <p>Returns a spec with empty lists rather than throwing when the node holds
     * nothing: an empty spec is a legitimate instruction and means "run nothing", which
     * is exactly what a drained node should be told.
     *
     * @throws lhqm.furimeo.wisper.web.NotFoundException if there is no such node
     */
    NodeSpec buildSpec(UUID nodeId, long generation, Instant issuedAt);

    /**
     * The nodes whose spec would change if this service changed.
     *
     * <p>Every write that touches a service or one of its children - an environment
     * variable, a volume, a domain, a cron entry - has to republish a spec, and none of
     * those packages may depend on {@code placement} to work out which one. In v1 the
     * answer is at most one node, but it is a list because a placement being migrated
     * has a {@code DRAINING} row and an {@code ACTIVE} row at the same time, and both
     * nodes need telling.
     *
     * <p>Empty when the service has never been placed. That is not an error: a service
     * created a moment ago has nowhere to publish to yet, and the scheduler will publish
     * when it places it.
     */
    List<UUID> nodesHosting(UUID serviceId);
}
