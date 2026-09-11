package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lhqm.furimeo.wisper.web.SharedPropsContributor;

/**
 * Puts the organization switcher on every page: {@code organizations} and
 * {@code organization}.
 *
 * <p>Prop shapes, which the TypeScript declaration has to match:
 *
 * <pre>
 * organizations: { id, name, slug, role, status, suspensionReason,
 *                  accepted, invitedAt }[]   // memberships first, then invitations
 * organization:  the same shape, or null      // the one being looked at
 * </pre>
 *
 * <h2>Which organization is "current"</h2>
 *
 * <ol>
 * <li>The {@code organizationId} in the URL, when the request is on an organization
 *     screen. The address bar is the most specific thing the visitor said.</li>
 * <li>Otherwise the last one they were on, remembered in the session - so a customer with
 *     three tenants does not land back on the alphabetically first one after every
 *     redirect.</li>
 * <li>Otherwise their first membership, or null when they have none. Null is a real
 *     answer, and the chrome renders "no organizations yet" for it rather than a blank
 *     bar.</li>
 * </ol>
 *
 * <p>An invitation is never made current: it is in {@code organizations} so the list page
 * can offer it, but selecting one would put a tenant the visitor cannot read into the
 * chrome of every page.
 */
@Component
@Order(20)
public class CurrentOrganizationProps implements SharedPropsContributor {

    /** Where the last-viewed organization is remembered between requests. */
    static final String SESSION_KEY = "wisper.currentOrganizationId";

    private final ResolveCurrentAccount currentAccount;
    private final ListOrganizationsForAccount organizations;

    public CurrentOrganizationProps(ResolveCurrentAccount currentAccount,
                                    ListOrganizationsForAccount organizations) {
        this.currentAccount = currentAccount;
        this.organizations = organizations;
    }

    @Override
    public void contribute(Map<String, Object> props, HttpServletRequest request) {
        Optional<AccountRef> account = currentAccount.current();
        if (account.isEmpty()) {
            // The sign-in page renders through the same pipeline. Empty, not absent, so
            // the client never has to check for undefined.
            props.put("organizations", List.of());
            props.put("organization", null);
            return;
        }

        List<OrganizationSummary> all = organizations.all(account.get().id());
        props.put("organizations", all);

        OrganizationSummary current = choose(all, request);
        props.put("organization", current);
        if (current != null) {
            remember(request, current.id());
        }
    }

    private OrganizationSummary choose(List<OrganizationSummary> all,
                                       HttpServletRequest request) {
        UUID fromPath = organizationIdInPath(request);
        if (fromPath != null) {
            OrganizationSummary match = find(all, fromPath);
            if (match != null) {
                return match;
            }
        }
        UUID remembered = remembered(request);
        if (remembered != null) {
            OrganizationSummary match = find(all, remembered);
            if (match != null && match.accepted()) {
                return match;
            }
        }
        return all.stream().filter(OrganizationSummary::accepted).findFirst().orElse(null);
    }

    private static OrganizationSummary find(List<OrganizationSummary> all, UUID id) {
        return all.stream().filter(summary -> summary.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The {@code {organizationId}} path variable, when this request matched a route that
     * has one. Spring puts the resolved variables on the request before the view renders,
     * so this needs no parsing of the URL and cannot disagree with what the controller
     * saw.
     */
    private static UUID organizationIdInPath(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get("organizationId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static UUID remembered(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof UUID id ? id : null;
    }

    private static void remember(HttpServletRequest request, UUID organizationId) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_KEY, organizationId);
        }
    }
}
