package lhqm.furimeo.wisper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes what a service is: its image or its build, its command, its limits, its
 * isolation.
 *
 * <p>The kind and the address are not on the form. A service that turned from an app into
 * a site would have to satisfy CHECK constraints its existing columns were written under,
 * and a service whose slug moved would break every URL, webhook and bookmark pointing at
 * it. Both are a new service and a deletion, said out loud.
 *
 * <p>Only the <em>increase</em> in memory or CPU is charged against the plan. Shrinking a
 * service is always allowed, including for an organization already over its limit -
 * refusing to let a customer use less is how a quota system turns an overage into a
 * deadlock.
 *
 * <p>Ends by republishing the spec. A limit or an image the node has not been told about
 * is a change the customer can see in the panel and not in reality, which is the exact
 * gap this platform's desired-state model exists to close.
 */
@Component
public class UpdateServiceRuntime {

    private final ServiceRepository services;
    private final QuotaGuard quotas;
    private final SecretCipher cipher;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public UpdateServiceRuntime(ServiceRepository services, QuotaGuard quotas, SecretCipher cipher,
                                PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.quotas = quotas;
        this.cipher = cipher;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param submitted the whole settings form; every field is present, so an empty
     *                  numeric input means the platform default rather than "unchanged".
     *                  The one exception is {@code repositoryCredential}, which cannot be
     *                  shown back to the browser and therefore keeps its stored value
     *                  when left blank.
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a shape the kind does not allow
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    when growing past the plan
     */
    @Transactional
    public Service update(AuditActor actor, Membership membership, UUID serviceId,
                          ServiceDraft submitted) {
        membership.requireWrite("service.update");

        Service before = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (before.isArchived()) {
            throw new RequestRejected(null,
                    "This service is archived. Restore its project before changing it.");
        }

        // The kind is the service's, not the form's: a submission that disagrees is a
        // stale page, and honouring it would rewrite the row under a different set of
        // constraints.
        ServiceDraft draft = ServiceShape.validated(withKindOf(before, submitted));

        chargeGrowth(membership.organizationId(), before, draft);

        String credential = draft.repositoryCredential() == null
                ? before.repositoryCredential()
                : cipher.encrypt(draft.repositoryCredential());
        // Clearing the repository clears the credential with it, otherwise a key stays
        // behind for a repository that is no longer configured.
        if (draft.repositoryUrl() == null) {
            credential = null;
        }

        String change = describeChange(before, draft);
        Service updated = services.save(before.applying(draft, credential));

        specs.forService(updated.id(), "service " + updated.slug() + " updated");

        audit.record(AuditEntry.succeeded(actor, "service.update",
                AuditTarget.of("service", updated.id(), updated.name()),
                membership.organizationId(), change));
        return updated;
    }

    /**
     * Charges only what the change adds.
     *
     * <p>{@code QuotaGuard.require} compares an amount against what is already used, and
     * the existing service is part of that usage - so asking for the new total would
     * charge the customer twice for the memory they already have.
     */
    private void chargeGrowth(UUID organizationId, Service before, ServiceDraft after) {
        long extraMemory = after.memoryBytes() - before.memoryBytes();
        if (extraMemory > 0) {
            quotas.require(organizationId, QuotaResource.MEMORY_BYTES, extraMemory);
        }
        long extraCpu = after.cpuMillicores() - before.cpuMillicores();
        if (extraCpu > 0) {
            quotas.require(organizationId, QuotaResource.CPU_MILLICORES, extraCpu);
        }
    }

    private static ServiceDraft withKindOf(Service service, ServiceDraft submitted) {
        if (submitted.kind() == service.kind()) {
            return submitted;
        }
        return new ServiceDraft(submitted.name(), submitted.slug(), service.kind(),
                submitted.image(), submitted.command(), submitted.entrypoint(),
                submitted.workingDir(), submitted.containerPort(), submitted.healthCheckPath(),
                submitted.healthCheckIntervalSeconds(), submitted.restartPolicy(),
                submitted.buildPreset(), submitted.buildCommand(), submitted.buildOutputDir(),
                submitted.keepReleases(), submitted.repositoryUrl(), submitted.repositoryBranch(),
                submitted.repositoryCredential(), submitted.autoDeploy(),
                submitted.cpuMillicores(), submitted.memoryBytes(), submitted.diskBytes(),
                submitted.pidsLimit(), submitted.requiredTags(), submitted.runtimeIsolation(),
                submitted.isolationReason());
    }

    /**
     * Which fields moved, for the audit trail. Names only.
     *
     * <p>A repository credential is not in this list even by name-and-nothing-else,
     * because the entry that says one changed is one bit more than the trail needs and
     * the entry that says what it changed to would be a second copy of the secret
     * (panel-ports.md §2.4). The word "credential" appears; the value never does.
     */
    private static String describeChange(Service before, ServiceDraft after) {
        List<String> changed = new ArrayList<>();
        note(changed, "name", before.name(), after.name());
        note(changed, "image", before.image(), after.image());
        note(changed, "command", CommandLine.format(before.command()),
                CommandLine.format(after.commandArray()));
        note(changed, "entrypoint", CommandLine.format(before.entrypoint()),
                CommandLine.format(after.entrypointArray()));
        note(changed, "workingDir", before.workingDir(), after.workingDir());
        note(changed, "port", text(before.containerPort()), text(after.containerPort()));
        note(changed, "healthCheck", before.healthCheckPath(), after.healthCheckPath());
        note(changed, "restartPolicy", before.restartPolicy().name(),
                after.restartPolicy().name());
        note(changed, "build", text(before.buildPreset()) + " " + text(before.buildCommand())
                + " " + text(before.buildOutputDir()), text(after.buildPreset()) + " "
                + text(after.buildCommand()) + " " + text(after.buildOutputDir()));
        note(changed, "repository", text(before.repositoryUrl()) + "#"
                        + text(before.repositoryBranch()),
                text(after.repositoryUrl()) + "#" + text(after.repositoryBranch()));
        note(changed, "autoDeploy", String.valueOf(before.autoDeploy()),
                String.valueOf(after.autoDeploy()));
        // A new credential was typed, or the repository went away and took the stored one
        // with it. Either way the trail records that it moved and not what it is.
        if (after.repositoryCredential() != null
                || (before.repositoryCredential() != null && after.repositoryUrl() == null)) {
            changed.add("credential");
        }
        note(changed, "limits", before.cpuMillicores() + "m/" + before.memoryBytes() + "B/"
                        + before.diskBytes() + "B/" + before.pidsLimit(),
                after.cpuMillicores() + "m/" + after.memoryBytes() + "B/" + after.diskBytes()
                        + "B/" + after.pidsLimit());
        note(changed, "isolation", before.runtimeIsolation().name(),
                after.runtimeIsolation().name());
        note(changed, "tags", String.join(",", before.requiredTags()),
                String.join(",", after.requiredTags()));
        return changed.isEmpty() ? "Saved with no field changed" : "Changed "
                + String.join(", ", changed);
    }

    private static void note(List<String> changed, String field, String before, String after) {
        if (!text(before).equals(text(after))) {
            changed.add(field);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
