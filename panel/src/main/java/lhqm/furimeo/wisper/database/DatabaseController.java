package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The customer's databases: {@code /databases} and everything under it.
 *
 * <p>Flat rather than nested under a project, which is the opposite of what
 * {@code /services/{id}/…} does, and deliberately. A database is reached from three places
 * - the project it belongs to, a service that connects to it, and the list a customer opens
 * to find the connection string on their phone - and the thing they have in hand is the
 * database, not the path to it. The authorization check has something to check anyway:
 * every method here resolves the project first and asks {@code org.ResolveMembership} about
 * that, so an id belonging to somebody else is a 404 from the same place every other
 * dangling reference is.
 *
 * <p>Creation is the exception and takes a {@code projectId}, because a database has to
 * belong to something before it exists.
 *
 * <h2>Where the credentials appear</h2>
 *
 * <p>Two routes produce a password - reveal and rotate - and both flash it to the detail
 * page rather than returning it from a POST. Nothing else on any of these screens contains
 * one: the list and the detail props are {@link ManagedDatabaseView}, which has no password
 * field at all, so a customer who merely opened the page has not put a credential in their
 * browser's history.
 */
@Controller
public class DatabaseController {

    /** The flash prop the detail page renders its credentials panel from, when present. */
    private static final String CONNECTION_PROP = "connection";

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ManagedDatabaseRepository databases;
    private final ListDatabases listing;
    private final ProvisionDatabase provisionDatabase;
    private final RotateDatabasePassword rotatePassword;
    private final DropDatabase dropDatabase;
    private final ShowConnectionString connectionStrings;
    private final SetDatabaseQuota setQuota;
    private final QuotaGuard quotas;
    private final RecordRefusal refusals;

    public DatabaseController(ResolveCurrentAccount currentAccount,
                              ResolveMembership memberships,
                              ManagedDatabaseRepository databases, ListDatabases listing,
                              ProvisionDatabase provisionDatabase,
                              RotateDatabasePassword rotatePassword, DropDatabase dropDatabase,
                              ShowConnectionString connectionStrings, SetDatabaseQuota setQuota,
                              QuotaGuard quotas, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.databases = databases;
        this.listing = listing;
        this.provisionDatabase = provisionDatabase;
        this.rotatePassword = rotatePassword;
        this.dropDatabase = dropDatabase;
        this.connectionStrings = connectionStrings;
        this.setQuota = setQuota;
        this.quotas = quotas;
        this.refusals = refusals;
    }

    /**
     * Every database this person can reach, across every organization they belong to.
     *
     * <p>Props: {@code databases} - a {@link ManagedDatabaseView} each, ordered by
     * organization then project then name; {@code engines} - the two {@link EngineKind}
     * values, so the create form does not hard-code them.
     *
     * <p>Renders {@code features/database/DatabaseListPage.tsx}.
     */
    @GetMapping("/databases")
    public String list(Model model) {
        AccountRef account = currentAccount.require();
        model.addAttribute("databases", listing.visibleTo(account.id()));
        model.addAttribute("engines", EngineKind.values());
        return "database/DatabaseList";
    }

