package lhqm.furimeo.wisper.domain;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.ServiceView;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The hostnames pointed at one service.
 *
 * <p>Renders {@code features/domain/ServiceDomainsPage.tsx}. Every write redirects back to
 * the same page, so a customer who reloads after adding a hostname does not resubmit it.
 *
 * <p>Numbers and enums arrive as strings and are parsed here rather than bound. An empty
 * text input posts an empty string, and letting Spring convert that into an {@code Integer}
 * produces a 400 with a message about a conversion service - which is what a customer sees
 * instead of "a port is between 1 and 65535".
 */
@Controller
public class DomainController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final ListServiceDomains listing;
    private final QuotaGuard quotas;
    private final AddDomain addDomain;
    private final VerifyDomainOwnership verification;
    private final SetPrimaryDomain setPrimary;
    private final RemoveDomain removeDomain;
    private final CheckDomainDns checkDomainDns;
    private final RecordRefusal refusals;

    public DomainController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                            ServiceRepository services, ListServiceDomains listing,
                            QuotaGuard quotas, AddDomain addDomain,
                            VerifyDomainOwnership verification, SetPrimaryDomain setPrimary,
                            RemoveDomain removeDomain, CheckDomainDns checkDomainDns,
                            RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.listing = listing;
        this.quotas = quotas;
        this.addDomain = addDomain;
        this.verification = verification;
        this.setPrimary = setPrimary;
        this.removeDomain = removeDomain;
        this.checkDomainDns = checkDomainDns;
        this.refusals = refusals;
    }

    /**
     * Props: {@code service}, {@code domains}, {@code allowance} for the domain count,
     * {@code viewerRole}, and {@code nodeAddress} - the A record the customer has to create,
     * or null when nothing holds the service yet (pages.md §8).
     */
    @GetMapping("/services/{serviceId}/domains")
    public String domains(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        ServiceDomains page = listing.of(serviceId);
        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("domains", page.domains());
        model.addAttribute("nodeAddress", page.nodeAddress());
        model.addAttribute("allowance",
                quotas.allowanceFor(membership.organizationId(), QuotaResource.DOMAIN));
        model.addAttribute("viewerRole", membership.role());
        return "domain/ServiceDomains";
    }

    @PostMapping("/services/{serviceId}/domains")
    public String add(@PathVariable UUID serviceId,
                      @RequestParam(name = "hostname", required = false) String hostname,
                      @RequestParam(name = "tlsMode", defaultValue = "ON_DEMAND") String tlsMode,
                      @RequestParam(name = "targetPort", required = false) String targetPort,
                      @RequestParam(name = "forceHttps", defaultValue = "true") boolean forceHttps,
                      @RequestParam(name = "redirectToHostname", required = false) String redirect,
                      HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Domain added = addDomain.add(actor, membership, serviceId, hostname,
                    parseTlsMode(tlsMode), parsePort(targetPort), forceHttps, redirect);
            InertiaFlash.success(flash, added.hostname() + " is added. Point it at this "
                    + "service's node and wisper will verify it and obtain a certificate.");
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "domain.add", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    /**
     * The "Check now" button.
     *
     * <p>A check that does not verify is not a failure of the request, so the outcome is a
     * message and not an error: the customer is told exactly what the resolver returned and
     * what it was compared against.
     */
    @PostMapping("/services/{serviceId}/domains/{domainId}/verify")
    public String verify(@PathVariable UUID serviceId, @PathVariable UUID domainId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            VerificationResult result =
                    verification.verifyNow(actor, membership, serviceId, domainId);
            if (result.isVerified()) {
                InertiaFlash.success(flash, result.message());
            } else {
                InertiaFlash.failure(flash, result.message());
            }
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "domain.verify", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/domains/{domainId}/primary")
    public String primary(@PathVariable UUID serviceId, @PathVariable UUID domainId,
                          HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Domain promoted = setPrimary.promote(actor, membership, serviceId, domainId);
            InertiaFlash.success(flash, promoted.hostname()
                    + " is now this service's main address.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, SetPrimaryDomain.ACTION, target(serviceId),
                    membership.organizationId(), refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/domains/{domainId}/remove")
    public String remove(@PathVariable UUID serviceId, @PathVariable UUID domainId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Domain removed = removeDomain.remove(actor, membership, serviceId, domainId);
            InertiaFlash.success(flash, removed.hostname() + " is removed. It is available for "
                    + "anyone to add again, so remove the DNS record too if you are done with it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "domain.remove", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    private static DomainTlsMode parseTlsMode(String raw) {
        try {
            return DomainTlsMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new RequestRejected("tlsMode",
                    "Choose whether wisper should obtain a certificate for this hostname.");
        }
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new RequestRejected("targetPort", "A port is a number between 1 and 65535.");
        }
    }

    @GetMapping(path = "/services/{serviceId}/domains/{domainId}/dns-check",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CheckDomainDns.Result dnsCheck(@PathVariable UUID serviceId,
                                          @PathVariable UUID domainId) {
        AccountRef account = currentAccount.require();
        memberships.forService(account.id(), serviceId);
        return checkDomainDns.check(serviceId, domainId);
    }

    private static String back(UUID serviceId) {
        return "redirect:/services/" + serviceId + "/domains";
    }

    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }
}
