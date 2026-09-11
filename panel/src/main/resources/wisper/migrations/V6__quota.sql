-- The limits a plan grants, one row per resource.
--
-- Rows rather than columns because the set of metered resources grows, and adding a
-- resource must not be an ALTER TABLE on a table other packages already read. The
-- enforcement rule is deliberately strict: a resource with no row on the plan is
-- limited to zero. EnforceQuota must never read a missing row as "unlimited" - that
-- turns forgetting to seed a plan into an unmetered platform.
--
-- Every limit is a long in the smallest unit: a count, bytes, or millicores. Never a
-- double, never a percentage.

CREATE TABLE quota (
    id          uuid        PRIMARY KEY,
    plan_id     uuid        NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    resource    text        NOT NULL,
    limit_value bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT quota_resource_known CHECK (resource IN (
        'PROJECT',
        'SERVICE',
        'DOMAIN',
        'MANAGED_DATABASE',
        'CRON_TASK',
        'MEMBER',
        'API_TOKEN',
        'VOLUME_BYTES',
        'MEMORY_BYTES',
        'CPU_MILLICORES',
        'BACKUP_BYTES',
        'RESTORE_POINT',
        'DEPLOYMENTS_PER_DAY')),
    CONSTRAINT quota_limit_not_negative CHECK (limit_value >= 0)
);

CREATE UNIQUE INDEX quota_plan_resource_key ON quota (plan_id, resource);
