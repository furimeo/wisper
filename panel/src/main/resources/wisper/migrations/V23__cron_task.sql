-- A command a customer wants run on a schedule inside their service.
--
-- Named cron_task and not scheduled_task on purpose. `scheduled_tasks` is db-scheduler's
-- table (V1) and belongs to the panel's own job queue; two tables whose names differ by
-- one letter, one holding customer intent and one holding internal jobs, is a query
-- somebody eventually writes against the wrong one at two in the morning. The design
-- doc's sketch calls this scheduled_task; the rename is recorded in
-- docs/contracts/schema.md.
--
-- The schedule is evaluated on the node, not by db-scheduler: a cron that must run
-- inside the customer's container while the panel is unreachable is exactly the kind of
-- thing the panel must not be in the path of (design §5.1).

CREATE TABLE cron_task (
    id                  uuid        PRIMARY KEY,
    service_id          uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    name                text        NOT NULL,
    -- Five-field cron expression, evaluated in `timezone`.
    schedule            text        NOT NULL,
    timezone            text        NOT NULL DEFAULT 'UTC',
    -- argv. Never a shell string: the node execs an argument slice.
    command             text[]      NOT NULL,
    enabled             boolean     NOT NULL DEFAULT true,
    timeout_seconds     int         NOT NULL DEFAULT 300,
    -- What to do when the previous run has not finished.
    concurrency_policy  text        NOT NULL DEFAULT 'FORBID',

    -- Node-reported fact about the last execution.
    last_run_at         timestamptz,
    last_finished_at    timestamptz,
    last_exit_code      int,
    last_duration_ms    bigint,
    last_error          text,
    -- Computed by the panel from `schedule` for display only. The node does its own
    -- scheduling and does not read this.
    next_run_at         timestamptz,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,

    CONSTRAINT cron_task_name_shape CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT cron_task_command_not_empty CHECK (cardinality(command) > 0),
    CONSTRAINT cron_task_timeout_positive CHECK (timeout_seconds > 0),
    CONSTRAINT cron_task_concurrency_policy_known
        CHECK (concurrency_policy IN ('ALLOW', 'FORBID', 'REPLACE')),
    -- Five whitespace-separated fields. Full cron validity is checked in Java, where the
    -- customer gets a message that says which field is wrong; this stops the shape that
    -- would make the node's parser give up.
    CONSTRAINT cron_task_schedule_shape
        CHECK (btrim(schedule) ~ '^\S+\s+\S+\s+\S+\s+\S+\s+\S+$')
);

CREATE UNIQUE INDEX cron_task_service_name_key ON cron_task (service_id, name);

-- The spec builder reads only the enabled ones.
CREATE INDEX cron_task_enabled_by_service_idx ON cron_task (service_id) WHERE enabled;
