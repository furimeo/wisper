package lhqm.furimeo.wisper.project;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;

/**
 * The screen behind {@code /}: every project the signed-in person can reach.
 *
 * <p>One route and one model attribute. The organizations themselves arrive as shared
 * props (panel-ports.md §5), so this controller does not decide which tenant is
 * "current" - it lists all of them and lets the page group by
 * {@code organizationName}. A dashboard that filters by a remembered tenant is a
 * dashboard that hides a project the moment the switcher and the session disagree.
 *
 * <p>Separate from {@link ProjectController} because it owns a different path -
 * {@code /} rather than {@code /projects/**} - and because the landing page is the one
 * screen whose job is breadth. Everything else in this package is about one project.
 *
 * <p>Renders {@code frontend/src/features/project/DashboardPage.tsx}.
 */
@Controller
public class DashboardController {

    private final ResolveCurrentAccount currentAccount;
    private final ListProjects projects;

    public DashboardController(ResolveCurrentAccount currentAccount, ListProjects projects) {
        this.currentAccount = currentAccount;
        this.projects = projects;
    }

    /**
     * Props: {@code projects} - a {@link ProjectSummary} per project, tenants in
     * alphabetical order, live projects before archived ones inside each tenant.
     *
     * <p>An empty list is a real answer and the page renders the "create your first
     * project" state for it. The new-project form posts to {@code POST /projects} and
     * picks its organization from the shared {@code organizations} prop.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        AccountRef account = currentAccount.require();
        model.addAttribute("projects", projects.visibleTo(account.id()));
        return "project/Dashboard";
    }
}
