-- An account's membership of an organization, and what it may do there.
--
-- This is the authorization edge. Every ownership check in the panel walks
-- account -> member -> organization -> project -> service; there is no denormalised
-- organization_id on the leaves, because two places to read ownership from is one place
-- to get it wrong.

CREATE TABLE member (
    id                    uuid        PRIMARY KEY,
    organization_id       uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    account_id            uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    -- OWNER     - everything, including deleting the organization and changing owners
    -- ADMIN     - everything except deleting the organization
    -- DEVELOPER - deploy, terminal, files, databases; no member or billing-shaped change
    -- VIEWER    - read only, including logs and metrics; no terminal, no files
    role                  text        NOT NULL,
    invited_by_account_id uuid        REFERENCES account (id) ON DELETE SET NULL,
    invited_at            timestamptz,
    -- Null while an invitation is outstanding. An unaccepted member grants nothing.
    accepted_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT member_role_known CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE UNIQUE INDEX member_organization_account_key ON member (organization_id, account_id);

-- "Which organizations am I in", read on nearly every request to build the switcher.
CREATE INDEX member_by_account_idx ON member (account_id) WHERE accepted_at IS NOT NULL;

-- The guard against removing the last owner.
CREATE INDEX member_owners_idx ON member (organization_id) WHERE role = 'OWNER';
