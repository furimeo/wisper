-- The deployable unit a customer owns. `kind` is APP (a container the platform runs) or
-- SITE (static files built from a repository and served by Caddy with no process at all,
-- design §5.5).
--
-- Every column here is panel intent. Nothing sasayaki reports is written back into this
-- table; runtime fact for the running copy lives on `placement`, because the fact is
-- about a workload on a node and dies with that binding.

CREATE TABLE service (
    id                    uuid        PRIMARY KEY,
    project_id            uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name                  text        NOT NULL,
    slug                  text        NOT NULL,
    kind                  text        NOT NULL,

    -- What the customer has asked for. The node converges to it; it is never written
    -- from a status report, so "stopped by the customer" and "crashed" stay different
    -- facts.
    desired_state         text        NOT NULL DEFAULT 'STOPPED',

    -- gVisor by default. runc is the escape hatch for workloads runsc cannot run
    -- (io_uring, some old binaries - design §11.6); choosing it requires a reason,
    -- which the panel displays as a warning next to the service.
    runtime_isolation     text        NOT NULL DEFAULT 'RUNSC',
    isolation_reason      text,

    -- APP only ---------------------------------------------------------------------
    image                 text,
    -- Resolved at deploy time and pinned, so a moved tag cannot change what restarts.
    image_digest          text,
    -- argv, never a shell string: the node execs an argument slice (AGENTS.md §5).
    command               text[],
    entrypoint            text[],
    working_dir           text,
    container_port        int,
    health_check_path     text,
    health_check_interval_seconds int  NOT NULL DEFAULT 30,
    restart_policy        text        NOT NULL DEFAULT 'ALWAYS',

    -- SITE only --------------------------------------------------------------------
    build_preset          text,
    -- Runs inside the ephemeral build container, so a shell string is correct here and
    -- only here. It never reaches ProcessBuilder or exec.Command on the panel or node.
    build_command         text,
    build_output_dir      text,
    -- How many releases stay on disk for instant rollback (design §5.5).
    keep_releases         int         NOT NULL DEFAULT 5,

    -- Git source, used by both kinds. Null means deploys arrive as uploaded archives.
    repository_url        text,
    repository_branch     text,
    -- Deploy key or access token for a private repository, encrypted at rest.
    repository_credential text,
    auto_deploy           boolean     NOT NULL DEFAULT true,
    -- HMAC key for /webhooks/**, encrypted at rest. The panel needs the plaintext to
    -- verify a signature, so this is encrypted rather than hashed.
    webhook_secret        text        NOT NULL,

    -- Resource intent. Longs in the smallest unit; the node turns millicores into
    -- NanoCPUs, which is a rate and not CPUShares * 1000.
    cpu_millicores        bigint      NOT NULL DEFAULT 500,
    memory_bytes          bigint      NOT NULL DEFAULT 268435456,
    disk_bytes            bigint      NOT NULL DEFAULT 1073741824,
    pids_limit            int         NOT NULL DEFAULT 256,

    -- Placement filters this service needs; matched against node.tags.
    required_tags         text[]      NOT NULL DEFAULT '{}',

    archived_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT service_slug_shape CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT service_kind_known CHECK (kind IN ('APP', 'SITE')),
    CONSTRAINT service_desired_state_known CHECK (desired_state IN ('RUNNING', 'STOPPED')),
    CONSTRAINT service_runtime_isolation_known CHECK (runtime_isolation IN ('RUNSC', 'RUNC')),
    CONSTRAINT service_runc_needs_reason
        CHECK (runtime_isolation <> 'RUNC' OR length(btrim(coalesce(isolation_reason, ''))) > 0),
    CONSTRAINT service_restart_policy_known
        CHECK (restart_policy IN ('ALWAYS', 'ON_FAILURE', 'NEVER')),
    CONSTRAINT service_build_preset_known
        CHECK (build_preset IS NULL
               OR build_preset IN ('STATIC', 'NODE', 'HUGO', 'ASTRO', 'JEKYLL', 'CUSTOM')),
    CONSTRAINT service_container_port_range
        CHECK (container_port IS NULL OR container_port BETWEEN 1 AND 65535),
    CONSTRAINT service_limits_positive
        CHECK (cpu_millicores > 0 AND memory_bytes > 0 AND disk_bytes > 0 AND pids_limit > 0),
    CONSTRAINT service_keep_releases_positive CHECK (keep_releases > 0),
    CONSTRAINT service_health_interval_positive CHECK (health_check_interval_seconds > 0),
    -- An APP with no image has nothing to run; the deploy job would fail at the node
    -- instead of at the form that created it.
    CONSTRAINT service_app_needs_image CHECK (kind <> 'APP' OR image IS NOT NULL),
    -- A SITE with no preset has no build to run and no output directory to publish.
    CONSTRAINT service_site_needs_preset
        CHECK (kind <> 'SITE' OR (build_preset IS NOT NULL AND build_output_dir IS NOT NULL)),
    -- A SITE is served by Caddy from disk. It has no container, so it has no port.
    CONSTRAINT service_site_has_no_container
        CHECK (kind <> 'SITE' OR (image IS NULL AND container_port IS NULL))
);

CREATE UNIQUE INDEX service_project_slug_key ON service (project_id, slug);

CREATE INDEX service_by_project_idx
    ON service (project_id, name)
    WHERE archived_at IS NULL;

-- The placement scheduler's work list: services that should be running but have no
-- active placement yet.
CREATE INDEX service_wanted_running_idx
    ON service (id)
    WHERE desired_state = 'RUNNING' AND archived_at IS NULL;

CREATE INDEX service_required_tags_idx ON service USING gin (required_tags);
