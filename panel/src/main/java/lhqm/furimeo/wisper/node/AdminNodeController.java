package lhqm.furimeo.wisper.node;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
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
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The fleet, and one machine in it.
 *
 * <p>Under {@code /admin/**}, which {@code SecurityConfig} gates on {@code ROLE_ADMIN}. No
 * membership is involved: nodes belong to the platform, not to a tenant, and a customer
 * with a valid session must not be able to see another tenant's infrastructure.
 *
 * <p>The two buttons that change what a node is <em>for</em> - create and edit - are here.
 * The ones that change what it is <em>doing</em> are in
 * {@link AdminNodeLifecycleController}, and the ones that let a machine join are in
 * {@link AdminNodeTokenController}. Three files, three questions, and none of them has to
 * be read to understand the others.
 */
@Controller
public class AdminNodeController {

    private final ListNodes listNodes;
    private final DescribeNode describeNode;
    private final CreateNode createNode;
    private final IssueEnrolmentToken issueToken;
    private final UpdateNodeSettings updateNodeSettings;
    private final ResolveCurrentAccount currentAccount;
    private final RecordRefusal refusals;

    public AdminNodeController(ListNodes listNodes, DescribeNode describeNode,
                               CreateNode createNode, IssueEnrolmentToken issueToken,
                               UpdateNodeSettings updateNodeSettings,
                               ResolveCurrentAccount currentAccount, RecordRefusal refusals) {
        this.listNodes = listNodes;
        this.describeNode = describeNode;
        this.createNode = createNode;
        this.issueToken = issueToken;
        this.updateNodeSettings = updateNodeSettings;
        this.currentAccount = currentAccount;
        this.refusals = refusals;
    }

    /** Every node, with the ones needing attention called out separately. */
    @GetMapping("/admin/nodes")
    public String list(Model model) {
        model.addAttribute("nodes", listNodes.all());
        model.addAttribute("attention", listNodes.needingAttention());
        return "node/AdminNodeList";
    }

    /** One machine in full: report, tokens, install command, capacity. */
    @GetMapping("/admin/nodes/{nodeId}")
    public String detail(@PathVariable UUID nodeId, HttpServletRequest request, Model model) {
        model.addAttribute("node", describeNode.of(nodeId, PanelBaseUrl.of(request)));
        return "node/AdminNodeDetail";
    }

    /**
     * Creates the record and hands over the first bootstrap token in the same breath.
     *
     * <p>Design §7.3 step 1 is "create a node and receive a token", one action rather than
     * two, and that is what makes "from the install command to a node holding workloads in
     * under sixty seconds" achievable. The token text lives only in the flash attribute
     * written here; nothing can produce it again.
     */
    @PostMapping("/admin/nodes")
    public String create(@Valid @ModelAttribute("form") CreateForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return "redirect:/admin/nodes";
        }
        try {
            Node node = createNode.create(actor, form.name(), form.description(),
                    form.publicAddress(), splitTags(form.tags()));
            IssueEnrolmentToken.IssuedToken issued = issueToken.issue(actor, node.id(),
                    account.id());
            NodeDetail detail = describeNode.of(node.id(), PanelBaseUrl.of(request));
            flash.addFlashAttribute("bootstrapToken", issued.text());
            flash.addFlashAttribute("bootstrapTokenExpiresAt", issued.row().expiresAt());
            flash.addFlashAttribute("installCommand", detail.installCommand());
            flash.addFlashAttribute("installChecksum", detail.installChecksum());
            InertiaFlash.success(flash, node.name() + " created, with a bootstrap token that "
                    + "is shown once and expires at " + issued.row().expiresAt() + ".");
            return "redirect:/admin/nodes/" + node.id();
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.create", AuditTarget.unidentified("node", form.name()),
                    null, rejected, flash);
            return "redirect:/admin/nodes";
        }
    }

    @PostMapping("/admin/nodes/{nodeId}/settings")
    public String update(@PathVariable UUID nodeId,
                         @Valid @ModelAttribute("form") SettingsForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AuditActor actor = operator(request);
        String back = "redirect:/admin/nodes/" + nodeId;
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            Node node = updateNodeSettings.update(actor, nodeId, form.description(),
                    form.publicAddress(), splitTags(form.tags()), form.schedulable());
            InertiaFlash.success(flash, node.schedulable()
                    ? "Saved. " + node.name() + " is accepting new placements."
                    : "Saved. " + node.name() + " will take no new placements; nothing running "
                            + "was touched.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.update", AuditTarget.of("node", nodeId, ""), null,
                    rejected, flash);
        }
        return back;
    }

    /**
     * Tags arrive as one comma-separated field, because a phone keyboard and a repeating
     * input are a bad combination and this is an operator screen with three of them.
     */
    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::strip).filter(tag -> !tag.isEmpty())
                .toList();
    }

    private AuditActor operator(HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        return AuditActor.account(account.id(), account.email(), request);
    }

    /** The new-node form. */
    public record CreateForm(
            @NotBlank(message = "Give the node a name.")
            @Size(max = 63, message = "Keep it to 63 characters.")
            String name,
            @Size(max = 500, message = "Keep it to 500 characters.")
            String description,
            @Size(max = 255, message = "Keep it to 255 characters.")
            String publicAddress,
            @Size(max = 500, message = "Keep it to 500 characters.")
            String tags) {
    }

    /** The edit form. The name is absent on purpose: it is the deletion confirmation. */
    public record SettingsForm(
            @Size(max = 500, message = "Keep it to 500 characters.")
            String description,
            @Size(max = 255, message = "Keep it to 255 characters.")
            String publicAddress,
            @Size(max = 500, message = "Keep it to 500 characters.")
            String tags,
            boolean schedulable) {
    }
}
