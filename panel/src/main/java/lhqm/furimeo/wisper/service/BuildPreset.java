package lhqm.furimeo.wisper.service;

/**
 * How a static site is built from a repository. Mirrors the
 * {@code service_build_preset_known} CHECK.
 *
 * <p>A preset is a name the node resolves into an ephemeral build container and a default
 * command; the panel stores the name and nothing else. Keeping the image out of the
 * database is what lets a node be upgraded to a newer toolchain without a migration, and
 * it is why {@link #defaultOutputDir()} is a suggestion for the form rather than a value
 * the node reads back.
 *
 * <p>{@link #CUSTOM} is the honest escape: the customer supplies the build command and
 * the output directory, and the platform makes no assumptions about either.
 */
public enum BuildPreset {

    /** Nothing to build. The repository is already the site. */
    STATIC("."),

    /** A Node toolchain: {@code npm ci && npm run build}. Covers Vite, Next export, and most others. */
    NODE("dist"),

    /** Hugo. */
    HUGO("public"),

    /** Astro. */
    ASTRO("dist"),

    /** Jekyll. */
    JEKYLL("_site"),

    /** The customer's own command, in the platform's build image. */
    CUSTOM("");

    private final String defaultOutputDir;

    BuildPreset(String defaultOutputDir) {
        this.defaultOutputDir = defaultOutputDir;
    }

    /**
     * What the form fills the output directory in with, so the common case is one choice
     * instead of two. Empty for {@link #CUSTOM}, which has no common case.
     */
    public String defaultOutputDir() {
        return defaultOutputDir;
    }

    /** Whether the customer has to write the build command themselves. */
    public boolean needsCommand() {
        return this == CUSTOM;
    }

    /** The word in the dropdown. */
    public String label() {
        return switch (this) {
            case STATIC -> "Plain files, no build";
            case NODE -> "Node";
            case HUGO -> "Hugo";
            case ASTRO -> "Astro";
            case JEKYLL -> "Jekyll";
            case CUSTOM -> "Custom command";
        };
    }
}
