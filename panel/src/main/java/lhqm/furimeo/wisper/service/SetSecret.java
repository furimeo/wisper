package lhqm.furimeo.wisper.service;

import java.time.Instant;
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
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Sets a secret environment value, creating it or rotating it.
 *
 * <p>The value goes through {@link SecretCipher} on its way in and is never read back by
 * anything in this package. The panel's own screens see {@link SecretView}, which has no
 * value in it; the only consumer of the plaintext is the node, which receives it in a spec
 * over an authenticated stream.
 *
 * <p>There is no "show me the secret" route and there will not be one. A customer who has
 * forgotten a value sets a new one - which is the same thing they would have to do if the
 * value had leaked, so the recovery path is the safe path either way.
 *
 * <p>The same two refusals as {@link SetEnvVar}: a name a plain variable already holds,
 * because both land in one environment and the winner would be whichever list the spec
 * builder concatenated last; and a runtime secret on a static site, which has no process
 * to read one.
 */
@Component
public class SetSecret {

    /** Room for a private key or a service-account JSON, not for a database dump. */
    private static final int MAX_VALUE_LENGTH = 32_768;

    private final ServiceRepository services;
    private final SecretRepository secrets;
    private final EnvVarRepository envVars;
    private final SecretCipher cipher;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public SetSecret(ServiceRepository services, SecretRepository secrets,
                     EnvVarRepository envVars, SecretCipher cipher, PublishNodeSpec specs,
                     AuditTrail audit) {
        this.services = services;
        this.secrets = secrets;
        this.envVars = envVars;
        this.cipher = cipher;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param value plaintext, encrypted here. It is not trimmed: a trailing newline in a
     *              PEM block is part of the key, and silently removing it produces a
     *              failure three layers away that nobody traces back to a text box.
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a bad or colliding name, an
     *                                                  empty or oversized value, or a
     *                                                  runtime secret on a site
     */
    @Transactional
    public SecretView set(AuditActor actor, Membership membership, UUID serviceId, String rawName,
                          String value, boolean buildTime) {
        membership.requireWrite("secret.set");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        String name = EnvVarName.require(rawName, "name");
        if (value == null || value.isEmpty()) {
            throw new RequestRejected("value",
                    "A secret with no value is not a secret. Delete it instead, or give it one.");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new RequestRejected("value",
                    "That value is longer than " + MAX_VALUE_LENGTH + " characters. Put a file "
                            + "that large on a volume.");
        }
        if (value.indexOf('\0') >= 0) {
            throw new RequestRejected("value",
                    "A null byte cannot be part of an environment value.");
        }
        if (service.isSite() && !buildTime) {
            throw new RequestRejected("buildTime",
                    "A static site has no running process, so a secret is only read while it is "
                            + "being built. Mark it as available during the build.");
        }
        if (envVars.existsByServiceIdAndName(serviceId, name)) {
            throw new RequestRejected("name",
                    name + " is already a plain variable on this service. Delete it first, or "
                            + "use a different name.");
        }

        String envelope = cipher.encrypt(value);
        Secret saved = secrets.findByServiceIdAndName(serviceId, name)
                .map(existing -> secrets.save(
                        existing.rotated(envelope, buildTime, Instant.now())))
                .orElseGet(() -> secrets.save(
                        Secret.of(UUID.randomUUID(), serviceId, name, envelope, buildTime)));

        specs.forService(serviceId, "secrets of " + service.slug() + " changed");

        // The name, and nothing else. An audit detail that quoted the value would be a
        // second copy of the secret in a table nobody encrypts (panel-ports.md §2.4).
        audit.record(AuditEntry.succeeded(actor, "secret.set",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(),
                "Set " + name + (buildTime ? " (also at build time)" : "")));

        return new SecretView(saved.id(), saved.name(), saved.buildTime(),
                saved.lastRotatedAt() == null ? saved.createdAt() : saved.lastRotatedAt(),
                saved.createdAt());
    }
}
