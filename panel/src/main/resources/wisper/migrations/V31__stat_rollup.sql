-- Hourly and daily aggregates of stat_sample.
--
-- One table with a `granularity` column rather than stat_rollup_hourly and
-- stat_rollup_daily. The two would be the same twenty columns twice, the same record
-- twice and the same rollup use-case twice, differing only in a bucket width - and the
-- retention difference they would buy is one predicate in a DELETE.
--
-- Retention: HOUR buckets for 30 days, DAY buckets for 400. A chart older than that is
-- read from nothing, because nothing kept it.
--
-- Averages are stored pre-divided as longs. Averaging an average of an average is how
-- the numbers stop meaning anything, so the daily bucket is computed from raw samples
-- where they still exist and from hourly buckets weighted by sample_count where they do
-- not.

CREATE TABLE stat_rollup (
    id                     uuid        PRIMARY KEY,
    node_id                uuid        NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    service_id             uuid        REFERENCES service (id) ON DELETE CASCADE,

    granularity            text        NOT NULL,
    -- Truncated to the granularity, always in UTC.
    bucket_start           timestamptz NOT NULL,
    -- How many raw samples went in. The weight for any further aggregation, and the way
    -- to tell a quiet hour from a missing one.
    sample_count           int         NOT NULL,

    cpu_millicores_avg     bigint      NOT NULL DEFAULT 0,
    cpu_millicores_max     bigint      NOT NULL DEFAULT 0,
    memory_bytes_avg       bigint      NOT NULL DEFAULT 0,
    memory_bytes_max       bigint      NOT NULL DEFAULT 0,
    disk_bytes_max         bigint      NOT NULL DEFAULT 0,
    network_rx_bytes       bigint      NOT NULL DEFAULT 0,
    network_tx_bytes       bigint      NOT NULL DEFAULT 0,
    disk_read_bytes        bigint      NOT NULL DEFAULT 0,
    disk_write_bytes       bigint      NOT NULL DEFAULT 0,
    restart_count          int         NOT NULL DEFAULT 0,

    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT stat_rollup_granularity_known CHECK (granularity IN ('HOUR', 'DAY')),
    CONSTRAINT stat_rollup_sample_count_positive CHECK (sample_count > 0),
    CONSTRAINT stat_rollup_bucket_aligned CHECK (
        (granularity = 'HOUR' AND bucket_start = date_trunc('hour', bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
        OR
        (granularity = 'DAY' AND bucket_start = date_trunc('day', bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')),
    CONSTRAINT stat_rollup_maxima_not_below_averages CHECK (
        cpu_millicores_max >= cpu_millicores_avg AND memory_bytes_max >= memory_bytes_avg)
);

-- One bucket per subject per period. The rollup job upserts against this, so re-running
-- it over the same window corrects rather than duplicates.
CREATE UNIQUE INDEX stat_rollup_bucket_key
    ON stat_rollup (granularity, node_id, service_id, bucket_start) NULLS NOT DISTINCT;

-- The node chart beyond 48 hours.
CREATE INDEX stat_rollup_node_window_idx
    ON stat_rollup (node_id, granularity, bucket_start DESC);

-- The service chart beyond 48 hours.
CREATE INDEX stat_rollup_service_window_idx
    ON stat_rollup (service_id, granularity, bucket_start DESC)
    WHERE service_id IS NOT NULL;

-- The retention sweep.
CREATE INDEX stat_rollup_retention_idx ON stat_rollup (granularity, bucket_start);
