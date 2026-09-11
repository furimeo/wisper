package lhqm.furimeo.wisper.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of {@code audit_log.action}, as {@code docs/contracts/panel-ports.md}
 * §2.4 fixes it.
 *
 * <p>Not an enum. Every caller writes the literal - {@code "service.start"} - next to the
 * thing it is auditing, in fifteen packages, and turning that into an import would put a
 * shared file in the middle of every write in the panel. This list exists for the two
 * jobs a bare literal cannot do: filling the filter on {@code /admin/audit} with the
 * actions that exist rather than the ones that happen to be in the table today, and
 * refusing a filter value that is not one of them.
 *
 * <p>The database only enforces the <em>shape</em> ({@code audit_log_action_shape}), so
 * nothing stops a new action being written. That is deliberate - a contract change should
 * not require a migration - and it is why {@link #isKnown} exists rather than a CHECK.
 * Adding one here without adding it to panel-ports.md is how the two drift apart.
 */
public final class AuditAction {

    /**
     * Every action panel-ports.md §2.4 names, in its order.
     *
     * <p>The order is the contract's, not alphabetical, because it is the order the filter's
     * option groups appear in on {@code /admin/audit}. Adding one here without adding it
     * there, or the other way round, is how the two drift apart.
     */
    public static final List<String> ALL = List.of(
            "account.sign_in", "account.sign_out", "account.password_change",
            "account.two_factor_enable", "account.two_factor_disable",
            "api_token.create", "api_token.revoke",
            "organization.suspend", "organization.resume",
            "member.invite", "member.remove", "member.role_change",
            "plan.assign", "quota_override.grant", "quota_override.revoke",
            "project.create", "project.archive", "project.delete",
            "service.create", "service.update", "service.start", "service.stop",
            "service.delete",
            "env_var.set", "env_var.delete", "secret.set", "secret.delete",
            "volume.create", "volume.resize", "volume.delete",
            "cron_task.create", "cron_task.update", "cron_task.delete",
            "deployment.start", "deployment.cancel", "deployment.rollback",
            "domain.add", "domain.verify", "domain.remove",
            "database.create", "database.rotate_password", "database.delete",
            "backup.create", "backup.run", "backup.delete", "backup.restore",
            "restore_point.delete",
            "node.create", "node.enrol", "node.token_issue", "node.token_revoke",
            "node.drain", "node.upgrade", "node.suspend", "node.delete",
            "files.upload", "files.delete", "files.move", "files.chmod",
            "files.archive", "files.extract", "files.create_directory", "files.path_escape",
            "terminal.open", "terminal.close",
            "job.retry", "job.discard");

    private AuditAction() {
    }

    /** Whether a filter value is one the panel actually writes. */
    public static boolean isKnown(String action) {
        return action != null && ALL.contains(action);
    }

    /**
     * The actions grouped by the object they act on, for the filter's option groups.
     *
     * <p>Derived from the names rather than listed twice: the prefix before the dot
     * <em>is</em> the group, and a second hand-written grouping would be a place for a new
     * action to go missing.
     */
    public static Map<String, List<String>> byDomain() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String action : ALL) {
            grouped.computeIfAbsent(domainOf(action), key -> new ArrayList<>()).add(action);
        }
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        grouped.forEach((domain, actions) -> ordered.put(domain, List.copyOf(actions)));
        // Not Map.copyOf: its iteration order is unspecified, and the order here is the
        // order the filter's option groups appear in.
        return Collections.unmodifiableMap(ordered);
    }

    /** The object kinds that appear in the trail, for the second filter. */
    public static Set<String> domains() {
        Set<String> domains = new LinkedHashSet<>();
        ALL.forEach(action -> domains.add(domainOf(action)));
        return Collections.unmodifiableSet(domains);
    }

    private static String domainOf(String action) {
        return action.substring(0, action.indexOf('.'));
    }
}
