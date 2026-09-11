-- One attempt to put a new version of a service in front of customers.
--
-- For a SITE this is a build plus an atomic symlink swap; for an APP it is a pinned
-- image plus a restart. Rollback is a new deployment row whose
-- rolled_back_from_deployment_id points at the one being undone, so the history reads
-- forwards and "which release is live" has exactly one answer.

CREATE TABLE deployment (
    id                     uuid        PRIMARY KEY,
    service_id             uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    -- SET NULL, not CASCADE: a retired node must not take the deployment history with
    -- it. The row stays and says the node is gone.
    node_id                uuid        REFERENCES node (id) ON DELETE SET NULL,

    -- The number the customer sees, counted per service starting at 1. Allocated as
    -- max(sequence) + 1 inside the transaction that inserts the row; the unique index
    -- below is what makes two concurrent deploys resolve instead of colliding silently.
    sequence               bigint      NOT NULL,

    trigger                text        NOT NULL,
    triggered_by_account_id uuid       REFERENCES account (id) ON DELETE SET NULL,
    source                 text        NOT NULL,
    status                 text        NOT NULL DEFAULT 'QUEUED',

    git_ref                text,
    commit_sha             text,
    commit_message         text,
    commit_author          text,
    -- Uploaded archive under wisper.storage.root, for the deploy-a-zip path. Panel-side
    -- and temporary; the bytes that matter end up on the node.
    archive_path           text,
    -- The digest actually deployed, so a rollback restarts the same bytes.
    image_digest           text,
    -- /var/lib/wisper/sites/<service-id>/releases/<deployment-id> on the node. The path
    -- uses ids, not slugs, so renaming a service cannot move a release.
    release_path           text,

    -- Exactly one live deployment per service; see deployment_current_idx.
    is_current             boolean     NOT NULL DEFAULT false,
    rolled_back_from_deployment_id uuid REFERENCES deployment (id) ON DELETE SET NULL,

    queued_at              timestamptz NOT NULL DEFAULT now(),
    assigned_at            timestamptz,
    started_at             timestamptz,
    finished_at            timestamptz,
    duration_ms            bigint,
    error_message          text,

    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT deployment_sequence_positive CHECK (sequence > 0),
    CONSTRAINT deployment_trigger_known
        CHECK (trigger IN ('MANUAL', 'GIT_PUSH', 'ROLLBACK', 'API', 'SCHEDULED')),
    CONSTRAINT deployment_source_known CHECK (source IN ('GIT', 'ARCHIVE', 'IMAGE')),
    CONSTRAINT deployment_status_known CHECK (status IN
        ('QUEUED', 'ASSIGNED', 'BUILDING', 'PUBLISHING', 'SUCCEEDED', 'FAILED',
         'CANCELLED', 'SUPERSEDED')),
    -- Only something that finished successfully can be what is serving traffic.
    CONSTRAINT deployment_current_must_have_succeeded
        CHECK (NOT is_current OR status = 'SUCCEEDED'),
    CONSTRAINT deployment_git_source_needs_ref
        CHECK (source <> 'GIT' OR git_ref IS NOT NULL),
    CONSTRAINT deployment_archive_source_needs_path
        CHECK (source <> 'ARCHIVE' OR archive_path IS NOT NULL)
);

CREATE UNIQUE INDEX deployment_service_sequence_key ON deployment (service_id, sequence);

-- The deployment list, newest first. The access path the design calls for explicitly.
CREATE INDEX deployment_by_service_recent_idx ON deployment (service_id, sequence DESC);

-- "What is live right now", read on every service page.
CREATE UNIQUE INDEX deployment_current_idx ON deployment (service_id) WHERE is_current;

-- The worker's queue view, and the sweep that supersedes deploys overtaken by a newer
-- push while they were still waiting.
CREATE INDEX deployment_pending_idx
    ON deployment (queued_at)
    WHERE status IN ('QUEUED', 'ASSIGNED', 'BUILDING', 'PUBLISHING');

-- The per-day deployment quota, counted over a rolling window.
CREATE INDEX deployment_by_service_time_idx ON deployment (service_id, created_at DESC);
