-- Where snapshots are pushed: an S3-compatible bucket, or a directory on the node.
--
-- A null organization_id is a platform-wide destination an operator configured and every
-- organization may use. A non-null one belongs to that customer and nobody else sees it.

CREATE TABLE backup_destination (
    id                     uuid        PRIMARY KEY,
    organization_id        uuid        REFERENCES organization (id) ON DELETE CASCADE,
    name                   text        NOT NULL,
    kind                   text        NOT NULL,

    -- S3
    endpoint               text,
    region                 text,
    bucket                 text,
    path_prefix            text        NOT NULL DEFAULT '',
    access_key_id          text,
    -- Encrypted at rest, envelope format (see V21).
    secret_access_key      text,
    storage_class          text,

    -- LOCAL
    local_path             text,

    -- Client-side encryption of the archive before it leaves the node, so an offsite
    -- bucket never holds readable customer data. Encrypted at rest itself.
    archive_passphrase     text,

    enabled                boolean     NOT NULL DEFAULT true,
    last_checked_at        timestamptz,
    last_check_error       text,

    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT backup_destination_kind_known CHECK (kind IN ('S3', 'LOCAL')),
    CONSTRAINT backup_destination_s3_complete
        CHECK (kind <> 'S3' OR (endpoint IS NOT NULL AND bucket IS NOT NULL
                                AND access_key_id IS NOT NULL AND secret_access_key IS NOT NULL)),
    CONSTRAINT backup_destination_local_complete
        CHECK (kind <> 'LOCAL' OR local_path IS NOT NULL),
    CONSTRAINT backup_destination_local_path_absolute
        CHECK (local_path IS NULL OR local_path LIKE '/%'),
    CONSTRAINT backup_destination_secret_is_envelope
        CHECK (secret_access_key IS NULL
               OR secret_access_key ~ '^v[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$'),
    CONSTRAINT backup_destination_passphrase_is_envelope
        CHECK (archive_passphrase IS NULL
               OR archive_passphrase ~ '^v[0-9]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$')
);

-- NULLS NOT DISTINCT so the platform-wide destinations, which all have a null
-- organization_id, still cannot share a name with each other.
CREATE UNIQUE INDEX backup_destination_scope_name_key
    ON backup_destination (organization_id, name) NULLS NOT DISTINCT;

-- The picker: this organization's destinations plus the shared ones.
CREATE INDEX backup_destination_usable_idx
    ON backup_destination (organization_id)
    WHERE enabled;
