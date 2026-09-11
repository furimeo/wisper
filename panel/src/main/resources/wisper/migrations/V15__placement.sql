-- Which node runs a service, and what that node says about the copy it is running.
--
-- The binding is deliberate and sticky: a service with a volume is pinned to the node
-- holding the bytes, and moving it is an explicit migration, never an automatic
-- reschedule (design §7.8).
--
-- The two halves of this table have different owners, and the split runs down the
-- middle of the column list:
--   * state, pinned, reason - the panel decides these;
--   * reported_* , container_id, restart_count, health - the node reports these and the
--     panel writes them only from a status report.

CREATE TABLE placement (
    id                   uuid        PRIMARY KEY,
    service_id           uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    -- RESTRICT: deleting a node that still holds placements would orphan a customer's
    -- workload with no record of where it went. Drain first; the drain releases the
    -- placements, and then the node deletes.
    node_id              uuid        NOT NULL REFERENCES node (id) ON DELETE RESTRICT,

    -- PLANNED  - chosen, not yet in a spec the node has applied
    -- ACTIVE   - in the node's spec
    -- DRAINING - being evacuated; a replacement placement is PLANNED or ACTIVE elsewhere
    -- RELEASED - historical, kept so the audit trail can answer "where did it run"
    state                text        NOT NULL DEFAULT 'PLANNED',
    -- True once a volume exists on this node. A pinned placement is never moved by the
    -- scheduler.
    pinned               boolean     NOT NULL DEFAULT false,
    reason               text        NOT NULL DEFAULT '',
    placed_at            timestamptz NOT NULL DEFAULT now(),
    released_at          timestamptz,

    -- Node-reported fact about this copy.
    reported_state       text,
    reported_at          timestamptz,
    container_id         text,
    running_image_digest text,
    restart_count        int         NOT NULL DEFAULT 0,
    last_exit_code       int,
    last_error           text,
    health               text,

    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,

    CONSTRAINT placement_state_known
        CHECK (state IN ('PLANNED', 'ACTIVE', 'DRAINING', 'RELEASED')),
    CONSTRAINT placement_released_consistent
        CHECK ((state = 'RELEASED') = (released_at IS NOT NULL)),
    CONSTRAINT placement_reported_state_known
        CHECK (reported_state IS NULL OR reported_state IN
            ('PENDING', 'CREATING', 'RUNNING', 'STOPPED', 'CRASHED', 'DEGRADED', 'UNKNOWN')),
    CONSTRAINT placement_health_known
        CHECK (health IS NULL OR health IN ('HEALTHY', 'UNHEALTHY', 'UNKNOWN')),
    CONSTRAINT placement_restart_count_not_negative CHECK (restart_count >= 0)
);

-- v1 runs one copy of a service, on one node. The partial unique index is what enforces
-- it, and it is partial so a DRAINING placement and its ACTIVE replacement can coexist
-- during a migration.
CREATE UNIQUE INDEX placement_one_active_per_service_idx
    ON placement (service_id)
    WHERE state = 'ACTIVE';

-- The spec builder's query: everything this node must be running. Read every time a
-- generation is cut, which is the hottest path on the gRPC side.
CREATE INDEX placement_active_by_node_idx
    ON placement (node_id, service_id)
    WHERE state IN ('PLANNED', 'ACTIVE', 'DRAINING');

-- "Where does this service run", including history.
CREATE INDEX placement_by_service_idx ON placement (service_id, placed_at DESC);

-- The drain screen: what is left on a node that is emptying.
CREATE INDEX placement_draining_idx
    ON placement (node_id)
    WHERE state = 'DRAINING';
