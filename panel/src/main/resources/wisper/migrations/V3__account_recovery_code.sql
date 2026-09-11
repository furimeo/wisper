-- Single-use recovery codes for the TOTP second factor.
--
-- Hashed, not encrypted: the panel only ever needs to answer "is this the code you were
-- given", and a store that cannot produce the code back is one an attacker cannot read
-- it out of either. Codes are shown to the customer exactly once, at generation time.

CREATE TABLE account_recovery_code (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    -- SHA-256 of the normalised code. Unique so a collision cannot let one code consume
    -- another account's slot.
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX account_recovery_code_hash_key ON account_recovery_code (code_hash);

-- "How many codes has this person got left", and the sweep that replaces a spent set.
CREATE INDEX account_recovery_code_unused_idx
    ON account_recovery_code (account_id)
    WHERE used_at IS NULL;
