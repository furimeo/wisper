package lhqm.furimeo.wisper.deploy;

import java.util.List;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.BuildPlan;
import lhqm.furimeo.wisper.service.BuildPreset;
import lhqm.furimeo.wisper.service.Service;

/**
 * Turns a preset into a concrete image and concrete commands.
 *
 * <p>The panel resolves this, not the node, and build.proto says why: two nodes running
 * different versions of the daemon must not disagree about what "the Node preset" means,
 * and a customer has to be able to see and override every step. The node receives an
 * image name and two argument vectors and executes exactly those.
 *
 * <p>Commands are argv, never shell strings, because the node runs {@code exec.Command}
 * with an argument slice (AGENTS.md §5). The one exception is the customer's own
 * {@code service.build_command}, which the migration marks as a shell string on purpose:
 * it runs <em>inside</em> the ephemeral build container and needs pipes and {@code &&} to
 * be useful. It travels as a single argument to {@code sh -lc}, so it is still one argv
 * and nothing is concatenated into another command.
 *
 * <p>The images are overridable per preset with {@code wisper.deploy.builder-images}, so
 * an operator moving to a newer toolchain sets one key instead of waiting for a release.
 */
@Component
public class ResolveBuildPlan {

    /** Nothing to build, so the image only has to be able to hold a checkout. */
    private static final String STATIC_IMAGE = "alpine:3.21";

    /** Covers Vite, Astro, Next export and everything else with a package.json. */
    private static final String NODE_IMAGE = "node:22-alpine";

    /** The extended build, which is what themes with SCSS need. */
    private static final String HUGO_IMAGE = "hugomods/hugo:exts";

    private static final String JEKYLL_IMAGE = "ruby:3.3-alpine";

    /**
     * {@code npm ci} needs a lock file and refuses without one. Falling back to
     * {@code npm install} means a repository committed without a lock file still builds
     * instead of failing on a line the customer did not write.
     */
    private static final List<String> NPM_INSTALL = List.of("sh", "-lc",
            "npm ci --no-audit --no-fund || npm install --no-audit --no-fund");

    private static final List<String> NPM_BUILD = List.of("npm", "run", "build");

    private final DeploySettings settings;

    public ResolveBuildPlan(DeploySettings settings) {
        this.settings = settings;
    }

    /**
     * The recipe for building this site.
     *
     * @throws RequestRejected if the service asks for a custom build and has not said what
     *         the command is. {@link BuildArtifact} catches it and fails the deployment
     *         with this sentence in its log, rather than sending a node a plan with no
     *         command in it and letting the failure happen where nobody is looking
     */
    public BuildPlan forService(Service service) {
        BuildPreset preset = service.buildPreset();
        if (preset == null) {
            throw new RequestRejected("buildPreset", "This site has no build preset, so there is "
                    + "nothing to run. Choose one in the service's settings.");
        }
        String custom = service.buildCommand();
        boolean hasCustom = custom != null && !custom.isBlank();
        if (preset.needsCommand() && !hasCustom) {
            throw new RequestRejected("buildCommand", "A custom build needs a command. Write the "
                    + "one you would type in the project's directory.");
        }

        BuildPlan.Builder plan = BuildPlan.newBuilder()
                .setPreset(wireValue(preset))
                .setBuilderImage(imageFor(preset))
                .setOutputDirectory(outputDirectory(service, preset));

        switch (preset) {
            case STATIC -> {
                // The repository is already the site. A command is still honoured if the
                // customer wrote one: "no preset build" is not "no build allowed".
                if (hasCustom) {
                    plan.addAllBuildCommand(shell(custom));
                }
            }
            case NODE, ASTRO -> {
                plan.addAllInstallCommand(NPM_INSTALL);
                plan.addAllBuildCommand(hasCustom ? shell(custom) : NPM_BUILD);
            }
            case HUGO -> plan.addAllBuildCommand(
                    hasCustom ? shell(custom) : List.of("hugo", "--minify"));
            case JEKYLL -> {
                plan.addAllInstallCommand(List.of("sh", "-lc",
                        "bundle config set --local path vendor/bundle && bundle install"));
                plan.addAllBuildCommand(
                        hasCustom ? shell(custom) : List.of("bundle", "exec", "jekyll", "build"));
            }
            case CUSTOM -> plan.addAllBuildCommand(shell(custom));
        }
        return plan.build();
    }

    /** The image the build runs inside: the operator's override, or the built-in one. */
    private String imageFor(BuildPreset preset) {
        String override = settings.builderImageFor(preset.name());
        if (override != null && !override.isBlank()) {
            return override;
        }
        return switch (preset) {
            case STATIC -> STATIC_IMAGE;
            case NODE, ASTRO, CUSTOM -> NODE_IMAGE;
            case HUGO -> HUGO_IMAGE;
            case JEKYLL -> JEKYLL_IMAGE;
        };
    }

    /**
     * The preset as {@code build.proto} spells it.
     *
     * <p>{@code JEKYLL} and {@code CUSTOM} have no value in that enum, so they go over as
     * {@code BUILD_PRESET_UNSPECIFIED}. That is not a hole in the build: the node works
     * from {@code builder_image}, {@code install_command} and {@code build_command}, all
     * of which are fully resolved above for every preset, and the enum is what a log line
     * says rather than what a decision is made from. It is still a gap between the schema
     * and the contract, and it is reported as one.
     */
    private static lhqm.furimeo.wisper.proto.v1.BuildPreset wireValue(BuildPreset preset) {
        return switch (preset) {
            case STATIC -> lhqm.furimeo.wisper.proto.v1.BuildPreset.BUILD_PRESET_STATIC;
            case NODE -> lhqm.furimeo.wisper.proto.v1.BuildPreset.BUILD_PRESET_NODE;
            case HUGO -> lhqm.furimeo.wisper.proto.v1.BuildPreset.BUILD_PRESET_HUGO;
            case ASTRO -> lhqm.furimeo.wisper.proto.v1.BuildPreset.BUILD_PRESET_ASTRO;
            case JEKYLL, CUSTOM ->
                    lhqm.furimeo.wisper.proto.v1.BuildPreset.BUILD_PRESET_UNSPECIFIED;
        };
    }

    private static String outputDirectory(Service service, BuildPreset preset) {
        String configured = service.buildOutputDir();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        // service_site_needs_preset makes this unreachable for a site, which is the only
        // kind that gets here. Falling back to the preset's own default rather than
        // sending an empty string keeps a hand-inserted row from publishing the whole
        // checkout, node_modules included.
        return preset.defaultOutputDir();
    }

    private static List<String> shell(String command) {
        return List.of("sh", "-lc", command);
    }
}
