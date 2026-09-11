-- A PostgreSQL or MySQL server running on a node, inside which customers get databases.
--
-- Shared by default, one instance per engine per node (design §8.1). The reason is
-- arithmetic: a few hundred private PostgreSQL containers cost 30-50 MB of idle memory
-- each, which no node survives. A customer who pays for isolation gets a DEDICATED
-- instance, which is a row in this same table owned by their organization.
--
-- Admin-facing, under /admin/databases. Customers never see a row from this table; they
-- see the connection string built from it.

CREATE TABLE database_engine (
    id                   uuid        PRIMARY KEY,
    node_id              uuid        NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    engine               text        NOT NULL,
    engine_version       text        NOT NULL,
    mode                 text        NOT NULL DEFAULT 'SHARED',
    -- Set only for DEDICATED instances: the organization that paid for it.
    organization_id      uuid        REFERENCES organization (id) ON DELETE CASCADE,

    image                text        NOT NULL,
    -- Reachable name and port on the node's internal network. Not exposed publicly;
    -- customers connect from their own containers, or through the panel's console.
    host                 text        NOT NULL,
    port                 int         NOT NULL,
    admin_username       text        NOT NULL,
    -- Encrypted at rest, envelope format (see V21). The panel needs the plaintext to
    -- create customer databases, so it cannot be a hash.
    admin_password       text        NOT NULL,
    -- Where the engine keeps its data on the node, decided once and never moved.
    data_path            text        NOT NULL,

    -- Panel intent.
    desired_state        text        NOT NULL DEFAULT 'RUNNING',
    -- Node-reported fact.
    reported_state       text,
    reported_at          timestamptz,
    disk_bytes_used      bigint,
    last_error           text,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,

    CONSTRAINT database_engine_engine_known CHECK (engine IN ('POSTGRES', 'MYSQL')),
    CONSTRAINT database_engine_mode_known CHECK (mode IN ('SHARED', 'DEDICATED')),
    CONSTRAINT database_engine_dedicated_has_owner
        CHECK ((mode = 'DEDICATED') = (organization_id IS NOT NULL)),
    CONSTRAINT database_engine_port_range CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT database_engine_desired_state_known
        CHECK (desired_state IN ('RUNNING', 'STOPPED')),
    CONSTRAINT database_engine_reported_state_known
        CHECK (reported_state IS NULL
               OR reported_state IN ('PENDING', 'RUNNING', 'STOPPED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT database_engine_admin_password_is_envelope
        CHECK (admin_password ~ '^v[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$')
);

-- One shared instance per engine per node. Partial, because a node may also carry any
-- number of dedicated instances of the same engine.
CREATE UNIQUE INDEX database_engine_shared_per_node_key
    ON database_engine (node_id, engine)
    WHERE mode = 'SHARED';

-- Nothing else may bind the same port on the same node.
CREATE UNIQUE INDEX database_engine_node_port_key ON database_engine (node_id, port);

-- The placement question: which engines can take a new customer database.
CREATE INDEX database_engine_placeable_idx
    ON database_engine (engine, node_id)
    WHERE mode = 'SHARED' AND desired_state = 'RUNNING';

CREATE INDEX database_engine_by_organization_idx
    ON database_engine (organization_id)
    WHERE organization_id IS NOT NULL;
