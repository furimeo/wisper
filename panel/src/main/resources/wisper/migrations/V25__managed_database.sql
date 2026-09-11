-- A database the platform runs for a customer: one database and one user on a shared or
-- dedicated engine, with a quota on how big it may get (design §8.1).
--
-- Named managed_database and not `database`. DATABASE is a keyword in PostgreSQL - it
-- happens to be a non-reserved one, so `CREATE TABLE database` would work today - but
-- the point of the panel's naming rule is that Spring Data JDBC quotes nothing for you,
-- and a table whose name is a keyword is a query away from a syntax error nobody can
-- read. The Java package is still `database`; the record is ManagedDatabase.
--
-- Exactly one user per database, so there is no separate grant table. A customer who
-- needs two credentials creates two databases.

CREATE TABLE managed_database (
    id                     uuid        PRIMARY KEY,
    project_id             uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- RESTRICT: an engine that still holds customer databases must not be deletable.
    -- Move or drop them first, deliberately.
    database_engine_id     uuid        NOT NULL REFERENCES database_engine (id) ON DELETE RESTRICT,

    -- The actual database name on the engine. Prefixed by the panel so two customers on
    -- one engine cannot collide, and constrained to what both engines accept unquoted.
    name                   text        NOT NULL,
    db_username            text        NOT NULL,
    -- Encrypted at rest, envelope format (see V21). The panel shows the connection
    -- string and offers a rotate button, so it must be able to decrypt.
    db_password            text        NOT NULL,
    -- db_ prefixed because COLLATION is a reserved word in PostgreSQL and Spring Data
    -- JDBC quotes nothing for you.
    db_charset             text,
    db_collation           text,

    -- Panel intent.
    quota_bytes            bigint      NOT NULL,
    -- Node-reported fact.
    used_bytes             bigint,
    used_bytes_measured_at timestamptz,

    state                  text        NOT NULL DEFAULT 'PENDING',
    last_error             text,
    provisioned_at         timestamptz,
    password_rotated_at    timestamptz,

    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT managed_database_name_shape CHECK (name ~ '^[a-z][a-z0-9_]{2,62}$'),
    CONSTRAINT managed_database_username_shape CHECK (db_username ~ '^[a-z][a-z0-9_]{2,30}$'),
    CONSTRAINT managed_database_quota_positive CHECK (quota_bytes > 0),
    CONSTRAINT managed_database_used_not_negative CHECK (used_bytes IS NULL OR used_bytes >= 0),
    CONSTRAINT managed_database_state_known CHECK (state IN
        ('PENDING', 'READY', 'SUSPENDED', 'FAILED', 'DELETING')),
    CONSTRAINT managed_database_password_is_envelope
        CHECK (db_password ~ '^v[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$')
);

-- Names and users are unique within the engine they live on, because that is where the
-- collision would actually happen.
CREATE UNIQUE INDEX managed_database_engine_name_key ON managed_database (database_engine_id, name);
CREATE UNIQUE INDEX managed_database_engine_username_key
    ON managed_database (database_engine_id, db_username);

CREATE INDEX managed_database_by_project_idx ON managed_database (project_id);

-- The quota sweep: databases over their limit, which get suspended rather than dropped.
CREATE INDEX managed_database_usage_idx
    ON managed_database (database_engine_id, used_bytes)
    WHERE state = 'READY';
