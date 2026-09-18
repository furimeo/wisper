package lhqm.furimeo.wisper.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import lhqm.furimeo.wisper.domain.Domain;
import lhqm.furimeo.wisper.domain.DomainRepository;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The service overview: what it is, what it is doing, and how to get to everything else
 * about it.
 *
 * <p>Owns {@code GET /services/{serviceId}} and nothing more. The rest of
 * {@code /services/**} is split by screen - settings, environment, volumes, scheduled
 * commands here; deployments, domains, files, terminal, logs and metrics in the packages
 * that own those (panel-http.md).
 *
 * <p>Renders {@code features/service/ServiceOverviewPage.tsx}.
 */
@Controller
public class ServiceController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final ListServicesInProject serviceStatus;
    private final ProjectRepository projects;
    private final EnvVarRepository envVars;
    private final SecretRepository secrets;
    private final VolumeRepository volumes;
    private final CronTaskRepository tasks;
    private final DomainRepository domains;

    public ServiceController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                             ServiceRepository services, ListServicesInProject serviceStatus,
                             ProjectRepository projects, EnvVarRepository envVars,
                             SecretRepository secrets, VolumeRepository volumes,
                             CronTaskRepository tasks, DomainRepository domains) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.serviceStatus = serviceStatus;
        this.projects = projects;
        this.envVars = envVars;
        this.secrets = secrets;
        this.volumes = volumes;
        this.tasks = tasks;
        this.domains = domains;
    }

    /**
     * Props: {@code service} (a {@link ServiceView} - no secrets in it), {@code status}
     * (the node's view, or null before it has reported), {@code project},
     * {@code viewerRole} and {@code counts} for the tab badges.
     *
     * <p>{@code status} being null is a real state and the page says "not placed yet"
     * rather than drawing an empty pill. Intent and fact are two props on purpose: a
     * service the customer asked to run and the node says has crashed is the interesting
     * case, and one prop could not show it.
     */
    @GetMapping("/services/{serviceId}")
    public String overview(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        Project project = projects.findById(service.projectId())
                .orElseThrow(() -> NotFoundException.of("project", service.projectId()));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("envVars", envVars.countByServiceId(serviceId));
        counts.put("secrets", secrets.countByServiceId(serviceId));
        counts.put("volumes", volumes.countByServiceId(serviceId));
        counts.put("scheduledTasks", tasks.countByServiceId(serviceId));

        Domain primary = domains.findPrimaryOf(serviceId).orElse(null);

        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("status", serviceStatus.one(serviceId).orElse(null));
        model.addAttribute("project", project);
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("counts", counts);
        model.addAttribute("primaryHostname", primary != null ? primary.hostname() : null);
        return "service/ServiceOverview";
    }
}
