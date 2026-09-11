-- A per-organization exception to its plan's limit.
--
-- Kept apart from `quota` so the plan stays the description of a tier and the override
-- stays a decision somebody made about one customer, with a reason and an author
-- attached. Reading order in EnforceQuota: an unexpired override for the resource wins;
-- otherwise the plan's quota row; otherwise zero.

CREATE TABLE quota_override (
    id                 uuid        PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    resource           text        NOT NULL,
    limit_value        bigint      NOT NULL,
    -- Why this customer is different. Required: an override nobody can explain is one
    -- nobody dares remove.
    reason             text        NOT NULL,
    -- Null means it stands until somebody deletes it. A past value means the plan's
    -- limit applies again, with no sweep needed.
    expires_at         timestamptz,
    granted_by_account_id uuid     REFERENCES account (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT quota_override_resource_known CHECK (resource IN (
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
    CONSTRAINT quota_override_limit_not_negative CHECK (limit_value >= 0),
    CONSTRAINT quota_override_reason_not_blank CHECK (length(btrim(reason)) > 0)
);

CREATE UNIQUE INDEX quota_override_organization_resource_key
    ON quota_override (organization_id, resource);
