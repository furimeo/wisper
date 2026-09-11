package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.UUID;

/**
 * Answers "is this organization allowed one more?" before anything is created.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.org.EnforceQuota}. Declared as an
 * interface because six packages - {@code project}, {@code service}, {@code domain},
 * {@code database}, {@code backup}, {@code files} - are written against it in parallel
 * with it, and because it is worth being able to state the resolution order in one place
 * that every one of them reads.
 *
 * <h2>Resolution order, and the rule that matters</h2>
 *
 * <ol>
 * <li>An unexpired {@code quota_override} row for the organization, if there is one.</li>
 * <li>Otherwise the {@code quota} row on the organization's plan.</li>
 * <li>Otherwise <strong>zero</strong>.</li>
 * </ol>
 *
 * <p>Step three is the whole design. A resource with no row is limited to zero, never to
 * unlimited: reading a missing row as "no limit" means the day somebody adds a plan and
 * forgets to seed one line, that plan is unmetered and nobody finds out until a node
 * fills up.
 *
 * <h2>Where to call it</h2>
 *
 * <p>{@link #require} goes in the use-case, inside the transaction that does the write,
 * before the insert. Not in the controller - an API route and a form route would then
 * each need their own copy - and not after the insert, where rolling back is a race with
 * whatever else is being created at the same moment.
 *
 * <p>A suspended organization is refused everything: {@link #require} throws for any
 * resource when {@code organization.status = 'SUSPENDED'}, because suspension means
 * "no new commitments" and enumerating that at every call site is how one gets missed.
 * Suspension does not stop what is already running - a customer's containers keep
 * serving - it stops the panel writing anything new for them.
 */
public interface QuotaGuard {

    /**
     * Refuses the request if it would take the organization over its limit.
     *
     * @param amount how much is being asked for, in the resource's unit: 1 for a count,
     *               a byte figure for {@code VOLUME_BYTES}. When something is being
     *               resized, pass the increase and pass nothing at all when it shrinks.
     * @throws QuotaExceeded if the limit would be exceeded, or the organization is
     *                       suspended
     * @throws lhqm.furimeo.wisper.web.NotFoundException if there is no such organization
     */
    void require(UUID organizationId, QuotaResource resource, long amount);

    /**
     * The limit and the current usage for one resource, without refusing anything.
     *
     * <p>For the screen that shows a usage bar and for the caller that would rather
     * disable a button than show an error after it is pressed.
     */
    QuotaAllowance allowanceFor(UUID organizationId, QuotaResource resource);

    /**
     * Every resource at once, in {@link QuotaResource} declaration order.
     *
     * <p>The plan page needs all thirteen and asking thirteen times is thirteen round
     * trips for one screen. Always returns a row per resource, including the ones at
     * zero - a plan page that silently omits what you cannot have is a plan page that
     * cannot be compared with another plan.
     */
    List<QuotaAllowance> allowances(UUID organizationId);
}
