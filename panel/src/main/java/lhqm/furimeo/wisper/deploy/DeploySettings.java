package lhqm.furimeo.wisper.deploy;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Everything about deploying that an operator might reasonably want to change.
 *
 * <p>Bound by {@code @ConfigurationPropertiesScan} on {@code WisperApplication}, so
 * nothing central had to be edited to add it (panel-configuration.md). Every component
 * carries a {@code @DefaultValue}: record binding does not fall back to a constructor
 * default, and a missing key would otherwise bind to zero and surface as a build that
 * times out instantly.
 *
 * @param buildTimeout       how long a node gets before a build is called failed and its
 *                           slot reclaimed. Generous, because a cold {@code npm ci} on a
 *                           small node genuinely takes minutes, and finite, because a hung
 *                           build otherwise holds a builder forever
 * @param archiveChunkSize   how much of an uploaded zip travels in one {@code WriteChunk}
 *                           on the way to the node. Smaller than the file-manager chunk
 *                           because the panel is pushing this on a request thread, not a
 *                           phone pushing it over 4G
 * @param maxArchiveSize     the largest zip the deploy-a-zip path accepts. Refused at the
 *                           form, where the customer can be told, rather than after the
 *                           bytes have crossed the network twice
 * @param logReplayLimit     lines sent to a browser when it opens or resumes a log. A
 *                           phone that is handed a hundred thousand lines renders none
 * @param logKeepAlive       how often an SSE comment goes down an idle log stream. A
 *                           tunnel closes a connection that says nothing, and the customer
 *                           sees a build that stopped talking
 * @param stagingRootId      the id of the {@code FILE_ROOT_KIND_UPLOAD_STAGING} root in a
 *                           node's spec. The panel pushes deploy archives into it, so the
 *                           value has to match what {@code placement} puts in the spec
 * @param builderImages      per-preset override of the ephemeral build image, keyed by the
 *                           {@code service.build_preset} value in upper case. Empty uses
 *                           the images in {@link ResolveBuildPlan}; an operator upgrading a
 *                           toolchain sets one key instead of waiting for a release
 */
@ConfigurationProperties("wisper.deploy")
public record DeploySettings(
        @DefaultValue("20m") Duration buildTimeout,
        @DefaultValue("4MB") DataSize archiveChunkSize,
        @DefaultValue("512MB") DataSize maxArchiveSize,
        @DefaultValue("2000") int logReplayLimit,
        @DefaultValue("20s") Duration logKeepAlive,
        @DefaultValue("upload-staging") String stagingRootId,
        Map<String, String> builderImages) {

    public DeploySettings {
        builderImages = builderImages == null ? Map.of() : Map.copyOf(builderImages);
        if (buildTimeout.isZero() || buildTimeout.isNegative()) {
            throw new IllegalArgumentException("wisper.deploy.build-timeout must be positive; "
                    + "zero would fail every build the moment it started");
        }
        if (archiveChunkSize.toBytes() < 64 * 1024) {
            throw new IllegalArgumentException("wisper.deploy.archive-chunk-size of "
                    + archiveChunkSize + " would turn a 200 MB archive into thousands of "
                    + "round trips; 64KB is the smallest that makes sense");
        }
        if (logReplayLimit < 1) {
            throw new IllegalArgumentException("wisper.deploy.log-replay-limit must be at least "
                    + "1, or a deployment page would open showing nothing");
        }
        if (logKeepAlive.isZero() || logKeepAlive.isNegative()) {
            throw new IllegalArgumentException("wisper.deploy.log-keep-alive must be positive; "
                    + "an SSE stream that never sends a comment is closed by the tunnel");
        }
        if (stagingRootId.isBlank()) {
            throw new IllegalArgumentException("wisper.deploy.staging-root-id names the upload "
                    + "root in a node's spec and cannot be blank");
        }
    }

    /** The build timeout as the whole seconds {@code StartBuild} carries. */
    public long buildTimeoutSeconds() {
        return Math.max(1L, buildTimeout.toSeconds());
    }

    /** The overriding image for a preset, or null to use the built-in one. */
    public String builderImageFor(String presetName) {
        return builderImages.get(presetName);
    }
}
