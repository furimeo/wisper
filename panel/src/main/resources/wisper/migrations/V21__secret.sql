-- Environment values the panel must be able to hand to a node but must never show back
-- to a browser.
--
-- Encrypted at rest with the panel key, in the envelope format every encrypted column in
-- this schema uses:
--
--     v<keyVersion>.<base64url nonce>.<base64url ciphertext>
--
-- One text column rather than three, so rotating a key is a rewrite of one value and a
-- reader always knows which key produced what it is holding. AES-GCM, so the ciphertext
-- carries its own tag and a truncated value fails to decrypt instead of decrypting to
-- rubbish.
--
-- The API never returns `value`. The panel shows the name, when it was last changed, and
-- nothing else; a customer who has forgotten a secret sets a new one.

CREATE TABLE secret (
    id              uuid        PRIMARY KEY,
    service_id      uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    value           text        NOT NULL,
    build_time      boolean     NOT NULL DEFAULT false,
    last_rotated_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT secret_name_shape CHECK (name ~ '^[A-Za-z_][A-Za-z0-9_]*$'),
    CONSTRAINT secret_name_not_reserved CHECK (name NOT LIKE 'WISPER\_%'),
    -- Catches the mistake that matters most: a plaintext value written into the
    -- encrypted column. Every envelope starts with a version marker.
    CONSTRAINT secret_value_is_envelope CHECK (value ~ '^v[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$')
);

CREATE UNIQUE INDEX secret_service_name_key ON secret (service_id, name);
