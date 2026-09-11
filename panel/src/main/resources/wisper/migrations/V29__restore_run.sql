-- One attempt to restore from a restore_point.
--
-- This table exists because of the rule in design §8.3: a backup nobody has restored is
-- not a backup. Restoring has to be a button, and a button that starts an asynchronous
-- job needs somewhere to report progress, or the screen behind it is the blank frame
-- this project was rebuilt to avoid.
--
-- mode = VERIFY is the test path: restore into a scratch location on the node, check the
-- result, throw it away, and record that the snapshot was provably restorable. IN_PLACE
-- overwrites the live target, and always takes a PRE_RESTORE snapshot first.

CREATE TABLE restore_run (
    id                          uuid        PRIMARY KEY,
    restore_point_id            uuid        NOT NULL REFERENCES restore_point (id) ON DELETE CASCADE,
    requested_by_account_id     uuid        REFERENCES account (id) ON DELETE SET NULL,
    node_id                     uuid        REFERENCES node (id) ON DELETE SET NULL,

    -- Where it was restored to. Usually the original target, but a restore into a
    -- different volume or database is how a customer clones an environment.
    target_volume_id            uuid        REFERENCES volume (id) ON DELETE SET NULL,
    target_managed_database_id  uuid        REFERENCES managed_database (id) ON DELETE SET NULL,
    -- The PRE_RESTORE snapshot taken before overwriting, so an in-place restore of the
    -- wrong snapshot is itself undoable.
    safety_restore_point_id     uuid        REFERENCES restore_point (id) ON DELETE SET NULL,

    mode                        text        NOT NULL,
    state                       text        NOT NULL DEFAULT 'QUEUED',
    started_at                  timestamptz,
    finished_at                 timestamptz,
    bytes_restored              bigint,
    error_message               text,
    -- Progress output, appended as the node reports it. Small: a restore emits tens of
    -- lines, not the thousands a build does, so this is one column and not a child table.
    log                         text        NOT NULL DEFAULT '',

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    version                     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT restore_run_mode_known CHECK (mode IN ('IN_PLACE', 'VERIFY')),
    CONSTRAINT restore_run_state_known
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT restore_run_finished_has_start
        CHECK (finished_at IS NULL OR started_at IS NOT NULL),
    CONSTRAINT restore_run_bytes_not_negative
        CHECK (bytes_restored IS NULL OR bytes_restored >= 0)
);

-- "Has this snapshot ever been restored, and did it work" - the answer the verify badge
-- on the backup list is built from.
CREATE INDEX restore_run_by_restore_point_idx
    ON restore_run (restore_point_id, created_at DESC);

-- The in-flight list, and the sweep that fails runs whose node went away.
CREATE INDEX restore_run_active_idx
    ON restore_run (created_at)
    WHERE state IN ('QUEUED', 'RUNNING');

-- At most one in-flight restore into a given volume or database. Two concurrent restores
-- into the same target interleave their writes and produce something that was never a
-- valid snapshot of anything.
CREATE UNIQUE INDEX restore_run_one_active_per_volume_idx
    ON restore_run (target_volume_id)
    WHERE target_volume_id IS NOT NULL AND state IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX restore_run_one_active_per_database_idx
    ON restore_run (target_managed_database_id)
    WHERE target_managed_database_id IS NOT NULL AND state IN ('QUEUED', 'RUNNING');
