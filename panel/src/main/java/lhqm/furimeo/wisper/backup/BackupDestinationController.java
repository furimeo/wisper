package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * A customer's own backup destinations: add one, change one, prove it works, remove it.
 *
 * <p>Platform-wide destinations appear on this page - a customer has to be able to see what
 * they are choosing - and are not editable from it. Their {@link DestinationView#editable}
 * is false and every write here passes the caller's organization into the use-case, which
 * refuses a row that belongs to a different scope. Two checks, on purpose: the page decides
 * what to draw and the use-case decides what may happen.
 *
 * <p>Renders {@code frontend/src/features/backup/DestinationsPage.tsx}.
 */
@Controller
public class BackupDestinationController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ListBackupDestinations destinations;
    private final CreateBackupDestination create;
    private final UpdateBackupDestination update;
    private final DeleteBackupDestination delete;
    private final VerifyDestination verify;
    private final RecordRefusal refusals;

    public BackupDestinationController(ResolveCurrentAccount currentAccount,
                                       ResolveMembership memberships,
                                       ListBackupDestinations destinations,
                                       CreateBackupDestination create,
                                       UpdateBackupDestination update,
                                       DeleteBackupDestination delete, VerifyDestination verify,
                                       RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.destinations = destinations;
        this.create = create;
        this.update = update;
        this.delete = delete;
        this.verify = verify;
        this.refusals = refusals;
    }

    /**
     * Props: {@code destinations}, {@code nodeBackupRoot} (so the local-path field can say
     * what a valid path looks like) and {@code viewerRole}.
     */
    @GetMapping("/backups/{organizationId}/destinations")
    public String list(@PathVariable UUID organizationId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forAccount(account.id(), organizationId);

        model.addAttribute("destinations", destinations.visibleTo(organizationId));
        model.addAttribute("nodeBackupRoot", DestinationCredentials.NODE_BACKUP_ROOT);
        model.addAttribute("viewerRole", membership.role());
        return "backup/Destinations";
    }

    @PostMapping("/backups/{organizationId}/destinations")
    public String create(@PathVariable UUID organizationId,
                         @ModelAttribute DestinationDraft draft,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            membership.requireWrite("backup_destination.create");
            BackupDestination created = create.create(actor, organizationId, draft);
            InertiaFlash.success(flash, "\"" + created.name() + "\" is saved. Check it before "
                    + "you point a backup at it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup_destination.create",
                    AuditTarget.unidentified("backup_destination", draft.name()), organizationId,
                    refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/destinations/{destinationId}")
    public String update(@PathVariable UUID organizationId, @PathVariable UUID destinationId,
                         @ModelAttribute DestinationDraft draft,
                         @RequestParam(name = "enabled", defaultValue = "true") boolean enabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            membership.requireWrite("backup_destination.update");
            BackupDestination updated = update.update(actor, organizationId, destinationId, draft,
                    enabled);
            InertiaFlash.success(flash, "\"" + updated.name() + "\" saved.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup_destination.update",
                    AuditTarget.of("backup_destination", destinationId, draft.name()),
                    organizationId, refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/destinations/{destinationId}/verify")
    public String verify(@PathVariable UUID organizationId, @PathVariable UUID destinationId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            membership.requireWrite("backup_destination.verify");
            // Platform-wide rows are checked from the operator screen, which owns them.
            String outcome = verify.verify(actor, organizationId, destinationId);
            InertiaFlash.success(flash, outcome);
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup_destination.verify",
                    AuditTarget.of("backup_destination", destinationId, ""), organizationId,
                    refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/destinations/{destinationId}/delete")
    public String delete(@PathVariable UUID organizationId, @PathVariable UUID destinationId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            membership.requireWrite("backup_destination.delete");
            delete.delete(actor, organizationId, destinationId);
            InertiaFlash.success(flash, "The destination is gone.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup_destination.delete",
                    AuditTarget.of("backup_destination", destinationId, ""), organizationId,
                    refused, flash);
        }
        return back(organizationId);
    }

    private static String back(UUID organizationId) {
        return "redirect:/backups/" + organizationId + "/destinations";
    }
}
