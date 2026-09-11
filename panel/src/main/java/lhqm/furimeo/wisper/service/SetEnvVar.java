package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Sets an environment variable, creating it or replacing its value.
 *
 * <p>One use-case for both because a customer typing {@code PORT} into the form does not
 * think of it as two operations, and because the alternative - "create" that fails on a
 * name that exists - makes the form's second submission an error message rather than the
 * edit it plainly is. The name is the identity; the value is what moves.
 *
 * <h2>Two refusals worth naming</h2>
 *
 * <p><strong>A name already used by a secret.</strong> The two tables have separate
 * unique indexes, so the database would accept {@code DATABASE_URL} in both and the node
 * would receive one environment with two entries for it. Which one wins would depend on
 * the order the spec builder happened to concatenate them. Refused here, where the
 * customer can be told to pick a different name or delete the secret.
 *
 * <p><strong>A runtime variable on a static site.</strong> A site has no process, so a
 * variable that is not marked build-time would be stored, shown, sent and read by nobody.
 * Refusing it says that out loud instead of letting a customer debug an empty value for an
 * afternoon.
 */
@Component
public class SetEnvVar {

    /** Big enough for a certificate chain, small enough to catch a pasted file. */
    private static final int MAX_VALUE_LENGTH = 32_768;

    private final ServiceRepository services;
    private final EnvVarRepository envVars;
    private final SecretRepository secrets;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public SetEnvVar(ServiceRepository services, EnvVarRepository envVars,
                     SecretRepository secrets, PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.envVars = envVars;
        this.secrets = secrets;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param value     stored verbatim, including whitespace: an indented PEM block and a
     *                  trailing newline are both meaningful to whatever reads them
     * @param buildTime also give it to the build container
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a bad name, an oversized value,
     *                                                  a name a secret already holds, or a
     *                                                  runtime variable on a site
     */
    @Transactional
    public EnvVar set(AuditActor actor, Membership membership, UUID serviceId, String rawName,
                      String value, boolean buildTime) {
        membership.requireWrite("env_var.set");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        String name = EnvVarName.require(rawName, "name");
        String text = value == null ? "" : value;
        if (text.length() > MAX_VALUE_LENGTH) {
            throw new RequestRejected("value",
                    "That value is longer than " + MAX_VALUE_LENGTH + " characters. Mount a file "
                            + "on a volume instead.");
        }
        if (text.indexOf('\0') >= 0) {
            throw new RequestRejected("value",
                    "A null byte cannot be part of an environment value.");
        }
        if (service.isSite() && !buildTime) {
            throw new RequestRejected("buildTime",
                    "A static site has no running process, so a variable is only read while it "
                            + "is being built. Mark it as available during the build.");
        }
        if (secrets.existsByServiceIdAndName(serviceId, name)) {
            throw new RequestRejected("name",
                    name + " is already a secret on this service. Delete the secret first, or "
                            + "use a different name.");
        }

        EnvVar saved = envVars.findByServiceIdAndName(serviceId, name)
                .map(existing -> envVars.save(existing.withValue(text, buildTime)))
                .orElseGet(() -> envVars.save(
                        EnvVar.of(UUID.randomUUID(), serviceId, name, text, buildTime)));

        specs.forService(serviceId, "environment of " + service.slug() + " changed");

        audit.record(AuditEntry.succeeded(actor, "env_var.set",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(),
                "Set " + name + (buildTime ? " (also at build time)" : "")));
        return saved;
    }
}
