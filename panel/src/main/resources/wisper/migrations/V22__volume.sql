-- Persistent storage attached to a service.
--
-- A volume is what pins a service to a node: the bytes are on one machine, and moving
-- them is a deliberate migration rather than something the scheduler does while nobody
-- is looking (design §7.8).
--
-- The storage layout is decided here and not later, because changing it means moving
-- customer data (design §11.1). On the node:
--
--     /var/lib/wisper/volumes/<service-id>/<volume-id>
--
-- Ids, not slugs: renaming a service must not move a directory. `size_bytes` is the XFS
-- project quota the node applies; on a filesystem without project quota the node reports
-- quota_enforceable = false in node_status and the limit is advisory.

CREATE TABLE volume (
    id                     uuid        PRIMARY KEY,
    service_id             uuid        NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    name                   text        NOT NULL,
    -- Where it appears inside the container.
    mount_path             text        NOT NULL,
    -- Panel intent: the quota.
    size_bytes             bigint      NOT NULL,
    read_only              boolean     NOT NULL DEFAULT false,
    backup_enabled         boolean     NOT NULL DEFAULT true,

    -- Node-reported fact. Null until the node has reported once.
    used_bytes             bigint,
    used_bytes_measured_at timestamptz,
    inode_count            bigint,
    -- The absolute path on the node, as the node resolved it. Displayed in the file
    -- manager header; the panel never builds a path from it.
    host_path              text,
    reported_state         text,
    last_error             text,

    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT volume_name_shape CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT volume_size_positive CHECK (size_bytes > 0),
    CONSTRAINT volume_used_not_negative CHECK (used_bytes IS NULL OR used_bytes >= 0),
    -- An absolute path, and not one that can climb out of the container.
    CONSTRAINT volume_mount_path_absolute CHECK (mount_path LIKE '/%'),
    CONSTRAINT volume_mount_path_no_traversal CHECK (position('..' IN mount_path) = 0),
    -- Mounting over these breaks the container or hides the platform's own files.
    CONSTRAINT volume_mount_path_not_system
        CHECK (mount_path NOT IN ('/', '/etc', '/proc', '/sys', '/dev', '/usr', '/bin', '/sbin', '/lib')),
    CONSTRAINT volume_reported_state_known
        CHECK (reported_state IS NULL
               OR reported_state IN ('PENDING', 'READY', 'MIGRATING', 'FAILED', 'MISSING'))
);

CREATE UNIQUE INDEX volume_service_name_key ON volume (service_id, name);

-- Two volumes on the same mount point would make the spec ambiguous.
CREATE UNIQUE INDEX volume_service_mount_path_key ON volume (service_id, mount_path);

-- The quota rollup: bytes used across an organization, joined through service and
-- project.
CREATE INDEX volume_usage_idx ON volume (service_id, used_bytes);
