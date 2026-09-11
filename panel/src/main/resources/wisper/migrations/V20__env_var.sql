-- Plain environment variables for a service. Readable in the panel, shown in full, and
-- sent to the node in the spec as-is.
--
-- Anything that must not be readable goes in `secret` instead. Two tables rather than an
-- `is_secret` flag, because the difference is not a boolean on a row: it changes how the
-- value is stored, whether a GET returns it, and whether it appears in an audit entry.
-- A flag makes all three a runtime `if` somebody eventually forgets.

CREATE TABLE env_var (
    id         uuid        PRIMARY KEY,
    service_id uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    value      text        NOT NULL,
    -- Also exposed to the build container, not only to the running workload. Build-time
    -- variables are how a static site gets its API base URL baked in.
    build_time boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0,

    CONSTRAINT env_var_name_shape CHECK (name ~ '^[A-Za-z_][A-Za-z0-9_]*$'),
    -- WISPER_* is injected by the platform; letting a customer set one would let them
    -- overwrite the values the node relies on.
    CONSTRAINT env_var_name_not_reserved CHECK (name NOT LIKE 'WISPER\_%')
);

CREATE UNIQUE INDEX env_var_service_name_key ON env_var (service_id, name);
