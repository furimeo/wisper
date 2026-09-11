package lhqm.furimeo.wisper.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The platform-wide audit trail at {@code GET /admin/audit}.
 *
 * <p>Under {@code /admin/**}, which {@code SecurityConfig} gates on {@code ROLE_ADMIN}.
 * Every organization's entries are here, which is exactly why a customer session must not
 * reach it.
 *
 * <p>Read-only, and there is no second method. Nothing in this package edits or deletes an
 * entry, so there is no button that could.
 *
 * <p>Renders {@code features/audit/AdminAuditPage.tsx} with props {@code page} (the rows
 * and the pager), {@code filter} (echoed back so the form redraws with what was asked for)
 * and {@code actions} (the vocabulary, grouped, for the filter's option groups).
 *
 * <p>Bad filter values are ignored rather than refused. A trail that answers a mistyped
 * date with an error page sends the operator away at the moment they were looking for
 * something; answering with the unfiltered log and the filter form still filled in lets
 * them fix it in place.
 */
@Controller
public class AdminAuditController {

    private final SearchAuditLog search;

    public AdminAuditController(SearchAuditLog search) {
        this.search = search;
    }

    @GetMapping("/admin/audit")
    public String trail(@RequestParam(name = "organizationId", required = false) String organization,
                        @RequestParam(name = "accountId", required = false) String account,
                        @RequestParam(name = "nodeId", required = false) String node,
                        @RequestParam(name = "action", required = false) String action,
                        @RequestParam(name = "targetKind", required = false) String targetKind,
                        @RequestParam(name = "targetId", required = false) String target,
                        @RequestParam(name = "outcome", required = false) String outcome,
                        @RequestParam(name = "from", required = false) String from,
                        @RequestParam(name = "to", required = false) String to,
                        @RequestParam(name = "limit", defaultValue = "0") int limit,
                        @RequestParam(name = "offset", defaultValue = "0") int offset,
                        Model model) {
        AuditLogQuery query = new AuditLogQuery(
                uuid(organization), uuid(account), uuid(node), action, targetKind, uuid(target),
                outcomeOf(outcome), instant(from), instant(to), limit, offset)
                .sanitised();

        model.addAttribute("page", search.find(query));
        model.addAttribute("filter", echo(query));
        model.addAttribute("actions", AuditAction.byDomain());
        // The kinds a target can be: the same words the actions are prefixed with, which is
        // what "everything that happened to a service" filters on.
        model.addAttribute("targetKinds", AuditAction.domains());
        model.addAttribute("outcomes", AuditOutcome.values());
        return "audit/AdminAudit";
    }

    /**
     * The filter as the form should redraw it.
     *
     * <p>Built from the sanitised query rather than from the raw parameters, so a value
     * the search dropped does not stay in the box claiming to be in effect.
     */
    private static Map<String, Object> echo(AuditLogQuery query) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("organizationId", query.organizationId());
        filter.put("accountId", query.accountId());
        filter.put("nodeId", query.nodeId());
        filter.put("action", query.action());
        filter.put("targetKind", query.targetKind());
        filter.put("targetId", query.targetId());
        filter.put("outcome", query.outcome());
        filter.put("from", query.from());
        filter.put("to", query.to());
        filter.put("limit", query.limit());
        filter.put("offset", query.offset());
        return filter;
    }

    private static UUID uuid(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate.strip());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static AuditOutcome outcomeOf(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        for (AuditOutcome outcome : AuditOutcome.values()) {
            if (outcome.name().equalsIgnoreCase(candidate.strip())) {
                return outcome;
            }
        }
        return null;
    }

    /** An ISO-8601 instant, as the client's date picker sends it. */
    private static Instant instant(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(candidate.strip());
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }
}
