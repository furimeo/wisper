package lhqm.furimeo.wisper.backup;

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

/**
 * The snapshots screen, and the two buttons that matter on it: restore, and prove it
 * restores.
 *
 * <p>Both post to this controller and both end in a redirect to the run's own page, because
 * a restore takes minutes and the page that started it cannot be the page that reports it.
 * That redirect is what design §8.3 means by restoring being a button: one press, and then
 * somewhere to watch.
 *
 * <p>Renders {@code frontend/src/features/backup/SnapshotsPage.tsx}.
 */
@Controller
public class RestorePointController {

    /** A page of snapshots. Older ones are reached by narrowing the policy, not by paging. */
    private static final int PAGE_SIZE = 200;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ListRestorePoints snapshots;
    private final DescribeRestoreRun restores;
    private final QuotaGuard quotas;
    private final RestoreFromPoint restoreFromPoint;
    private final DeleteRestorePoint deleteRestorePoint;
    private final RecordRefusal refusals;

    public RestorePointController(ResolveCurrentAccount currentAccount,
                                  ResolveMembership memberships, ListRestorePoints snapshots,
                                  DescribeRestoreRun restores, QuotaGuard quotas,
                                  RestoreFromPoint restoreFromPoint,
                                  DeleteRestorePoint deleteRestorePoint, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.snapshots = snapshots;
        this.restores = restores;
        this.quotas = quotas;
        this.restoreFromPoint = restoreFromPoint;
        this.deleteRestorePoint = deleteRestorePoint;
        this.refusals = refusals;
    }

    /**
     * Props: {@code snapshots}, {@code restores}, {@code byteAllowance},
     * {@code viewerRole}.
     */
    @GetMapping("/backups/{organizationId}/snapshots")
    public String list(@PathVariable UUID organizationId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forAccount(account.id(), organizationId);

        model.addAttribute("snapshots", snapshots.forOrganization(organizationId, PAGE_SIZE));
        model.addAttribute("restores", restores.recentFor(organizationId, 20));
        model.addAttribute("byteAllowance",
                quotas.allowanceFor(organizationId, QuotaResource.BACKUP_BYTES));
        model.addAttribute("viewerRole", membership.role());
        return "backup/Snapshots";
    }

    /** Put it back over the live data, after taking a snapshot of what is there now. */
    @PostMapping("/backups/{organizationId}/snapshots/{restorePointId}/restore")
    public String restore(@PathVariable UUID organizationId, @PathVariable UUID restorePointId,
                          @RequestParam(name = "confirmed", defaultValue = "false")
                          boolean confirmed,
                          HttpServletRequest request, RedirectAttributes flash) {
        return begin(organizationId, restorePointId, RestoreMode.IN_PLACE, confirmed, request,
                flash);
    }

    /**
     * Restore it somewhere disposable and throw the result away.
     *
     * <p>The button that turns "we take backups" into "we have restored this one". It needs
     * no confirmation because it overwrites nothing.
     */
    @PostMapping("/backups/{organizationId}/snapshots/{restorePointId}/verify")
    public String verify(@PathVariable UUID organizationId, @PathVariable UUID restorePointId,
                         HttpServletRequest request, RedirectAttributes flash) {
        return begin(organizationId, restorePointId, RestoreMode.VERIFY, true, request, flash);
    }

    @PostMapping("/backups/{organizationId}/snapshots/{restorePointId}/delete")
    public String delete(@PathVariable UUID organizationId, @PathVariable UUID restorePointId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            RestorePoint deleted = deleteRestorePoint.delete(actor, membership, restorePointId);
            InertiaFlash.success(flash, "The snapshot of " + deleted.targetLabel()
                    + " is off the list. The archive itself goes when the node next prunes.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "restore_point.delete",
                    AuditTarget.of("restore_point", restorePointId, ""), organizationId, refused,
                    flash);
        }
        return "redirect:/backups/" + organizationId + "/snapshots";
    }

    private String begin(UUID organizationId, UUID restorePointId, RestoreMode mode,
                         boolean confirmed, HttpServletRequest request,
                         RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forAccount(account.id(), organizationId);
        try {
            RestoreRun run = restoreFromPoint.start(actor, membership, restorePointId, mode,
                    confirmed);
            InertiaFlash.success(flash, mode.overwritesTheTarget()
                    ? "Restoring. A snapshot of the current data is taken first; this page "
                            + "follows it."
                    : "Checking that this snapshot restores. Nothing live is touched.");
            return "redirect:/backups/" + organizationId + "/restores/" + run.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "backup.restore",
                    AuditTarget.of("restore_point", restorePointId, ""), organizationId, refused,
                    flash);
            return "redirect:/backups/" + organizationId + "/snapshots";
        }
    }
}
