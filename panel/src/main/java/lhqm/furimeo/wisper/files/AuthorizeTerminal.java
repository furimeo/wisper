package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The check a terminal starts with.
 *
 * <p>Two questions, and the second one is the whole reason the terminal is not a read.
 * A shell inside a customer's container can delete their database, rewrite their
 * application and read every secret the platform injected into it - everything the
 * application itself can do. It is therefore a write, and a {@code VIEWER} does not get one
 * even though they may read every file in the same volume through the file manager.
 *
 * <p>Every request in a session is re-checked, not only the one that opened it. A membership
 * revoked while somebody had a shell open must end their shell at the next keystroke, and
 * checking once at open time would leave it live until the idle timeout - which is the
 * fifteen minutes after somebody was removed for a reason.
 */
@Component
public class AuthorizeTerminal {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final LocateService services;

    public AuthorizeTerminal(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                             LocateService services) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
    }

    /**
     * Permission to use a shell on this service.
     *
     * @param action the dotted audit action being attempted, so a refusal names it
     * @throws NotFoundException if the service is not the caller's
     * @throws PermissionDenied  for a {@code VIEWER}
     */
    public TerminalAccess forService(UUID serviceId, String action, HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        membership.requireWrite(action);
        return new TerminalAccess(services.byId(serviceId), membership,
                AuditActor.account(account.id(), account.email(), request));
    }

    /**
     * The same, for the page itself.
     *
     * <p>A {@code VIEWER} may open the page and be told they cannot have a shell, which is a
     * better answer than a 403 on a link the navigation offered them.
     */
    public TerminalAccess toView(UUID serviceId, HttpServletRequest request) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        return new TerminalAccess(services.byId(serviceId), membership,
                AuditActor.account(account.id(), account.email(), request));
    }
}
