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
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The operator's destinations: the buckets every tenant may push to.
 *
 * <p>These are the rows with a null {@code organization_id}. They exist so a small platform
 * can run one offsite bucket for everybody rather than asking each customer to bring an S3
 * account, and they are the reason
 * {@code backup_destination_scope_name_key} is {@code NULLS NOT DISTINCT}.
 *
 * <p>Authorization is the security chain's: {@code /admin/**} requires {@code ROLE_ADMIN}
 * and nothing here checks a membership, because a platform-wide destination belongs to no
 * tenant. Every write passes a null organization to the use-case, which is what confines
 * this screen to exactly those rows - the same method called with an organization id from
 * {@code /backups} cannot touch one of them, and this one cannot touch a customer's.
 *
 * <p>Renders {@code frontend/src/features/backup/AdminDestinationsPage.tsx}.
 */
@Controller
public class AdminBackupDestinationController {

    private final ResolveCurrentAccount currentAccount;
    private final ListBackupDestinations destinations;
    private final CreateBackupDestination create;
    private final UpdateBackupDestination update;
    private final DeleteBackupDestination delete;
    private final VerifyDestination verify;
    private final RecordRefusal refusals;

    public AdminBackupDestinationController(ResolveCurrentAccount currentAccount,
                                            ListBackupDestinations destinations,
                                            CreateBackupDestination create,
                                            UpdateBackupDestination update,
                                            DeleteBackupDestination delete,
                                            VerifyDestination verify, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.destinations = destinations;
        this.create = create;
        this.update = update;
        this.delete = delete;
        this.verify = verify;
        this.refusals = refusals;
    }

    /** Props: {@code destinations}, {@code nodeBackupRoot}. */
    @GetMapping("/admin/backups")
    public String list(Model model) {
        currentAccount.require();
        model.addAttribute("destinations", destinations.platformWide());
        model.addAttribute("nodeBackupRoot", DestinationCredentials.NODE_BACKUP_ROOT);
        return "backup/AdminDestinations";
    }

    @PostMapping("/admin/backups/destinations")
    public String create(@ModelAttribute DestinationDraft draft, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = actor(request);
        try {
            BackupDestination created = create.create(actor, null, draft);
            InertiaFlash.success(flash, "\"" + created.name() + "\" is available to every "
                    + "organization. Check it before anybody points a backup at it.");
        } catch (RequestRejected refused) {
            refusals.of(actor, "backup_destination.create",
                    AuditTarget.unidentified("backup_destination", draft.name()), null, refused,
                    flash);
        }
        return "redirect:/admin/backups";
    }

    @PostMapping("/admin/backups/destinations/{destinationId}")
    public String update(@PathVariable UUID destinationId, @ModelAttribute DestinationDraft draft,
                         @RequestParam(name = "enabled", defaultValue = "true") boolean enabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = actor(request);
        try {
            BackupDestination updated = update.update(actor, null, destinationId, draft, enabled);
            InertiaFlash.success(flash, "\"" + updated.name() + "\" saved.");
        } catch (RequestRejected refused) {
            refusals.of(actor, "backup_destination.update",
                    AuditTarget.of("backup_destination", destinationId, draft.name()), null,
                    refused, flash);
        }
        return "redirect:/admin/backups";
    }

    @PostMapping("/admin/backups/destinations/{destinationId}/verify")
    public String verify(@PathVariable UUID destinationId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = actor(request);
        InertiaFlash.success(flash, verify.verify(actor, null, destinationId));
        return "redirect:/admin/backups";
    }

    @PostMapping("/admin/backups/destinations/{destinationId}/delete")
    public String delete(@PathVariable UUID destinationId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = actor(request);
        try {
            delete.delete(actor, null, destinationId);
            InertiaFlash.success(flash, "The destination is gone.");
        } catch (RequestRejected refused) {
            refusals.of(actor, "backup_destination.delete",
                    AuditTarget.of("backup_destination", destinationId, ""), null, refused, flash);
        }
        return "redirect:/admin/backups";
    }

    private AuditActor actor(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }
}
