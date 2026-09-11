-- A named bundle of limits an organization is placed on.
--
-- There is no price column. Billing is deliberately out of scope (design §12), and a
-- column nothing charges against is a door into an empty room.

CREATE TABLE plan (
    id          uuid        PRIMARY KEY,
    -- Stable machine name used in URLs and in the seed data: 'free', 'standard', 'pro'.
    code        text        NOT NULL,
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    -- The plan a new organization gets when nobody picks one. At most one row may have
    -- it set; see plan_single_default_idx.
    is_default  boolean     NOT NULL DEFAULT false,
    -- Archived plans keep working for the organizations already on them and disappear
    -- from the picker. Plans are never deleted while an organization references one.
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT plan_code_shape CHECK (code ~ '^[a-z0-9][a-z0-9-]*$'),
    CONSTRAINT plan_default_not_archived CHECK (NOT is_default OR archived_at IS NULL)
);

CREATE UNIQUE INDEX plan_code_key ON plan (code);

-- Exactly one default at a time. A partial unique index over a constant column is the
-- cheapest way to say "at most one row where this is true".
CREATE UNIQUE INDEX plan_single_default_idx ON plan (is_default) WHERE is_default;

CREATE INDEX plan_selectable_idx ON plan (code) WHERE archived_at IS NULL;
