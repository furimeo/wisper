-- A machine running sasayaki.
--
-- This table is the panel's side of the relationship and nothing else: the record, the
-- enrolment identity, and what an operator has decided about the node. Everything the
-- node reports about itself - heartbeat, capacity, applied generation, whether Docker is
-- answering - lives in node_status. Two tables rather than one because the ownership
-- rule (design §5.1, AGENTS.md §4.2) is that the panel writes intent and the node writes
-- fact, and a column written from both directions is the bug that rule exists to stop.

CREATE TABLE node (
    id                    uuid        PRIMARY KEY,
    -- Typed by an operator when creating the node, and typed again to confirm deletion.
    name                  text        NOT NULL,
    description           text        NOT NULL DEFAULT '',

    -- Enrolment identity. All three are null until the node completes Enroll().
    --
    -- fingerprint is machine-id plus hardware serial, hashed. It is unique so a cloned
    -- VM cannot enrol as a second node: the clone collides here, and the panel suspends
    -- rather than quietly splitting the workload across two machines with one
    -- credential (design §7.3).
    fingerprint           text,
    -- Ed25519 public key, base64. Generated on the node; the private half never leaves.
    public_key            text,
    -- SHA-256 of the long-lived credential the node presents in gRPC metadata. Hashed,
    -- not encrypted: the panel only ever compares.
    credential_hash       text,
    credential_issued_at  timestamptz,

    -- Operator intent.
    --   CREATED   - the record exists, no node has enrolled against it yet
    --   ENROLLED  - enrolled and eligible to run workloads
    --   DRAINING  - accepting no new placements, evacuating what it can
    --   DRAINED   - empty, safe to delete
    --   SUSPENDED - refused, usually a duplicate fingerprint; workloads keep running
    --   RETIRED   - kept for the audit trail, never scheduled again
    lifecycle             text        NOT NULL DEFAULT 'CREATED',
    -- The placement filter. Set false to stop new work without draining.
    schedulable           boolean     NOT NULL DEFAULT true,
    drain_requested_at    timestamptz,
    suspended_at          timestamptz,
    suspension_reason     text,

    -- Where customers' DNS points. The panel never dials it; it is shown to the
    -- customer so they can create an A record.
    public_address        text,
    -- The gRPC endpoint this node was told to dial, recorded so the panel can show what
    -- it pinned. Informational: the node holds the authoritative copy in node.json.
    dialled_endpoint      text,

    -- Placement filters: 'region=eu', 'ssd', 'gpu'. Matched with the array containment
    -- operator, which is why there is a GIN index below.
    tags                  text[]      NOT NULL DEFAULT '{}',

    -- The panel's monotonic spec counter. Bumped on every change to anything this node
    -- must run; never decreased, never reset. The node echoes back what it has applied
    -- in node_status.applied_generation, and the two being equal is the definition of
    -- "converged" (design §11.2).
    desired_generation    bigint      NOT NULL DEFAULT 0,
    spec_updated_at       timestamptz,

    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT node_name_shape CHECK (name ~ '^[a-z0-9][a-z0-9.-]{1,62}$'),
    CONSTRAINT node_lifecycle_known CHECK (lifecycle IN
        ('CREATED', 'ENROLLED', 'DRAINING', 'DRAINED', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT node_generation_not_negative CHECK (desired_generation >= 0),
    -- An enrolled node has all three halves of its identity, or none of them. A node
    -- with a credential but no fingerprint cannot be checked for cloning.
    CONSTRAINT node_enrolled_identity_complete
        CHECK (lifecycle = 'CREATED'
               OR (fingerprint IS NOT NULL AND public_key IS NOT NULL AND credential_hash IS NOT NULL)),
    CONSTRAINT node_suspension_consistent
        CHECK ((lifecycle = 'SUSPENDED') = (suspended_at IS NOT NULL)),
    CONSTRAINT node_suspension_reason_known
        CHECK (suspension_reason IS NULL OR suspension_reason IN
            ('DUPLICATE_FINGERPRINT', 'PROTOCOL_MISMATCH', 'OPERATOR', 'CREDENTIAL_REVOKED'))
);

CREATE UNIQUE INDEX node_name_key ON node (name);

-- Enrolment looks a node up by the fingerprint the machine reports; the uniqueness is
-- also the clone detector.
CREATE UNIQUE INDEX node_fingerprint_key ON node (fingerprint) WHERE fingerprint IS NOT NULL;

-- Read on every gRPC call the node makes, so it is the hottest index in this schema.
CREATE UNIQUE INDEX node_credential_hash_key
    ON node (credential_hash)
    WHERE credential_hash IS NOT NULL;

-- The scheduler's candidate set.
CREATE INDEX node_placeable_idx
    ON node (id)
    WHERE schedulable AND lifecycle = 'ENROLLED';

-- Tag filtering with tags @> ARRAY['region=eu'].
CREATE INDEX node_tags_idx ON node USING gin (tags);
