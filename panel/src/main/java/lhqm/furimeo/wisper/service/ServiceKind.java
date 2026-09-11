package lhqm.furimeo.wisper.service;

/**
 * What a service <em>is</em>, and the one distinction the rest of this package keeps
 * branching on. Mirrors the {@code service_kind_known} CHECK.
 *
 * <p>This is not a display flag. The two kinds differ in what the database will accept,
 * in what the node is asked to do and in which screens make sense, and the migration
 * enforces the first of those with three CHECK constraints. Every divergence in this
 * package traces back to one sentence: <strong>a site has no process.</strong>
 */
public enum ServiceKind {

    /**
     * A container the platform runs: a web server, a worker, a bot, anything with a pid.
     *
     * <p>Needs an {@code image}. May take a container port, a health check, a command,
     * volumes and cron entries, because all five presuppose something running.
     */
    APP,

    /**
     * A directory of files built from a repository and served by the node's embedded
     * Caddy.
     *
     * <p>No image and no container port - the migration refuses both - because there is
     * nothing to start. Publishing is a symlink swap and rolling back is the same swap in
     * reverse, so an idle site costs nothing (design §5.5). It needs a
     * {@code build_preset} and a {@code build_output_dir}, and it cannot have volumes or
     * cron entries: both need a container to be inside.
     */
    SITE;

    /** Whether this kind runs a container the node has to keep alive. */
    public boolean runsAContainer() {
        return this == APP;
    }

    /** The word to put in front of a customer. */
    public String label() {
        return this == APP ? "App" : "Static site";
    }
}
