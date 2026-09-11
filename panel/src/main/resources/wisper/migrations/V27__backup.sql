-- A backup policy: what to snapshot, how often, where to push it, how long to keep it.
--
-- This is the rule, not the artefact. The artefacts are restore_point rows, and they
-- deliberately outlive this table: deleting a policy must never delete the snapshots
-- taken under it.
--
-- The target is a volume or a managed database, never both. The two foreign keys CASCADE
-- rather than SET NULL because a policy for a target that no longer exists has nothing
-- to run against - and because ON DELETE SET NULL would have to satisfy the exactly-one
-- check below, which it cannot, so deleting a volume would fail instead.

CREATE TABLE backup (
    id                   uuid        PRIMARY KEY,
    organization_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    -- RESTRICT: a destination with policies pointing at it is in use.
    destination_id       uuid        NOT NULL REFERENCES backup_destination (id) ON DELETE RESTRICT,

    name                 text        NOT NULL,
    target_kind          text        NOT NULL,
    volume_id            uuid        REFERENCES volume (id) ON DELETE CASCADE,
    managed_database_id  uuid        REFERENCES managed_database (id) ON DELETE CASCADE,

    -- Five-field cron expression. Null means the policy exists but only runs when
    -- somebody presses the button.
    schedule             text,
    timezone             text        NOT NULL DEFAULT 'UTC',
    enabled              boolean     NOT NULL DEFAULT true,

    -- Retention is both, and whichever bites first wins: keep at least this many, and
    -- keep nothing older than this many days.
    retention_count      int         NOT NULL DEFAULT 7,
    retention_days       int         NOT NULL DEFAULT 30,

    last_run_at          timestamptz,
    last_status          text,
    last_error           text,
    next_run_at          timestamptz,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,

    CONSTRAINT backup_target_kind_known CHECK (target_kind IN ('VOLUME', 'DATABASE')),
    CONSTRAINT backup_target_exactly_one CHECK (
        (target_kind = 'VOLUME' AND volume_id IS NOT NULL AND managed_database_id IS NULL)
        OR
        (target_kind = 'DATABASE' AND managed_database_id IS NOT NULL AND volume_id IS NULL)),
    CONSTRAINT backup_retention_positive CHECK (retention_count > 0 AND retention_days > 0),
    CONSTRAINT backup_last_status_known
        CHECK (last_status IS NULL OR last_status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT backup_schedule_shape
        CHECK (schedule IS NULL OR btrim(schedule) ~ '^\S+\s+\S+\s+\S+\s+\S+\s+\S+$')
);

CREATE UNIQUE INDEX backup_organization_name_key ON backup (organization_id, name);

-- The scheduler sweep: which policies are due.
CREATE INDEX backup_due_idx
    ON backup (next_run_at)
    WHERE enabled AND schedule IS NOT NULL;

CREATE INDEX backup_by_volume_idx ON backup (volume_id) WHERE volume_id IS NOT NULL;
CREATE INDEX backup_by_database_idx
    ON backup (managed_database_id)
    WHERE managed_database_id IS NOT NULL;
CREATE INDEX backup_by_destination_idx ON backup (destination_id);
