package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The page one restore reports itself on.
 *
 * <p>Its whole reason for existing is the sentence in design §8.3: restoring is a button.
 * A button that starts a job taking minutes needs a destination, and without one the
 * customer is left on a list with a flash message and no way to find out what happened -
 * which is the blank frame this project was rebuilt to avoid.
 *
 * <p>No SSE here. A restore emits tens of lines over minutes, not the thousands a build
 * emits over the same period, so the page polls this route while
 * {@code state} is not terminal. That keeps the seam to one ordinary GET rather than a
 * second streaming surface to operate for a screen most customers see twice a year.
 *
 * <p>Renders {@code frontend/src/features/backup/RestoreRunPage.tsx}.
 */
@Controller
public class RestoreRunController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final DescribeRestoreRun restores;

    public RestoreRunController(ResolveCurrentAccount currentAccount,
                                ResolveMembership memberships, DescribeRestoreRun restores) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.restores = restores;
    }

    /**
     * Props: {@code restore} - a {@link RestoreRunView}, including the node's progress log
     * and the id of the safety snapshot to go back to - and {@code viewerRole}.
     */
    @GetMapping("/backups/{organizationId}/restores/{restoreRunId}")
    public String show(@PathVariable UUID organizationId, @PathVariable UUID restoreRunId,
                       Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forAccount(account.id(), organizationId);

        RestoreRunView run = restores.byId(restoreRunId, organizationId)
                .orElseThrow(() -> NotFoundException.of("restore_run", restoreRunId));
        model.addAttribute("restore", run);
        model.addAttribute("viewerRole", membership.role());
        return "backup/RestoreRun";
    }
}
