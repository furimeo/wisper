package lhqm.furimeo.wisper.node;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.proto.v1.DrainReport;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The buttons that change what a node is doing: drain, suspend, resume, upgrade, delete.
 *
 * <p>Every one of them is destructive in the sense that matters - it changes where a
 * customer's workload runs, or stops the panel being able to manage it - so every one is a
 * POST, redirects, and produces a sentence the operator can act on rather than a spinner
 * that stops.
 *
 * <p>The survey is the interesting one. An operator about to take a machine away needs to
 * know what is pinned to it <em>before</em> they commit, and the only thing that knows is
 * the node. So "what would happen" is a real round trip that changes nothing, and the
 * answer names the workloads with volumes - which are the ones nothing will move
 * automatically, because a silent migration is silent data loss (design §7.7).
 */
@Controller
public class AdminNodeLifecycleController {

    private final RequestNodeDrain requestDrain;
    private final RequestNodeUpgrade requestUpgrade;
    private final SuspendNode suspendNode;
    private final ResumeNode resumeNode;
    private final DeleteNode deleteNode;
    private final ResolveCurrentAccount currentAccount;
    private final RecordRefusal refusals;

    public AdminNodeLifecycleController(RequestNodeDrain requestDrain,
                                        RequestNodeUpgrade requestUpgrade,
                                        SuspendNode suspendNode, ResumeNode resumeNode,
                                        DeleteNode deleteNode,
                                        ResolveCurrentAccount currentAccount,
                                        RecordRefusal refusals) {
        this.requestDrain = requestDrain;
        this.requestUpgrade = requestUpgrade;
        this.suspendNode = suspendNode;
        this.resumeNode = resumeNode;
        this.deleteNode = deleteNode;
        this.currentAccount = currentAccount;
        this.refusals = refusals;
    }

    /** Ask the node what draining would do, and change nothing. */
    @PostMapping("/admin/nodes/{nodeId}/drain-survey")
    public String survey(@PathVariable UUID nodeId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Optional<DrainReport> report = requestDrain.request(actor, nodeId,
                    "survey before draining", false);
            InertiaFlash.success(flash, report.map(AdminNodeLifecycleController::describe)
                    .orElse("The node has not answered yet. Try again in a moment."));
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.drain", target(nodeId), null, rejected, flash);
        }
        return back(nodeId);
    }

    @PostMapping("/admin/nodes/{nodeId}/drain")
    public String drain(@PathVariable UUID nodeId, @Valid @ModelAttribute("form") ReasonForm form,
                        BindingResult errors, HttpServletRequest request,
                        RedirectAttributes flash) {
        AuditActor actor = operator(request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back(nodeId);
        }
        try {
            Optional<DrainReport> report = requestDrain.request(actor, nodeId, form.reason(),
                    true);
            InertiaFlash.success(flash, report.map(AdminNodeLifecycleController::describe)
                    .orElse("Draining. The node is working through it and the page will show "
                            + "the report when it finishes."));
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.drain", target(nodeId), null, rejected, flash);
        }
        return back(nodeId);
    }

    @PostMapping("/admin/nodes/{nodeId}/suspend")
    public String suspend(@PathVariable UUID nodeId,
                          @Valid @ModelAttribute("form") ReasonForm form, BindingResult errors,
                          HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back(nodeId);
        }
        try {
            Node node = suspendNode.byOperator(actor, nodeId, form.reason());
            InertiaFlash.success(flash, node.name() + " is suspended. The panel will not "
                    + "publish to it; its containers keep running.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.suspend", target(nodeId), null, rejected, flash);
        }
        return back(nodeId);
    }

    @PostMapping("/admin/nodes/{nodeId}/resume")
    public String resume(@PathVariable UUID nodeId, HttpServletRequest request,
                         RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            Node node = resumeNode.resume(actor, nodeId);
            InertiaFlash.success(flash, node.name() + " is active again and has been sent the "
                    + "current spec.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.resume", target(nodeId), null, rejected, flash);
        }
        return back(nodeId);
    }

    @PostMapping("/admin/nodes/{nodeId}/upgrade")
    public String upgrade(@PathVariable UUID nodeId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AuditActor actor = operator(request);
        try {
            InertiaFlash.success(flash, requestUpgrade.request(actor, nodeId,
                            PanelBaseUrl.of(request))
                    .orElse("Upgrading. The node restarts as part of it, so the result appears "
                            + "when it reconnects. Its containers are unaffected."));
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.upgrade", target(nodeId), null, rejected, flash);
        }
        return back(nodeId);
    }

    @PostMapping("/admin/nodes/{nodeId}/delete")
    public String delete(@PathVariable UUID nodeId,
                         @Valid @ModelAttribute("form") ConfirmForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back(nodeId);
        }
        try {
            deleteNode.delete(actor, nodeId, form.confirmation());
            InertiaFlash.success(flash, "Node record deleted. Nothing on the machine was "
                    + "touched; run `sasayaki uninstall` there to remove the daemon.");
            return "redirect:/admin/nodes";
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.delete", target(nodeId), null, rejected, flash);
            return back(nodeId);
        }
    }

    private static String describe(DrainReport report) {
        String evacuated = report.getEvacuatedWorkloadsCount() + " workload(s) can move";
        String pinned = report.getPinnedWorkloadsCount() == 0
                ? "nothing is pinned by a volume"
                : report.getPinnedWorkloadsCount() + " pinned by a volume and never moved "
                        + "automatically: " + String.join(", ", report.getPinnedWorkloadsList());
        return evacuated + "; " + pinned + ".";
    }

    private static String back(UUID nodeId) {
        return "redirect:/admin/nodes/" + nodeId;
    }

    private static AuditTarget target(UUID nodeId) {
        return AuditTarget.of("node", nodeId, "");
    }

    private AuditActor operator(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }

    /** Drain and suspend both want a sentence the next operator can read. */
    public record ReasonForm(
            @NotBlank(message = "Say why. It is shown on the node's page.")
            @Size(max = 500, message = "Keep it to 500 characters.")
            String reason) {
    }

    /** Deleting a node needs its name typed out. */
    public record ConfirmForm(
            @NotBlank(message = "Type the node's name to confirm.")
            String confirmation) {
    }
}
