-- Every state-changing action, with who did it, from where, and to what
-- (AGENTS.md §5, design §9).
--
-- Append-only. Nothing updates a row, nothing deletes one except the retention job, and
-- the actor references are ON DELETE SET NULL rather than CASCADE so deleting an account
-- cannot erase what that account did. The denormalised actor_label and target_label are
-- the reason the row still reads correctly afterwards.
--
-- A DENIED outcome is recorded too. "Somebody tried and was refused" is the entry an
-- incident is reconstructed from, and it is the one a log that only records successes
-- does not have.

CREATE TABLE audit_log (
    id               uuid        PRIMARY KEY,
    occurred_at      timestamptz NOT NULL DEFAULT now(),

    organization_id  uuid        REFERENCES organization (id) ON DELETE SET NULL,

    actor_kind       text        NOT NULL,
    actor_account_id uuid        REFERENCES account (id) ON DELETE SET NULL,
    api_token_id     uuid        REFERENCES api_token (id) ON DELETE SET NULL,
    node_id          uuid        REFERENCES node (id) ON DELETE SET NULL,
    -- The email, token name or node name as it was at the time.
    actor_label      text        NOT NULL,

    -- Dotted and stable: 'service.start', 'domain.create', 'node.enroll'. Used in
    -- filters, so it is a value and not prose.
    action           text        NOT NULL,
    target_kind      text        NOT NULL,
    target_id        uuid,
    target_label     text        NOT NULL DEFAULT '',
    outcome          text        NOT NULL,

    remote_address   text,
    user_agent       text,
    -- Ties several entries written while handling one request together.
    request_id       text,
    -- JSON document with whatever the action needs to be understandable later - the old
    -- and new value of a changed field, the reason for a denial. Text, not jsonb, for
    -- the reason given in node_status.doctor_report. Never contains a secret value.
    detail           text        NOT NULL DEFAULT '',

    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT audit_log_actor_kind_known
        CHECK (actor_kind IN ('ACCOUNT', 'API_TOKEN', 'NODE', 'SYSTEM')),
    CONSTRAINT audit_log_outcome_known
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'DENIED')),
    CONSTRAINT audit_log_action_shape CHECK (action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT audit_log_actor_label_not_blank CHECK (length(btrim(actor_label)) > 0),
    -- The actor references that are set must agree with actor_kind. An API_TOKEN entry
    -- carries both the token and the account it belongs to; the others carry one.
    --
    -- Deliberately phrased as "these must be null" rather than "that one must be set".
    -- The references are ON DELETE SET NULL so the trail outlives the actor, and
    -- SET NULL is an UPDATE that has to satisfy this constraint: a check demanding
    -- actor_account_id IS NOT NULL would make deleting an account fail outright, which
    -- is the opposite of keeping the history. Only removal can happen here, and removal
    -- cannot violate a clause that asks for absence.
    --
    -- Who did it therefore survives in actor_label, which is required and non-blank.
    -- Setting the matching id at insert time is the writer's job.
    CONSTRAINT audit_log_actor_consistent CHECK (
        (actor_kind = 'ACCOUNT'   AND api_token_id IS NULL AND node_id IS NULL)
        OR (actor_kind = 'API_TOKEN' AND node_id IS NULL)
        OR (actor_kind = 'NODE'      AND actor_account_id IS NULL AND api_token_id IS NULL)
        OR (actor_kind = 'SYSTEM'    AND actor_account_id IS NULL AND api_token_id IS NULL
                                     AND node_id IS NULL))
);

-- The platform-wide log at /admin/audit.
CREATE INDEX audit_log_recent_idx ON audit_log (occurred_at DESC);

-- The per-organization log a customer sees.
CREATE INDEX audit_log_by_organization_idx
    ON audit_log (organization_id, occurred_at DESC)
    WHERE organization_id IS NOT NULL;

-- "What has this person done", used when investigating an account.
CREATE INDEX audit_log_by_actor_idx
    ON audit_log (actor_account_id, occurred_at DESC)
    WHERE actor_account_id IS NOT NULL;

-- "What happened to this service", shown on the object's own page.
CREATE INDEX audit_log_by_target_idx
    ON audit_log (target_kind, target_id, occurred_at DESC)
    WHERE target_id IS NOT NULL;

-- The node timeline on the admin node page.
CREATE INDEX audit_log_by_node_idx
    ON audit_log (node_id, occurred_at DESC)
    WHERE node_id IS NOT NULL;
