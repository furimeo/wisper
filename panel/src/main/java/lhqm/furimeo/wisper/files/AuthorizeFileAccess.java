package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The check every file operation starts with.
 *
 * <p>Three questions in one place, because two of them are easy to answer correctly and
 * forget entirely:
 *
 * <ol>
 * <li><strong>Is this service the caller's?</strong> {@code ResolveMembership.forService}
 *     walks the one join that establishes ownership and throws
 *     {@link NotFoundException} when it does not - so "not yours" and "no such thing" are
 *     the same answer and the id space cannot be enumerated (panel-http.md).</li>
 * <li><strong>Does this root belong to <em>this</em> service?</strong> The root id arrives
 *     from the browser. The node checks it against its own spec, which contains every
 *     tenant's roots on that machine - so without this check a member of one organization
 *     could name another's volume id in their own service's URL and the node would happily
 *     serve it. This is the check that stops that, and it is the reason the root list is
 *     derived panel-side at all.</li>
 * <li><strong>May the caller write?</strong> A {@code VIEWER} may browse and download and
 *     may change nothing, and a static site's releases tree is read-only for everybody -
 *     an edit made in place would be silently reverted by the next deployment.</li>
 * </ol>
 *
 * <p>Reading is allowed to any member. Files are what the customer came for, and a
 * read-only role that cannot look at them is not a useful role.
 */
@Component
public class AuthorizeFileAccess {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final LocateService services;
    private final ListFileRoots roots;

    public AuthorizeFileAccess(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                               LocateService services, ListFileRoots roots) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.roots = roots;
    }

    /**
     * Permission to look.
     *
     * @param rootId the root the browser named, or null for the service's default one
     * @throws NotFoundException if the service is not the caller's, or the root is not the
     *         service's. An unknown root is a 404 rather than a message naming it: telling
     *         a caller that a root id exists somewhere is telling them something
     */
    public FileAccess toRead(UUID serviceId, String rootId, HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        ServiceLocation service = services.byId(serviceId);
        FileRootRef root = rootId == null || rootId.isBlank()
                ? roots.defaultFor(serviceId).orElseThrow(() -> new NotFoundException(
                        service.name() + " has no files: it has no volumes and is not a site."))
                : roots.find(serviceId, rootId)
                        .orElseThrow(() -> NotFoundException.of("file root", rootId));
        return new FileAccess(service, membership, root,
                AuditActor.account(account.id(), account.email(), request));
    }

    /**
     * Permission to change something.
     *
     * @param action the dotted audit action being attempted, so a refusal names what was
     *               refused rather than saying "not allowed"
     * @throws PermissionDenied  for a {@code VIEWER}
     * @throws RequestRejected   for a root that is read-only whatever the caller's role
     */
    public FileAccess toWrite(UUID serviceId, String rootId, String action,
                              HttpServletRequest request) {
        FileAccess access = toRead(serviceId, rootId, request);
        requireWritable(access, action);
        return access;
    }

    /**
     * The write half on its own, for a caller that already resolved the access.
     *
     * <p>A form controller needs the {@link FileAccess} before it can report a refusal - the
     * audit entry names the service and the actor - so it resolves read access first and
     * calls this inside the {@code try}. Otherwise a {@code VIEWER} pressing delete would
     * produce a 403 page and no {@code DENIED} entry, which is the one the incident is
     * reconstructed from.
     *
     * @throws PermissionDenied for a {@code VIEWER}
     * @throws RequestRejected  for a root that is read-only whatever the caller's role
     */
    public void requireWritable(FileAccess access, String action) {
        access.membership().requireWrite(action);
        if (!access.root().writable()) {
            throw new RequestRejected(null, access.root().label() + " is read-only. A static "
                    + "site's files come from its last build, and an edit made here would be "
                    + "replaced by the next deployment.");
        }
    }
}
