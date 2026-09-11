package lhqm.furimeo.wisper.placement;

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
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * {@code /admin/placement} - which service is on which node, and the one button that
 * moves it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code panel-http.md} reserves this prefix and nothing was serving it, which made
 * {@link MigrateService} unreachable: a use-case with a test, an audit line and no way for
 * a human to invoke it. The scheduler places a service once and pins it as soon as it has
 * a volume, so after that the only thing that can move it is an operator deciding to -
 * before draining a node for maintenance, or after one turns out to be the wrong size. A
 * platform with no such button has one recovery from a badly placed service, which is to
 * delete it.
 *
 * <h2>Why moving is never automatic</h2>
 *
 * <p>A pinned placement holds a volume, and the volume does not follow. The panel cannot
 * copy it - the bytes are on a node's disk, and the only thing that could move them is a
 * backup and a restore, which is a different operation with a different failure mode. So
 * {@code MigrateService} refuses a service with storage unless the caller says, in the
 * request, that they accept losing it. This controller surfaces that as a separate
 * checkbox rather than folding it into the confirmation, because "yes I meant this node"
 * and "yes I accept the data is staying behind" are two different answers.
 */
@Controller
public class AdminPlacementController {

    private final LoadPlacementBoard board;
    private final LoadNodeCapacity capacity;
    private final MigrateService migrateService;
    private final ResolveCurrentAccount currentAccount;
    private final RecordRefusal refusals;

    public AdminPlacementController(LoadPlacementBoard board, LoadNodeCapacity capacity,
                                    MigrateService migrateService,
                                    ResolveCurrentAccount currentAccount,
                                    RecordRefusal refusals) {
        this.board = board;
        this.capacity = capacity;
        this.migrateService = migrateService;
        this.currentAccount = currentAccount;
        this.refusals = refusals;
    }

    @GetMapping("/admin/placement")
    public String board(Model model) {
        model.addAttribute("placements", board.everything());
        // Every schedulable node, not only the ones that fit: an operator choosing a target
        // needs to see that the node they had in mind is full, rather than finding out from
        // a refusal after they have picked it.
        model.addAttribute("nodes", capacity.candidates(List.of()));
        return "placement/AdminPlacement";
    }

    /**
     * Moves one service to a named node.
     *
     * <p>{@code moveDespiteVolumes} is the operator accepting that a pinned service's data
     * stays on the old node. {@link MigrateService} refuses without it, and that refusal is
     * the safety, so this only passes through what was actually ticked.
     */
    @PostMapping("/admin/placement/{serviceId}/migrate")
    public String migrate(@PathVariable UUID serviceId,
                          @RequestParam(name = "nodeId") UUID nodeId,
                          @RequestParam(name = "moveDespiteVolumes", defaultValue = "false")
                          boolean moveDespiteVolumes,
                          @RequestParam(name = "reason", required = false) String reason,
                          HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            Placement moved = migrateService.toNode(actor, serviceId, nodeId, moveDespiteVolumes,
                    reason);
            flashMoved(flash, moved);
        } catch (RequestRejected refused) {
            // NoNodeFits is a RequestRejected, so "that node is full" lands here with the
            // sentence the placement package wrote rather than a generic failure.
            refusals.of(actor, "service.migrate", AuditTarget.of("service", serviceId, null),
                    null, refused, flash);
        }
        return "redirect:/admin/placement";
    }

    private static void flashMoved(RedirectAttributes flash, Placement moved) {
        InertiaFlash.success(flash,
                "Moving to node " + moved.nodeId() + ". The old placement is draining; both "
                        + "nodes have been sent a new spec, and the service is live on the new "
                        + "one as soon as it converges.");
    }
}