    /**
     * One database: usage, state, and the buttons that act on it.
     *
     * <p>Props: {@code database}, {@code viewerRole}, {@code databaseAllowance} - the last
     * so the page can say how many more the plan permits - and {@code connection} when the
     * previous request was a reveal or a rotation.
     *
     * <p>Renders {@code features/database/DatabaseDetailPage.tsx}.
     */
    @GetMapping("/databases/{databaseId}")
    public String detail(@PathVariable UUID databaseId, Model model) {
        Membership membership = resolve(databaseId);
        model.addAttribute("database", listing.one(membership.organizationId(), databaseId)
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId)));
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("databaseAllowance", quotas.allowanceFor(membership.organizationId(),
                QuotaResource.MANAGED_DATABASE));
        return "database/DatabaseDetail";
    }

    /**
     * Create one.
     *
     * @param engine    {@code POSTGRES} or {@code MYSQL}
     * @param dedicated whether to put it on this organization's own engine container
     */
    @PostMapping("/databases")
    public String create(@RequestParam("projectId") UUID projectId,
                         @RequestParam("engine") String engine,
                         @RequestParam("name") String name,
                         @RequestParam(name = "quotaBytes", defaultValue = "0") long quotaBytes,
                         @RequestParam(name = "dedicated", defaultValue = "false")
                         boolean dedicated,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forProject(account.id(), projectId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        EngineKind kind = EngineKind.parse(engine);
        try {
            if (kind == null) {
                throw new RequestRejected("engine", "Choose PostgreSQL or MySQL.");
            }
            ManagedDatabase created = provisionDatabase.create(actor, membership, projectId, kind,
                    name, quotaBytes, dedicated);
            InertiaFlash.success(flash, "Creating " + created.name()
                    + ". Its connection details will be here as soon as the node confirms it.");
            return "redirect:/databases/" + created.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "database.create",
                    AuditTarget.unidentified("managed_database", name),
                    membership.organizationId(), refused, flash);
            return "redirect:/databases";
        }
    }

    /** Show the connection details, once, and record that they were shown. */
    @PostMapping("/databases/{databaseId}/reveal")
    public String reveal(@PathVariable UUID databaseId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = resolve(databaseId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            flash.addFlashAttribute(CONNECTION_PROP,
                    connectionStrings.reveal(actor, membership, databaseId));
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "database.reveal_credentials",
                    AuditTarget.of("managed_database", databaseId, databaseId.toString()),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/databases/" + databaseId;
    }

    /** New password, old one revoked. */
    @PostMapping("/databases/{databaseId}/password")
    public String rotate(@PathVariable UUID databaseId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = resolve(databaseId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            ConnectionString rotated = rotatePassword.rotate(actor, membership, databaseId);
            flash.addFlashAttribute(CONNECTION_PROP, rotated);
            InertiaFlash.success(flash, "The password has been changed. The previous one no "
                    + "longer works, so update anything using it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "database.rotate_password",
                    AuditTarget.of("managed_database", databaseId, databaseId.toString()),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/databases/" + databaseId;
    }

    /** Change how large it may get. */
    @PostMapping("/databases/{databaseId}/quota")
    public String quota(@PathVariable UUID databaseId,
                        @RequestParam("quotaBytes") long quotaBytes,
                        HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = resolve(databaseId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            ManagedDatabase updated = setQuota.set(actor, membership, databaseId, quotaBytes);
            InertiaFlash.success(flash, "The limit for " + updated.name() + " is now "
                    + updated.quotaBytes() + " bytes.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "database.set_quota",
                    AuditTarget.of("managed_database", databaseId, databaseId.toString()),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/databases/" + databaseId;
    }

    /** Drop it, its login and its data. */
    @PostMapping("/databases/{databaseId}/delete")
    public String delete(@PathVariable UUID databaseId,
                         @RequestParam(name = "confirmation", required = false)
                         String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = resolve(databaseId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            dropDatabase.drop(actor, membership, databaseId, confirmation);
            InertiaFlash.success(flash, "The database has been dropped.");
            return "redirect:/databases";
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "database.delete",
                    AuditTarget.of("managed_database", databaseId, databaseId.toString()),
                    membership.organizationId(), refused, flash);
            return "redirect:/databases/" + databaseId;
        }
    }

    /**
     * The membership that governs a database, resolved through the project it belongs to.
     *
     * <p>Two steps and both of them matter. Reading the project id is a lookup in this
     * package's own table; deciding what the visitor may do with it is
     * {@code org.ResolveMembership}, which is the only class that walks
     * {@code account -> member -> organization -> project}. A database nobody can reach and
     * a database that does not exist produce the same {@link NotFoundException}.
     */
    private Membership resolve(UUID databaseId) {
        AccountRef account = currentAccount.require();
        UUID projectId = databases.findProjectIdOf(databaseId)
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId));
        return memberships.forProject(account.id(), projectId);
    }
}
