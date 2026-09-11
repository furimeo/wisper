-- One row per signed-in browser.
--
-- This is *not* Spring Session JDBC: spring-session-jdbc is not a dependency, and the
-- servlet container still holds the HttpSession. What this table adds is the part the
-- container cannot do - a list of active sessions the customer can look at in
-- /settings, a "sign out everywhere" that takes effect on the next request, and a
-- record of where each sign-in came from.
--
-- The auth package writes a row on successful authentication and checks revoked_at on
-- each request. Nothing else may write it.

CREATE TABLE session (
    id                uuid        PRIMARY KEY,
    account_id        uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    -- SHA-256 of the container's session id. The raw id is a bearer credential; storing
    -- it would turn a database read into a session hijack.
    session_id_hash   text        NOT NULL,
    -- IPv4/IPv6 literal as text rather than inet: PgJDBC sends a String parameter as
    -- varchar, and PostgreSQL will not implicitly cast that to inet, so an inet column
    -- would need a converter in every package that writes one.
    remote_address    text,
    user_agent        text,
    -- Null while the TOTP challenge is outstanding. A session that has not passed the
    -- second factor may reach /login/** and nothing else.
    second_factor_at  timestamptz,
    last_seen_at      timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL,
    revoked_at        timestamptz,
    revoked_reason    text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,

    CONSTRAINT session_revoked_reason_known
        CHECK (revoked_reason IS NULL
               OR revoked_reason IN ('SIGNED_OUT', 'SIGNED_OUT_EVERYWHERE', 'PASSWORD_CHANGED',
                                     'ACCOUNT_SUSPENDED', 'EXPIRED', 'ADMIN'))
);

-- The lookup on every authenticated request.
CREATE UNIQUE INDEX session_id_hash_key ON session (session_id_hash);

-- The /settings "active sessions" list.
CREATE INDEX session_by_account_idx ON session (account_id, last_seen_at DESC);

-- The expiry sweep. Partial, because expired rows are deleted and live ones are the
-- only interesting set.
CREATE INDEX session_live_expiry_idx
    ON session (expires_at)
    WHERE revoked_at IS NULL;
