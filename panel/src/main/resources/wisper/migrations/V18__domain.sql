-- A hostname pointed at a service.
--
-- Globally unique, and not per-organization: two customers cannot both claim
-- example.com, and the node's on-demand TLS `ask` handler answers from a route table
-- keyed by hostname alone. In v1 a hostname belongs to exactly one node, because the
-- node owns the certificate and certificates are not shared between nodes
-- (design §5.4, §11.3).

CREATE TABLE domain (
    id                  uuid        PRIMARY KEY,
    service_id          uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    -- Lower-cased and stored as an IDNA A-label, so a plain unique constraint is a
    -- case-insensitive one and Caddy can match it byte for byte.
    hostname            text        NOT NULL,
    kind                text        NOT NULL DEFAULT 'ALIAS',
    tls_mode            text        NOT NULL DEFAULT 'ON_DEMAND',

    -- Does DNS actually point here? The panel checks and records; it never blocks the
    -- customer from adding the record first.
    verification_state  text        NOT NULL DEFAULT 'PENDING',
    -- The value the customer puts in a TXT record when they cannot point A/AAAA yet.
    verification_token  text,
    verified_at         timestamptz,
    last_checked_at     timestamptz,
    last_check_error    text,

    -- Set to serve a permanent redirect instead of the service. An alias that only
    -- redirects still needs a certificate, which is why it is a domain and not a flag.
    redirect_to_hostname text,
    force_https         boolean     NOT NULL DEFAULT true,
    -- Overrides service.container_port for this hostname. Null means the service port.
    target_port         int,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,

    CONSTRAINT domain_hostname_lowercase CHECK (hostname = lower(hostname)),
    CONSTRAINT domain_hostname_shape CHECK (hostname ~ '^(\*\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?$'),
    CONSTRAINT domain_kind_known CHECK (kind IN ('PRIMARY', 'ALIAS', 'WILDCARD')),
    CONSTRAINT domain_wildcard_shape
        CHECK ((kind = 'WILDCARD') = (hostname LIKE '*.%')),
    CONSTRAINT domain_tls_mode_known CHECK (tls_mode IN ('ON_DEMAND', 'STATIC', 'OFF')),
    CONSTRAINT domain_verification_state_known
        CHECK (verification_state IN ('PENDING', 'VERIFIED', 'FAILED')),
    CONSTRAINT domain_verified_consistent
        CHECK ((verification_state = 'VERIFIED') = (verified_at IS NOT NULL)),
    CONSTRAINT domain_target_port_range
        CHECK (target_port IS NULL OR target_port BETWEEN 1 AND 65535),
    CONSTRAINT domain_redirect_lowercase
        CHECK (redirect_to_hostname IS NULL OR redirect_to_hostname = lower(redirect_to_hostname))
);

-- Global uniqueness, and the lookup the ask-handler route table is built from.
CREATE UNIQUE INDEX domain_hostname_key ON domain (hostname);

CREATE INDEX domain_by_service_idx ON domain (service_id);

-- One primary hostname per service: the one the panel shows as "your site is at".
CREATE UNIQUE INDEX domain_primary_per_service_idx
    ON domain (service_id)
    WHERE kind = 'PRIMARY';

-- The re-check sweep, oldest first.
CREATE INDEX domain_unverified_idx
    ON domain (last_checked_at NULLS FIRST)
    WHERE verification_state <> 'VERIFIED';
