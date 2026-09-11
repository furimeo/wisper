-- What the panel knows about a TLS certificate. The node owns the certificate itself.
--
-- In v1 Caddy runs inside sasayaki, obtains the certificate over ACME and keeps the key
-- on the node's disk (design §11.3). The private key is never sent to the panel and
-- there is no column for it here. Every column in this table is node-reported fact; the
-- panel writes them from a status report and from nowhere else.
--
-- The panel keeps this record so it can show an expiry date, warn before a renewal that
-- is not happening, and answer "why is this domain serving a warning" without asking a
-- node that may be offline.

CREATE TABLE certificate (
    id                       uuid        PRIMARY KEY,
    domain_id                uuid        NOT NULL REFERENCES domain (id) ON DELETE CASCADE,
    node_id                  uuid        REFERENCES node (id) ON DELETE SET NULL,

    state                    text        NOT NULL DEFAULT 'PENDING',
    -- 'letsencrypt', 'zerossl', 'internal' - whatever the node's ACME client used.
    issuer                   text,
    serial_number            text,
    subject_common_name      text,
    subject_alternative_names text[]     NOT NULL DEFAULT '{}',
    fingerprint_sha256       text,
    not_before               timestamptz,
    not_after                timestamptz,

    obtained_at              timestamptz,
    last_renewal_attempt_at  timestamptz,
    renewal_failure_count    int         NOT NULL DEFAULT 0,
    last_error               text,

    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint      NOT NULL DEFAULT 0,

    CONSTRAINT certificate_state_known
        CHECK (state IN ('PENDING', 'ISSUED', 'RENEWING', 'FAILED', 'REVOKED')),
    CONSTRAINT certificate_issued_has_validity
        CHECK (state NOT IN ('ISSUED', 'RENEWING')
               OR (not_before IS NOT NULL AND not_after IS NOT NULL)),
    CONSTRAINT certificate_validity_ordered
        CHECK (not_before IS NULL OR not_after IS NULL OR not_before < not_after),
    CONSTRAINT certificate_renewal_failure_count_not_negative CHECK (renewal_failure_count >= 0)
);

-- At most one live certificate per hostname. Superseded and revoked rows stay for the
-- history.
CREATE UNIQUE INDEX certificate_live_per_domain_idx
    ON certificate (domain_id)
    WHERE state IN ('ISSUED', 'RENEWING');

-- The "expiring soon and nobody has renewed it" warning on the dashboard.
CREATE INDEX certificate_expiry_idx
    ON certificate (not_after)
    WHERE state IN ('ISSUED', 'RENEWING');

-- The certificate list on a node's admin page.
CREATE INDEX certificate_by_node_idx ON certificate (node_id);
