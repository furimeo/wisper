package lhqm.furimeo.wisper.service;

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
 * The disks attached to a service.
 *
 * <p>Sizes arrive from the form in mebibytes and are stored in bytes. The unit lives in
 * one place - {@link #toBytes} - because a factor of 1024 applied twice is a customer
 * asking for 10 GiB and getting 10 MiB, and applied zero times is a plan exhausted by the
 * first volume.
 *
 * <p>Renders {@code features/service/ServiceVolumesPage.tsx}.
 */
@Controller
public class VolumeController {

    private static final long MEBIBYTE = 1_048_576L;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final QuotaGuard quotas;
    private final CreateVolume createVolume;
    private final ResizeVolume resizeVolume;
    private final DeleteVolume deleteVolume;
    private final RecordRefusal refusals;

    public VolumeController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                            ServiceRepository services, VolumeRepository volumes,
                            QuotaGuard quotas, CreateVolume createVolume,
                            ResizeVolume resizeVolume, DeleteVolume deleteVolume,
                            RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.volumes = volumes;
        this.quotas = quotas;
        this.createVolume = createVolume;
        this.resizeVolume = resizeVolume;
        this.deleteVolume = deleteVolume;
        this.refusals = refusals;
    }

    /**
     * Props: {@code service}, {@code volumes} (each with what the node measured, or null
     * before it has reported), {@code allowance} for the storage bar, {@code viewerRole}.
     *
     * <p>A static site reaches this page too, and it renders the reason it has no volumes
     * rather than an empty list with an "add" button that always fails.
     */
    @GetMapping("/services/{serviceId}/volumes")
    public String volumes(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("volumes", volumes.findByServiceIdOrderByName(serviceId));
        model.addAttribute("allowance",
                quotas.allowanceFor(membership.organizationId(), QuotaResource.VOLUME_BYTES));
        model.addAttribute("viewerRole", membership.role());
        return "service/ServiceVolumes";
    }

    @PostMapping("/services/{serviceId}/volumes")
    public String create(@PathVariable UUID serviceId,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "mountPath", required = false) String mountPath,
                         @RequestParam(name = "sizeMebibytes", defaultValue = "0") long sizeMib,
                         @RequestParam(name = "readOnly", defaultValue = "false") boolean readOnly,
                         @RequestParam(name = "backupEnabled", defaultValue = "true")
                         boolean backupEnabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Volume created = createVolume.create(actor, membership, serviceId, name, mountPath,
                    toBytes(sizeMib), readOnly, backupEnabled);
            InertiaFlash.success(flash, created.name() + " is attached at "
                    + created.mountPath() + ". The service is now pinned to its node.");
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "volume.create", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/volumes/resize")
    public String resize(@PathVariable UUID serviceId,
                         @RequestParam(name = "name") String name,
                         @RequestParam(name = "sizeMebibytes", defaultValue = "0") long sizeMib,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Volume resized = resizeVolume.resize(actor, membership, serviceId, name,
                    toBytes(sizeMib));
            InertiaFlash.success(flash, resized.name() + " is now "
                    + resized.sizeBytes() / MEBIBYTE + " MiB.");
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "volume.resize", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/volumes/delete")
    public String delete(@PathVariable UUID serviceId,
                         @RequestParam(name = "name") String name,
                         @RequestParam(name = "confirmation", required = false) String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            deleteVolume.delete(actor, membership, serviceId, name, confirmation);
            InertiaFlash.success(flash, name
                    + " is detached. The data stays on the node until it is purged.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "volume.delete", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    /**
     * Mebibytes to bytes, saturating rather than overflowing.
     *
     * <p>A customer who types a very long number gets "that is larger than 4 TiB" from the
     * use-case, which is the truth. Multiplying into a negative long would get them
     * "a volume is at least 1 MiB", which is not.
     */
    private static long toBytes(long mebibytes) {
        if (mebibytes <= 0) {
            return 0;
        }
        return mebibytes > Long.MAX_VALUE / MEBIBYTE ? Long.MAX_VALUE : mebibytes * MEBIBYTE;
    }

    private static String back(UUID serviceId) {
        return "redirect:/services/" + serviceId + "/volumes";
    }

    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }
}
