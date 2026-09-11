-- The tenant. Everything a customer owns hangs off exactly one of these.

CREATE TABLE organization (
    id                uuid        PRIMARY KEY,
    name              text        NOT NULL,
    -- Appears in URLs, so it is lower-case and globally unique.
    slug              text        NOT NULL,
    -- RESTRICT: a plan in use cannot be deleted out from under the organizations on it.
    -- Archive it instead (plan.archived_at).
    plan_id           uuid        NOT NULL REFERENCES plan (id) ON DELETE RESTRICT,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    -- A suspended organization keeps its workloads running until an operator stops
    -- them; suspension blocks writes, it does not silently take a customer's site down.
    suspended_at      timestamptz,
    suspension_reason text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,

    CONSTRAINT organization_slug_shape CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT organization_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT organization_suspension_consistent
        CHECK ((status = 'SUSPENDED') = (suspended_at IS NOT NULL))
);

CREATE UNIQUE INDEX organization_slug_key ON organization (slug);

-- The admin screen that lists who is on which plan.
CREATE INDEX organization_plan_idx ON organization (plan_id);
