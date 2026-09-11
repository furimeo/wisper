-- Build output, one line per row.
--
-- Rows rather than one growing text column because the deployment page streams this over
-- SSE and a reconnecting browser resumes with "everything after line N". A single column
-- rewritten on each line would also rewrite the whole value every time, which is how the
-- predecessor's build log became the slowest write in its database.
--
-- Append-only: nothing updates a row once written. It still carries `version`, because
-- that is what tells Spring Data JDBC an application-assigned uuid is a new row rather
-- than an update of a row that does not exist.

CREATE TABLE deployment_log (
    id            uuid        PRIMARY KEY,
    deployment_id uuid        NOT NULL REFERENCES deployment (id) ON DELETE CASCADE,
    -- Line number within the deployment, starting at 1. The SSE cursor.
    sequence      bigint      NOT NULL,
    stream        text        NOT NULL,
    message       text        NOT NULL,
    logged_at     timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,

    CONSTRAINT deployment_log_stream_known CHECK (stream IN ('STDOUT', 'STDERR', 'SYSTEM')),
    CONSTRAINT deployment_log_sequence_positive CHECK (sequence > 0)
);

-- Both the ordering and the "resume after line N" seek, and it rejects a duplicate line
-- if the node redelivers a chunk after a reconnect.
CREATE UNIQUE INDEX deployment_log_cursor_key ON deployment_log (deployment_id, sequence);
