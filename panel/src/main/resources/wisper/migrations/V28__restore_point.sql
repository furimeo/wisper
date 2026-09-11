-- One snapshot that exists somewhere and can be restored from.
--
-- Built to outlive everything that produced it. The policy may be deleted, the volume
-- may be deleted, the node may be retired - and the row stays, because the moment a
-- customer needs a restore is usually the moment after something was deleted. That is
-- why the target references are ON DELETE SET NULL and why target_label carries a
-- human-readable copy of what was backed up.
--
-- The destination is RESTRICT: a bucket that still holds restorable snapshots is not a
-- row an operator gets to remove by accident.

CREATE TABLE restore_point (
    id                   uuid        PRIMARY KEY,
    organization_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    backup_id            uuid        REFERENCES backup (id) ON DELETE SET NULL,
    destination_id       uuid        NOT NULL REFERENCES backup_destination (id) ON DELETE RESTRICT,
    node_id              uuid        REFERENCES node (id) ON DELETE SET NULL,

    target_kind          text        NOT NULL,
    volume_id            uuid        REFERENCES volume (id) ON DELETE SET NULL,
    managed_database_id  uuid        REFERENCES managed_database (id) ON DELETE SET NULL,
    -- "project-a / api / data" at the time the snapshot was taken. Never recomputed.
    target_label         text        NOT NULL,

    state                text        NOT NULL DEFAULT 'RUNNING',
    trigger              text        NOT NULL,

    -- Key inside the destination, relative to its path_prefix.
    object_key           text,
    size_bytes           bigint,
    compressed           boolean     NOT NULL DEFAULT true,
    encrypted            boolean     NOT NULL DEFAULT true,
    checksum_sha256      text,

    started_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz,
    -- When retention says this may be deleted. Null means keep until somebody says
    -- otherwise.
    expires_at           timestamptz,
    error_message        text,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,

    CONSTRAINT restore_point_target_kind_known CHECK (target_kind IN ('VOLUME', 'DATABASE')),
    CONSTRAINT restore_point_state_known
        CHECK (state IN ('RUNNING', 'AVAILABLE', 'FAILED', 'EXPIRED', 'DELETED')),
    CONSTRAINT restore_point_trigger_known
        CHECK (trigger IN ('SCHEDULED', 'MANUAL', 'PRE_RESTORE')),
    -- An available snapshot has to say where it is and how big it is, or nothing can
    -- restore from it and the retention sweep cannot account for it.
    CONSTRAINT restore_point_available_is_locatable
        CHECK (state <> 'AVAILABLE'
               OR (object_key IS NOT NULL AND size_bytes IS NOT NULL AND finished_at IS NOT NULL)),
    CONSTRAINT restore_point_size_not_negative CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

-- The backup list a customer sees, newest first.
CREATE INDEX restore_point_by_organization_idx
    ON restore_point (organization_id, started_at DESC);

-- "Show me the snapshots this policy produced", and the retention count within a policy.
CREATE INDEX restore_point_by_backup_idx
    ON restore_point (backup_id, started_at DESC)
    WHERE backup_id IS NOT NULL;

-- The retention sweep.
CREATE INDEX restore_point_expiring_idx
    ON restore_point (expires_at)
    WHERE state = 'AVAILABLE' AND expires_at IS NOT NULL;

-- "What can I restore this volume/database from", the two restore buttons.
CREATE INDEX restore_point_by_volume_idx
    ON restore_point (volume_id, started_at DESC)
    WHERE volume_id IS NOT NULL;
CREATE INDEX restore_point_by_database_idx
    ON restore_point (managed_database_id, started_at DESC)
    WHERE managed_database_id IS NOT NULL;

-- The quota rollup for BACKUP_BYTES.
CREATE INDEX restore_point_size_idx
    ON restore_point (organization_id, size_bytes)
    WHERE state = 'AVAILABLE';
