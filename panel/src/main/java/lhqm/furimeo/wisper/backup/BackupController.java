package lhqm.furimeo.wisper.backup;

import java.util.List;
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
import lhqm.furimeo.wisper.org.ListOrganizationsForAccount;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.OrganizationSummary;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The backup policies screen: what is copied, how often, and what came of it.
 *
 * <p>The organization is in the path rather than remembered in the session. A customer with
 * three tenants must be able to have three of these pages open, and
 * {@code CurrentOrganizationProps} reads {@code organizationId} straight out of the URL - so
 * the chrome and the content cannot disagree about which tenant is being looked at.
 *
 * <p>Renders {@code frontend/src/features/backup/BackupListPage.tsx}.
 */
@Controller
public class BackupController {

    /** Enough restores for the history panel; the page is a list, not an archive. */
    private static final int RECENT_RESTORES = 20;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ListOrganizationsForAccount organizations;
    private final ListBackupPolicies policies;
    private final ListBackupDestinations destinations;
    private final ListBackupTargets targets;
    private final DescribeRestoreRun restores;
    private final QuotaGuard quotas;
    private final CreateBackupSchedule createSchedule;
    private final UpdateBackupSchedule updateSchedule;
    private final DeleteBackupSchedule deleteSchedule;
    private final RunBackupNow runNow;
    private final RecordRefusal refusals;

