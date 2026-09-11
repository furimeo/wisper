-- A grouping of services and databases inside an organization. The unit a customer
-- thinks in, and the unit the dashboard lists.

CREATE TABLE project (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    slug            text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    -- Archived projects keep their services and their data; they drop out of the
    -- dashboard and refuse new deployments. Deleting is a separate, confirmed action.
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT project_slug_shape CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$')
);

CREATE UNIQUE INDEX project_organization_slug_key ON project (organization_id, slug);

-- The dashboard query.
CREATE INDEX project_active_by_organization_idx
    ON project (organization_id, name)
    WHERE archived_at IS NULL;
