package lhqm.furimeo.wisper.jobs;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The background queue at {@code GET /admin/jobs}, and the two buttons on a failing row.
 *
 * <p>Gated on {@code ROLE_ADMIN} by {@code SecurityConfig}. The queue is platform
 * infrastructure: its rows name deployments, backups and databases belonging to every
 * tenant, so a customer session must not reach it.
 *
 * <p>Renders {@code features/jobs/AdminJobsPage.tsx} with {@code summary} (four counts, so
 * a healthy queue shows something rather than an empty table) and {@code page} (the
 * failing rows).
 *
 * <p>This package deliberately does not depend on {@code org}: {@code org} depends on
 * {@code jobs} (panel-ports.md §6) and importing it back would close a cycle. The operator
 * is therefore read from Spring Security's {@link Authentication} rather than through
 * {@code ResolveCurrentAccount}, which is enough for an audit label and an account id when
 * the principal is one.
 */
@Controller
public class AdminJobController {

    private final SummariseJobQueue summary;
    private final ListFailedJobs failures;
    private final RetryFailedJob retry;
    private final DiscardFailedJob discard;
    private final JobsSettings settings;

    public AdminJobController(SummariseJobQueue summary, ListFailedJobs failures,
                              RetryFailedJob retry, DiscardFailedJob discard,
                              JobsSettings settings) {
        this.summary = summary;
        this.failures = failures;
        this.retry = retry;
        this.discard = discard;
        this.settings = settings;
    }

    @GetMapping("/admin/jobs")
    public String queue(@RequestParam(name = "offset", defaultValue = "0") int offset,
                        Model model) {
        model.addAttribute("summary", summary.now());
        model.addAttribute("page", failures.page(settings.failedPageSize(), offset));
        return "jobs/AdminJobs";
    }

    /** Runs a failing job now, clearing its backoff and its failure streak. */
    @PostMapping("/admin/jobs/retry")
    public String retry(@RequestParam("taskName") String taskName,
                        @RequestParam("instanceId") String instanceId,
                        Authentication authentication, HttpServletRequest request,
                        RedirectAttributes flash) {
        JobActionOutcome outcome = retry.retry(operator(authentication, request), taskName,
                instanceId);
        report(flash, outcome, taskName, "is queued to run now.", "has been retried");
        return "redirect:/admin/jobs";
    }

    /** Abandons a job that will never succeed. The work is not done and does not come back. */
    @PostMapping("/admin/jobs/discard")
    public String discard(@RequestParam("taskName") String taskName,
                          @RequestParam("instanceId") String instanceId,
                          Authentication authentication, HttpServletRequest request,
                          RedirectAttributes flash) {
        JobActionOutcome outcome = discard.discard(operator(authentication, request), taskName,
                instanceId);
        report(flash, outcome, taskName, "has been discarded. The work was not done.",
                "has been discarded");
        return "redirect:/admin/jobs";
    }

    private static void report(RedirectAttributes flash, JobActionOutcome outcome, String taskName,
                               String done, String past) {
        switch (outcome) {
            case DONE -> InertiaFlash.success(flash, taskName + " " + done);
            case RUNNING -> InertiaFlash.failure(flash, taskName + " is running right now. A "
                    + "running job is stopped by cancelling its work, not by touching its row - "
                    + "wait for it to finish and try again.");
            case GONE -> InertiaFlash.failure(flash, "That job is no longer queued. It either "
                    + "succeeded or " + past + " already.");
        }
    }

    /**
     * The operator, from the security context.
     *
     * <p>{@code Authentication.getName()} is whatever {@code auth} authenticated the
     * visitor as - an account id or an email address, the same two values
     * {@code ResolveCurrentAccount} handles. When it is an id the audit row gets its
     * foreign key; when it is an address the label still names who did it, which is what
     * the trail keeps after the account is deleted anyway.
     */
    private static AuditActor operator(Authentication authentication, HttpServletRequest request) {
        String name = authentication == null || authentication.getName() == null
                ? "unknown administrator"
                : authentication.getName();
        return new AuditActor(AuditActorKind.ACCOUNT, asUuid(name), null, null, name,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader(AuditActor.REQUEST_ID_HEADER));
    }

    private static UUID asUuid(String candidate) {
        if (candidate.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
