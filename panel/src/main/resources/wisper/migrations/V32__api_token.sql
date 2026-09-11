-- A scoped bearer token for /api/v1/**.
--
-- Hashed, never encrypted: the panel only compares. The full token is shown once, at
-- creation. token_prefix is the first characters, kept in the clear so the customer can
-- tell two tokens apart in a list and so a leaked token found in a log can be matched to
-- a row and revoked without knowing the rest of it.

CREATE TABLE api_token (
    id                  uuid        PRIMARY KEY,
    account_id          uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    -- Null means the token acts across every organization the account belongs to. Set
    -- means it is confined to one, which is what a deploy key in CI should be.
    organization_id     uuid        REFERENCES organization (id) ON DELETE CASCADE,
    name                text        NOT NULL,

    token_prefix        text        NOT NULL,
    -- SHA-256 of the whole token.
    token_hash          text        NOT NULL,
    scopes              text[]      NOT NULL DEFAULT '{}',

    expires_at          timestamptz,
    last_used_at        timestamptz,
    last_used_address   text,
    revoked_at          timestamptz,
    revoked_reason      text,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,

    CONSTRAINT api_token_prefix_shape CHECK (token_prefix ~ '^[A-Za-z0-9_-]{6,16}$'),
    CONSTRAINT api_token_scopes_not_empty CHECK (cardinality(scopes) > 0),
    -- A token cannot ask for a permission the panel does not know how to check. The
    -- containment operator applies the enum to every element at once.
    CONSTRAINT api_token_scopes_known CHECK (scopes <@ ARRAY[
        'projects:read', 'projects:write',
        'services:read', 'services:write',
        'deployments:read', 'deployments:write',
        'domains:read', 'domains:write',
        'databases:read', 'databases:write',
        'files:read', 'files:write',
        'backups:read', 'backups:write',
        'metrics:read',
        'nodes:read', 'nodes:write']::text[])
);

-- The lookup on every /api/v1 request.
CREATE UNIQUE INDEX api_token_hash_key ON api_token (token_hash);

CREATE UNIQUE INDEX api_token_account_name_key ON api_token (account_id, name);

-- The /settings token list, live ones first.
CREATE INDEX api_token_live_by_account_idx
    ON api_token (account_id, created_at DESC)
    WHERE revoked_at IS NULL;

-- The sweep that revokes expired tokens so the list shows the truth.
CREATE INDEX api_token_expiry_idx
    ON api_token (expires_at)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL;