    public BackupController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                            ListOrganizationsForAccount organizations,
                            ListBackupPolicies policies, ListBackupDestinations destinations,
                            ListBackupTargets targets, DescribeRestoreRun restores,
                            QuotaGuard quotas, CreateBackupSchedule createSchedule,
                            UpdateBackupSchedule updateSchedule,
                            DeleteBackupSchedule deleteSchedule, RunBackupNow runNow,
                            RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.organizations = organizations;
        this.policies = policies;
        this.destinations = destinations;
        this.targets = targets;
        this.restores = restores;
        this.quotas = quotas;
        this.createSchedule = createSchedule;
        this.updateSchedule = updateSchedule;
        this.deleteSchedule = deleteSchedule;
        this.runNow = runNow;
        this.refusals = refusals;
    }

    /**
     * {@code /backups} with no tenant named: go to the first one this person belongs to.
     *
     * <p>A redirect rather than a render, so that every backup page in the application has
     * an organization in its URL and the switcher has somewhere to point. Somebody with no
     * accepted membership sees the page with nothing on it, which is the honest answer and
     * not an error.
     */
    @GetMapping("/backups")
    public String index(Model model) {
        AccountRef account = currentAccount.require();
        List<OrganizationSummary> mine = organizations.all(account.id());
        return mine.stream()
                .filter(OrganizationSummary::accepted)
                .findFirst()
                .map(first -> "redirect:/backups/" + first.id())
                .orElseGet(() -> emptyState(model));
    }

    /**
     * Props: {@code policies}, {@code destinations}, {@code targets}, {@code restores},
     * {@code snapshotAllowance}, {@code byteAllowance}, {@code viewerRole}.
     *
     * <p>Everything the create form needs is on the page already, because a form whose
     * options arrive in a second request is a form that renders empty on a slow connection.
     */
    @GetMapping("/backups/{organizationId}")
    public String list(@PathVariable UUID organizationId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forAccount(account.id(), organizationId);

        model.addAttribute("policies", policies.forOrganization(organizationId));
        model.addAttribute("destinations", destinations.visibleTo(organizationId));
        model.addAttribute("targets", targets.forOrganization(organizationId));
        model.addAttribute("restores", restores.recentFor(organizationId, RECENT_RESTORES));
        model.addAttribute("snapshotAllowance",
                quotas.allowanceFor(organizationId, QuotaResource.RESTORE_POINT));
        model.addAttribute("byteAllowance",
                quotas.allowanceFor(organizationId, QuotaResource.BACKUP_BYTES));
        model.addAttribute("viewerRole", membership.role());
        return "backup/BackupList";
    }

    @PostMapping("/backups/{organizationId}/policies")
    public String create(@PathVariable UUID organizationId,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "destinationId", required = false) UUID destinationId,
                         @RequestParam(name = "targetKind", required = false) String targetKind,
                         @RequestParam(name = "subjectId", required = false) UUID subjectId,
                         @RequestParam(name = "schedule", required = false) String schedule,
                         @RequestParam(name = "timezone", defaultValue = "UTC") String timezone,
                         @RequestParam(name = "retentionCount", defaultValue = "7") int count,
                         @RequestParam(name = "retentionDays", defaultValue = "30") int days,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            Backup created = createSchedule.create(actor, membership, name, destinationId,
                    kindOf(targetKind), subjectId, schedule, timezone, count, days);
            InertiaFlash.success(flash, "\"" + created.name() + "\" is set up. "
                    + (created.isScheduled()
                            ? "It next runs at " + created.nextRunAt() + "."
                            : "Run it whenever you like with the button."));
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "backup.create", AuditTarget.unidentified("backup",
                    name == null ? "" : name), organizationId, refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/policies/{backupId}")
    public String update(@PathVariable UUID organizationId, @PathVariable UUID backupId,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "destinationId", required = false) UUID destinationId,
                         @RequestParam(name = "schedule", required = false) String schedule,
                         @RequestParam(name = "timezone", defaultValue = "UTC") String timezone,
                         @RequestParam(name = "retentionCount", defaultValue = "7") int count,
                         @RequestParam(name = "retentionDays", defaultValue = "30") int days,
                         @RequestParam(name = "enabled", defaultValue = "true") boolean enabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            Backup updated = updateSchedule.update(actor, membership, backupId, name,
                    destinationId, schedule, timezone, count, days, enabled);
            InertiaFlash.success(flash, "\"" + updated.name() + "\" saved.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup.update", AuditTarget.of("backup", backupId, ""),
                    organizationId, refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/policies/{backupId}/run")
    public String run(@PathVariable UUID organizationId, @PathVariable UUID backupId,
                      HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            Backup policy = runNow.run(actor, membership, backupId);
            InertiaFlash.success(flash, "A snapshot under \"" + policy.name()
                    + "\" is on its way. It appears in the list when the node reports it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup.run", AuditTarget.of("backup", backupId, ""),
                    organizationId, refused, flash);
        }
        return back(organizationId);
    }

    @PostMapping("/backups/{organizationId}/policies/{backupId}/delete")
    public String delete(@PathVariable UUID organizationId, @PathVariable UUID backupId,
                         @RequestParam(name = "confirmation", required = false) String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            deleteSchedule.delete(actor, membership, backupId, confirmation);
            InertiaFlash.success(flash, "The policy is gone. Every snapshot it took is still "
                    + "there and still restorable.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "backup.delete", AuditTarget.of("backup", backupId, ""),
                    organizationId, refused, flash);
        }
        return back(organizationId);
    }

    /**
     * The page for somebody who belongs to no organization yet.
     *
     * <p>A real page with a real explanation, not a redirect to the dashboard: bouncing
     * somebody who clicked "Backups" back to where they came from looks like the link is
     * broken.
     */
    private static String emptyState(Model model) {
        model.addAttribute("policies", List.of());
        model.addAttribute("destinations", List.of());
        model.addAttribute("targets", List.of());
        model.addAttribute("restores", List.of());
        model.addAttribute("snapshotAllowance", null);
        model.addAttribute("byteAllowance", null);
        model.addAttribute("viewerRole", null);
        return "backup/BackupList";
    }

    /** The form sends a string; anything that is not one of the two kinds is refused here. */
    private static BackupTargetKind kindOf(String value) {
        try {
            return BackupTargetKind.valueOf(value == null ? "" : value.strip());
        } catch (IllegalArgumentException unknown) {
            throw new RequestRejected("targetKind", "Choose a volume or a database to back up.");
        }
    }

    private static String back(UUID organizationId) {
        return "redirect:/backups/" + organizationId;
    }
}
