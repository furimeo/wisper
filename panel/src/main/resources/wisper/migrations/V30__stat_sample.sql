-- Raw resource samples pushed by nodes over PushStats.
--
-- A sample with a null service_id is about the node itself; one with a service_id is
-- about a workload on it. Both live here because they arrive on the same stream, are
-- rolled up by the same job and are charted on the same axes.
--
-- Everything is a long in the smallest unit. cpu is millicores, not a percentage: a
-- percentage of what is a question with a different answer on every node.
--
-- This table is high-volume and short-lived. Raw samples are kept for 48 hours and then
-- deleted by the stats package's retention job; anything older is read from stat_rollup.

CREATE TABLE stat_sample (
    id                 uuid        PRIMARY KEY,
    node_id            uuid        NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    service_id         uuid        REFERENCES service (id) ON DELETE CASCADE,

    -- When the node measured it, not when the panel stored it. A reconnecting node
    -- backfills, and a chart drawn on arrival time would show a spike that never
    -- happened.
    sampled_at         timestamptz NOT NULL,
    -- Seconds the counters below cover.
    window_seconds     int         NOT NULL DEFAULT 15,

    cpu_millicores     bigint      NOT NULL DEFAULT 0,
    memory_bytes       bigint      NOT NULL DEFAULT 0,
    memory_limit_bytes bigint,
    disk_bytes         bigint      NOT NULL DEFAULT 0,
    network_rx_bytes   bigint      NOT NULL DEFAULT 0,
    network_tx_bytes   bigint      NOT NULL DEFAULT 0,
    disk_read_bytes    bigint      NOT NULL DEFAULT 0,
    disk_write_bytes   bigint      NOT NULL DEFAULT 0,
    restart_count      int         NOT NULL DEFAULT 0,

    created_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT stat_sample_window_positive CHECK (window_seconds > 0),
    CONSTRAINT stat_sample_counters_not_negative CHECK (
        cpu_millicores >= 0 AND memory_bytes >= 0 AND disk_bytes >= 0
        AND network_rx_bytes >= 0 AND network_tx_bytes >= 0
        AND disk_read_bytes >= 0 AND disk_write_bytes >= 0 AND restart_count >= 0)
);

-- Makes PushStats idempotent. The stream reconnects, the node resends what it is not
-- sure landed, and a duplicate sample is rejected instead of doubling a chart.
-- NULLS NOT DISTINCT because node-level samples all have a null service_id and must
-- still deduplicate against each other.
CREATE UNIQUE INDEX stat_sample_reading_key
    ON stat_sample (node_id, service_id, sampled_at) NULLS NOT DISTINCT;

-- Node metrics over a window - the access path the design calls out by name.
CREATE INDEX stat_sample_node_window_idx ON stat_sample (node_id, sampled_at DESC);

-- Service metrics over a window, for /services/{id}/metrics.
CREATE INDEX stat_sample_service_window_idx
    ON stat_sample (service_id, sampled_at DESC)
    WHERE service_id IS NOT NULL;

-- The rollup and retention jobs both scan by time across all nodes.
CREATE INDEX stat_sample_sampled_at_idx ON stat_sample (sampled_at);
