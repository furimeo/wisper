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
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The engine containers, under {@code /admin/databases}.
 *
 * <p>{@code SecurityConfig} gates {@code /admin/**} on {@code ROLE_ADMIN}, so there is no
 * membership here: engines belong to the platform rather than to a tenant, and a customer
 * with a valid session must not be able to see another tenant's infrastructure. A dedicated
 * instance has an owner, but the row is still an operator's to manage.
 *
 * <p>Most engines are never created from this screen. {@link ChooseDatabaseEngine} makes one
 * the first time a customer asks for a database and there is nothing to put it in, which is
 * what stops a fresh platform answering its first customer with "an operator has to set up
 * PostgreSQL first". This screen exists for the operator who wants to add one ahead of
 * demand, see what is running, or take one out.
 *
 * <h2>A note on the audit vocabulary</h2>
 *
 * <p>The four actions written from this package's engine paths -
 * {@code database_engine.create}, {@code .start}, {@code .stop}, {@code .delete} - are not
 * in the list in panel-ports.md §2.4, which covers customer databases and has nothing for
 * the containers they live in. Filing "an operator stopped PostgreSQL on node 3" under
 * {@code database.delete} would make the trail lie about what happened, so the vocabulary
 * needs these four adding.
 */
@Controller
public class AdminDatabaseEngineController {

    private final ListDatabaseEngines listing;
    private final ListDatabases databases;
    private final CreateDatabaseEngine createEngine;
    private final SetEngineDesiredState desiredState;
    private final DeleteDatabaseEngine deleteEngine;
    private final ResolveCurrentAccount currentAccount;
    private final RecordRefusal refusals;

    public AdminDatabaseEngineController(ListDatabaseEngines listing, ListDatabases databases,
                                         CreateDatabaseEngine createEngine,
                                         SetEngineDesiredState desiredState,
                                         DeleteDatabaseEngine deleteEngine,
                                         ResolveCurrentAccount currentAccount,
                                         RecordRefusal refusals) {
        this.listing = listing;
        this.databases = databases;
        this.createEngine = createEngine;
        this.desiredState = desiredState;
        this.deleteEngine = deleteEngine;
        this.currentAccount = currentAccount;
        this.refusals = refusals;
    }

    /**
     * Every engine on every node, and everything that has outgrown its ceiling.
     *
     * <p>Props: {@code engines} - a {@link DatabaseEngineView} each; {@code overQuota} - a
     * {@link ManagedDatabaseView} per database past its limit, worst first, because a node
     * whose disk is filling is usually a handful of databases doing it; and {@code kinds},
     * the two {@link EngineKind} values the create form offers.
     *
     * <p>Renders {@code features/database/AdminDatabaseEngineListPage.tsx}.
     */
    @GetMapping("/admin/databases")
    public String list(Model model) {
        model.addAttribute("engines", listing.all());
        model.addAttribute("overQuota", databases.overQuota());
        model.addAttribute("kinds", EngineKind.values());
        return "database/AdminDatabaseEngineList";
    }

    /**
     * One engine: what it is, what the node says about it, and what it is holding.
     *
     * <p>Renders {@code features/database/AdminDatabaseEngineDetailPage.tsx}.
     */
    @GetMapping("/admin/databases/{engineId}")
    public String detail(@PathVariable UUID engineId, Model model) {
        model.addAttribute("engine", listing.one(engineId)
                .orElseThrow(() -> NotFoundException.of("database_engine", engineId)));
        return "database/AdminDatabaseEngineDetail";
    }

    /** Add a shared instance to a node, ahead of anybody asking for a database. */
    @PostMapping("/admin/databases")
    public String create(@RequestParam("nodeId") UUID nodeId,
                         @RequestParam("engine") String engine,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        EngineKind kind = EngineKind.parse(engine);
        try {
            if (kind == null) {
                throw new RequestRejected("engine", "Choose PostgreSQL or MySQL.");
            }
            DatabaseEngine created = createEngine.shared(actor, nodeId, kind);
            InertiaFlash.success(flash, created.describe()
                    + " has been added to the node's spec and will start on its next pass.");
            return "redirect:/admin/databases/" + created.id();
        } catch (RequestRejected refused) {
            refusals.of(actor, "database_engine.create",
                    AuditTarget.unidentified("database_engine", engine), null, refused, flash);
            return "redirect:/admin/databases";
        }
    }

    /** Ask the node to keep this container running. */
    @PostMapping("/admin/databases/{engineId}/start")
    public String start(@PathVariable UUID engineId, HttpServletRequest request,
                        RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            DatabaseEngine started = desiredState.start(actor, engineId);
            InertiaFlash.success(flash, started.describe() + " is now wanted running.");
        } catch (RequestRejected refused) {
            refusals.of(actor, "database_engine.start",
                    AuditTarget.of("database_engine", engineId, engineId.toString()), null,
                    refused, flash);
        }
        return "redirect:/admin/databases/" + engineId;
    }

    /** Ask the node to stop it, leaving the data where it is. */
    @PostMapping("/admin/databases/{engineId}/stop")
    public String stop(@PathVariable UUID engineId, HttpServletRequest request,
                       RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            DatabaseEngine stopped = desiredState.stop(actor, engineId);
            InertiaFlash.success(flash, stopped.describe() + " is now wanted stopped.");
        } catch (RequestRejected refused) {
            refusals.of(actor, "database_engine.stop",
                    AuditTarget.of("database_engine", engineId, engineId.toString()), null,
                    refused, flash);
        }
        return "redirect:/admin/databases/" + engineId;
    }

    /** Take the container out of the node's spec. The data directory is left alone. */
    @PostMapping("/admin/databases/{engineId}/delete")
    public String delete(@PathVariable UUID engineId,
                         @RequestParam(name = "confirmation", required = false)
                         String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            deleteEngine.delete(actor, engineId, confirmation);
            InertiaFlash.success(flash, "The engine has been removed from the node's spec. Its "
                    + "data directory on the node is untouched.");
            return "redirect:/admin/databases";
        } catch (RequestRejected refused) {
            refusals.of(actor, "database_engine.delete",
                    AuditTarget.of("database_engine", engineId, engineId.toString()), null,
                    refused, flash);
            return "redirect:/admin/databases/" + engineId;
        }
    }

    private AuditActor operator(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }
}
