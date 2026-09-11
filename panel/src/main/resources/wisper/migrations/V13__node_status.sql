-- What the node says about itself. Exactly one row per node; the primary key is the
-- foreign key, so the one-to-one cannot drift into a one-to-many.
--
-- Every column here is written by the gRPC handlers on the panel from what sasayaki
-- reports, and by nothing else. No screen, no use-case and no admin action may write a
-- column in this table: it is the node's half of the contract. The panel's half is in
-- `node`.
--
-- Capacity numbers are longs in the smallest unit. cpu is millicores because Docker's
-- NanoCPUs is a rate, not a share, and the unit confusion in the predecessor project
-- started with storing a percentage.

CREATE TABLE node_status (
    node_id                  uuid        PRIMARY KEY REFERENCES node (id) ON DELETE CASCADE,

    -- CONNECTED  - Connect() stream is open and the protocol version matched
    -- DEGRADED   - stream open, but the node reports it cannot do its job (Docker down)
    -- DISCONNECTED - no stream. Workloads keep running; this is not an incident.
    connection_state         text        NOT NULL DEFAULT 'DISCONNECTED',
    last_heartbeat_at        timestamptz,
    last_connected_at        timestamptz,
    last_disconnected_at     timestamptz,
    -- Recorded so a second machine dialling in with the same credential from a
    -- different address can be spotted (design §7.3).
    remote_address           text,

    agent_version            text,
    protocol_version         int,

    -- The generation the node has actually converged to. -1 means it has never told us.
    -- Compared against node.desired_generation; equal is converged, lower is in flight,
    -- higher is impossible and means the databases have been mixed up.
    applied_generation       bigint      NOT NULL DEFAULT -1,
    last_reconcile_at        timestamptz,
    reconcile_error          text,

    cpu_cores                int,
    cpu_millicores_capacity  bigint,
    cpu_millicores_used      bigint,
    memory_bytes_capacity    bigint,
    memory_bytes_used        bigint,
    disk_bytes_capacity      bigint,
    disk_bytes_used          bigint,
    workload_count           int         NOT NULL DEFAULT 0,
    running_workload_count   int         NOT NULL DEFAULT 0,

    docker_healthy           boolean,
    docker_version           text,
    -- False means the node falls back to runc. The panel shows the node as less isolated
    -- rather than refusing to use it (design §7.2), so this must never be silently null.
    runsc_available          boolean,
    kernel_version           text,
    os_description           text,
    -- Milliseconds the node's clock is away from the panel's. Large skew breaks ACME in
    -- ways that are very hard to diagnose from the symptom.
    clock_skew_millis        bigint,
    -- 'xfs' has project quotas; 'ext4' does not, and disk limits then cannot be
    -- enforced. quota_enforceable is the flag the panel shows the operator.
    volume_filesystem        text,
    quota_enforceable        boolean,

    -- The full `sasayaki doctor` report as a JSON document, stored as text.
    --
    -- text and not jsonb on purpose: PgJDBC sends a String parameter as varchar and
    -- PostgreSQL will not implicitly cast that to jsonb, so a jsonb column forces a
    -- custom converter into every package that writes one. Nothing in the panel queries
    -- inside this document; it is parsed with Jackson and rendered.
    doctor_report            text,
    doctor_reported_at       timestamptz,

    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint      NOT NULL DEFAULT 0,

    CONSTRAINT node_status_connection_state_known
        CHECK (connection_state IN ('DISCONNECTED', 'CONNECTED', 'DEGRADED')),
    CONSTRAINT node_status_applied_generation_sane CHECK (applied_generation >= -1),
    CONSTRAINT node_status_counts_not_negative
        CHECK (workload_count >= 0 AND running_workload_count >= 0
               AND running_workload_count <= workload_count)
);

-- The sweep that marks a node lost after wisper.node.heartbeat-timeout.
CREATE INDEX node_status_heartbeat_idx
    ON node_status (last_heartbeat_at)
    WHERE connection_state <> 'DISCONNECTED';

-- The admin node list, which sorts by whether the node is up.
CREATE INDEX node_status_connection_state_idx ON node_status (connection_state);
