-- The single-use bootstrap token an operator hands to install.sh (design §7.1, §7.3).
--
-- A row, not a column on `node`, because the history matters: an expired token, a token
-- used from an unexpected address, and a token reissued after a failed install are three
-- different things an operator needs to be able to see.
--
-- Rules the node package must enforce, all of which this table makes checkable:
--   * hashed, never stored in the clear - the panel shows the token once, at creation;
--   * expires_at is issued_at + wisper.node.enrollment-token-ttl (15 minutes);
--   * used_at is set in the same transaction that writes node.credential_hash, so a
--     replay of the same token finds it already spent;
--   * a token belongs to exactly one node record and can enrol no other.

CREATE TABLE node_enrollment_token (
    id                    uuid        PRIMARY KEY,
    node_id               uuid        NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    -- SHA-256 of the token text.
    token_hash            text        NOT NULL,
    issued_by_account_id  uuid        REFERENCES account (id) ON DELETE SET NULL,
    expires_at            timestamptz NOT NULL,
    used_at               timestamptz,
    used_from_address     text,
    revoked_at            timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT node_enrollment_token_not_used_and_revoked
        CHECK (used_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX node_enrollment_token_hash_key ON node_enrollment_token (token_hash);

-- "Is there a live token for this node", shown on the node page next to the install
-- command.
CREATE INDEX node_enrollment_token_live_idx
    ON node_enrollment_token (node_id, expires_at DESC)
    WHERE used_at IS NULL AND revoked_at IS NULL;

-- The sweep that deletes tokens nobody used.
CREATE INDEX node_enrollment_token_expiry_idx ON node_enrollment_token (expires_at);
