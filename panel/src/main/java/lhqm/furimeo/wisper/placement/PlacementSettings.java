package lhqm.furimeo.wisper.placement;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Everything under {@code wisper.placement}: the values that go into a {@code NodeSpec}
 * and have no column of their own.
 *
 * <p>Every setting here exists because the spec asks for something the schema does not
 * store. That is deliberate in both directions - a per-service knob that nobody would ever
 * turn is a form field that confuses a customer, and a hard-coded number in a spec builder
 * is a number that cannot be changed without a release. Where a value is genuinely a
 * platform decision it lives here, once.
 *
 * <p>Declared in the package that reads it, so adding a setting touches nobody else's file
 * (panel-configuration.md). {@link DefaultValue} is on every component: record binding has
 * no constructor fallback, and a key nobody set otherwise binds to {@code null} and
 * surfaces much later as a {@code NullPointerException} inside a generation nobody was
 * watching.
 *
 * @param keepReleases            floor for {@code RetentionPolicy.keep_releases}. The node
 *                                is told the largest of this and the {@code keep_releases}
 *                                of the sites it holds, so no site can lose a release it
 *                                was promised.
 * @param keepBuildWorkspaces     checkouts and caches kept after a build. Zero makes every
 *                                build a cold one.
 * @param containerLogMaxBytes    Docker json-file rotation size. Unrotated container logs
 *                                are the classic way a node fills its disk overnight.
 * @param containerLogMaxFiles    how many rotated files to keep
 * @param orphanUploadTtl         how long a half-finished chunked upload survives on the
 *                                node. A phone that lost signal leaves parts nobody comes
 *                                back for.
 * @param uploadStagingRootId     the id of the node's {@code UPLOAD_STAGING} file root.
 *                                <strong>Must match {@code wisper.deploy.staging-root-id}</strong>:
 *                                this package puts the root in the spec and {@code deploy}
 *                                uploads into it by name.
 * @param stopGrace               how long SIGTERM gets before SIGKILL. A database-backed
 *                                app killed at two seconds loses what it had in flight.
 * @param nofileLimit             RLIMIT_NOFILE. Node and Go servers open a lot of sockets
 *                                and the default is low enough that the failure looks like
 *                                a networking bug.
 * @param databaseEngineMillicores what a shared engine container is allowed. Also what
 *                                {@link LoadNodeCapacity} reserves for one, so the ceiling
 *                                the node applies and the figure the scheduler subtracts
 *                                cannot drift.
 * @param databaseEngineMemory    the same for memory
 * @param databaseEngineMaxConnections connection ceiling for a shared engine. Without one,
 *                                a single leaking pool locks every other customer out of
 *                                the same container.
 * @param healthCheckTimeout      how long one health probe may take
 * @param healthCheckRetries      consecutive failures before a container is called
 *                                unhealthy
 * @param healthCheckStartPeriod  grace after start during which failures do not count.
 *                                Without it, anything that takes ten seconds to boot is
 *                                permanently unhealthy.
 * @param tenantNetworkPrefix     the Docker network name is this plus the organization id.
 *                                One network per tenant: two customers on one node must not
 *                                reach each other's containers by address.
 */
@ConfigurationProperties("wisper.placement")
public record PlacementSettings(
        @DefaultValue("5") int keepReleases,
        @DefaultValue("2") int keepBuildWorkspaces,
        @DefaultValue("64MB") DataSize containerLogMaxBytes,
        @DefaultValue("3") int containerLogMaxFiles,
        @DefaultValue("24h") Duration orphanUploadTtl,
        @DefaultValue("upload-staging") String uploadStagingRootId,
        @DefaultValue("30s") Duration stopGrace,
        @DefaultValue("65536") long nofileLimit,
        @DefaultValue("2000") long databaseEngineMillicores,
        @DefaultValue("2GB") DataSize databaseEngineMemory,
        @DefaultValue("200") int databaseEngineMaxConnections,
        @DefaultValue("10s") Duration healthCheckTimeout,
        @DefaultValue("3") int healthCheckRetries,
        @DefaultValue("20s") Duration healthCheckStartPeriod,
        @DefaultValue("wisper-tenant-") String tenantNetworkPrefix) {

    public PlacementSettings {
        requirePositive("keep-releases", keepReleases);
        requireNotNegative("keep-build-workspaces", keepBuildWorkspaces);
        requirePositive("container-log-max-files", containerLogMaxFiles);
        requirePositive("nofile-limit", nofileLimit);
        requirePositive("database-engine-millicores", databaseEngineMillicores);
        requirePositive("database-engine-max-connections", databaseEngineMaxConnections);
        requirePositive("health-check-retries", healthCheckRetries);
        if (uploadStagingRootId.isBlank()) {
            throw new IllegalArgumentException("wisper.placement.upload-staging-root-id names the "
                    + "node's staging directory in every spec and must match "
                    + "wisper.deploy.staging-root-id; it cannot be blank");
        }
        if (tenantNetworkPrefix.isBlank()) {
            throw new IllegalArgumentException("wisper.placement.tenant-network-prefix is what "
                    + "keeps one tenant's containers off another's network and cannot be blank");
        }
    }

    /** The Docker network for one organization's workloads on any node. */
    public String tenantNetworkFor(Object organizationId) {
        return tenantNetworkPrefix + organizationId;
    }

    private static void requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "wisper.placement." + key + " must be greater than zero, not " + value);
        }
    }

    private static void requireNotNegative(String key, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "wisper.placement." + key + " cannot be negative, and was " + value);
        }
    }
}
