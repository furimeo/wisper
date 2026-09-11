package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * Issuing and withdrawing the bootstrap token that lets one machine join.
 *
 * <p>The token text exists in exactly one place in this codebase: the flash message this
 * controller writes. It is never stored, never logged and never recoverable - only its
 * SHA-256 goes in the database - so the screen that appears after issuing one is the only
 * chance anybody has to copy it. That is deliberate, and it is why the message contains
 * the whole three-line command rather than a token the operator then has to work out what
 * to do with.
 *
 * <p>Putting it in a flash attribute means it survives exactly one redirect and is gone.
 * It is not in the URL, so it is not in a browser history, a proxy log or a referrer
 * header.
 */
@Controller
public class AdminNodeTokenController {

    private final IssueEnrolmentToken issueToken;
    private final RevokeEnrolmentToken revokeToken;
    private final DescribeNode describeNode;
    private final ResolveCurrentAccount currentAccount;
    private final RecordRefusal refusals;

    public AdminNodeTokenController(IssueEnrolmentToken issueToken,
                                    RevokeEnrolmentToken revokeToken, DescribeNode describeNode,
                                    ResolveCurrentAccount currentAccount,
                                    RecordRefusal refusals) {
        this.issueToken = issueToken;
        this.revokeToken = revokeToken;
        this.describeNode = describeNode;
        this.currentAccount = currentAccount;
        this.refusals = refusals;
    }

    /**
     * Mints a token and puts it, once, in front of the operator.
     *
     * <p>{@code bootstrapToken} is a separate flash attribute from the success message so
     * the page can render it in a copy-to-clipboard field rather than as prose somebody
     * has to select by hand on a phone.
     */
    @PostMapping("/admin/nodes/{nodeId}/tokens")
    public String issue(@PathVariable UUID nodeId, HttpServletRequest request,
                        RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        String back = "redirect:/admin/nodes/" + nodeId;
        try {
            IssueEnrolmentToken.IssuedToken issued = issueToken.issue(actor, nodeId,
                    account.id());
            NodeDetail detail = describeNode.of(nodeId, PanelBaseUrl.of(request));
            flash.addFlashAttribute("bootstrapToken", issued.text());
            flash.addFlashAttribute("bootstrapTokenExpiresAt", issued.row().expiresAt());
            flash.addFlashAttribute("installCommand", detail.installCommand());
            flash.addFlashAttribute("installChecksum", detail.installChecksum());
            InertiaFlash.success(flash, "Bootstrap token issued. It is shown once, it is good "
                    + "for one machine, and it expires at " + issued.row().expiresAt() + ".");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.token_issue", AuditTarget.of("node", nodeId, ""), null,
                    rejected, flash);
        }
        return back;
    }

    @PostMapping("/admin/nodes/{nodeId}/tokens/{tokenId}/revoke")
    public String revoke(@PathVariable UUID nodeId, @PathVariable UUID tokenId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            revokeToken.revoke(actor, tokenId);
            InertiaFlash.success(flash, "Token revoked. It can no longer enrol anything.");
        } catch (RequestRejected rejected) {
            refusals.of(actor, "node.token_revoke", AuditTarget.of("node", nodeId, ""), null,
                    rejected, flash);
        }
        return "redirect:/admin/nodes/" + nodeId;
    }
}
