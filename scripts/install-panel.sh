#!/bin/sh
# wisper panel first-run installer.
#
# This covers the panel side only. Node installs are a different story and live
# in deploy/install.sh plus `sasayaki install`: a node enrols with a single-use
# token, while the panel needs a database, an encryption key, and a process
# supervisor entry. One script per side keeps both honest.
#
# What this script does, in order:
#   1. checks for java 21, psql (PostgreSQL 17) and openssl, and stops if any
#      is missing, because every later step assumes them;
#   2. creates the wisper role and the wisper database when they do not exist
#      yet, so re-running it on a working host changes nothing;
#   3. writes an env file holding WISPER_DB_USER, WISPER_DB_PASSWORD and
#      WISPER_CRYPTO_KEY_1, generating the key with `openssl rand -base64 32`
#      when the file has none yet, because a key invented at boot would encrypt
#      a row and then be unable to read it back after a restart;
#   4. prints the command that runs the panel and an example systemd unit, so
#      the operator can copy it rather than reconstruct it from the docs.
#
# What it never does: it does not start the panel, it does not enable a
# service, and it does not touch an existing key. An existing
# WISPER_CRYPTO_KEY_1 in the env file is always kept, since replacing it
# orphans every encrypted column in the database.
#
# POSIX sh: same rule as deploy/install.sh, the host may be minimal.
set -eu

usage() {
  cat >&2 <<'USAGE'
Usage: install-panel.sh [options]

  --jar <path>       The panel jar to run. Default ./wisper.jar.
  --env-file <path>  Where to write WISPER_* values. Default
                     /etc/wisper/panel.env as root, ./panel.env otherwise.
  --db-user <name>   Database role the panel logs in as. Default wisper,
                     or $WISPER_DB_USER when set.
  --db-name <name>   Database to create and use. Default wisper.
  --db-host <host>   PostgreSQL host for setup. Default localhost. The panel
                     itself connects to localhost:5432 unless the env file
                     sets SPRING_DATASOURCE_URL, so a non-default host or
                     port is written there for you.
  --db-port <port>   PostgreSQL port for setup. Default 5432.
  --skip-db          Do not touch PostgreSQL. Use when the database and role
                     already exist, or a managed database provides them.
  --write-unit       Also write the example systemd unit to
                     /etc/systemd/system/wisper-panel.service. Without this
                     the unit is only printed, nothing is installed.
  -h, --help         Show this text.

Secrets never travel as arguments: argv is readable by every user on this
machine through ps, so the database password comes from $WISPER_DB_PASSWORD
(default wisper) and the crypto key is generated with openssl and stored in
the env file, which is chmod 0600.
USAGE
  exit 2
}

JAR="./wisper.jar"
ENV_FILE=""
DB_USER="${WISPER_DB_USER:-wisper}"
DB_PASSWORD="${WISPER_DB_PASSWORD:-wisper}"
DB_NAME="wisper"
DB_HOST="localhost"
DB_PORT="5432"
SKIP_DB=""
WRITE_UNIT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --jar)      [ $# -ge 2 ] || usage; JAR="$2";      shift 2 ;;
    --env-file) [ $# -ge 2 ] || usage; ENV_FILE="$2"; shift 2 ;;
    --db-user)  [ $# -ge 2 ] || usage; DB_USER="$2";  shift 2 ;;
    --db-name)  [ $# -ge 2 ] || usage; DB_NAME="$2";  shift 2 ;;
    --db-host)  [ $# -ge 2 ] || usage; DB_HOST="$2";  shift 2 ;;
    --db-port)  [ $# -ge 2 ] || usage; DB_PORT="$2";  shift 2 ;;
    --skip-db) SKIP_DB="yes"; shift ;;
    --write-unit) WRITE_UNIT="yes"; shift ;;
    --db-password|--db-password=*)
      echo "install-panel.sh: refusing --db-password. argv is readable by every user" >&2
      echo "            on this machine through ps. Export WISPER_DB_PASSWORD instead." >&2
      exit 2 ;;
    -h|--help) usage ;;
    *) echo "install-panel.sh: unknown argument $1" >&2; usage ;;
  esac
done

if [ -z "$ENV_FILE" ]; then
  if [ "$(id -u)" = "0" ]; then
    ENV_FILE="/etc/wisper/panel.env"
  else
    ENV_FILE="$PWD/panel.env"
  fi
fi

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "install-panel.sh: $1 is not installed ($2)." >&2
    exit 1; }
}

need java "the panel needs a JVM 21: apt install temurin-21-jre or your distro equivalent"
need psql "the panel needs PostgreSQL 17: https://www.postgresql.org/download/"
need openssl "used once, to generate WISPER_CRYPTO_KEY_1"

# java -version writes to stderr and its first line looks like:
#   openjdk version "21.0.3" 2024-04-16
JAVA_LINE=$(java -version 2>&1 | head -n 1)
case "$JAVA_LINE" in
  *'"21'*|*' 21.'*|*' 21"'*|*version\ 21*|'openjdk\ 21'* ) : ;;
  *) echo "install-panel.sh: $JAVA_LINE" >&2
     echo "            The panel needs a JVM 21. Refusing to continue with another version." >&2
     exit 1 ;;
esac

# psql as the local superuser. As root via sudo when a postgres account exists,
# otherwise straight psql, which honours PGHOST/PGUSER/PGPASSWORD from the caller.
run_psql() {
  if [ "$(id -u)" = "0" ] && command -v sudo >/dev/null 2>&1 && id postgres >/dev/null 2>&1; then
    sudo -u postgres psql -h "$DB_HOST" -p "$DB_PORT" -v ON_ERROR_STOP=1 "$@"
  else
    psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -v ON_ERROR_STOP=1 "$@"
  fi
}

if [ -z "$SKIP_DB" ]; then
  SERVER_VERSION=$(run_psql -tAc "SHOW server_version_num;" | tr -d '[:space:]')
  case "$SERVER_VERSION" in
    17*) : ;;
    *) echo "install-panel.sh: PostgreSQL server reports version $SERVER_VERSION, expected 17.x." >&2
       echo "            Continuing anyway; migrations target PostgreSQL 17." >&2 ;;
  esac

  # Single quotes inside the password are doubled for the SQL literal. The
  # default wisper/wisper pair from the README needs no escaping; this is for
  # operators who set WISPER_DB_PASSWORD to something with punctuation in it.
  ESCAPED_PASSWORD=$(printf "%s" "$DB_PASSWORD" | sed "s/'/''/g")

  if [ "$(run_psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$DB_USER';")" != "1" ]; then
    run_psql -c "CREATE ROLE \"$DB_USER\" WITH LOGIN PASSWORD '$ESCAPED_PASSWORD';"
    echo "Created role $DB_USER."
  else
    echo "Role $DB_USER already exists, leaving it alone."
  fi

  if [ "$(run_psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME';")" != "1" ]; then
    run_psql -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\";"
    echo "Created database $DB_NAME owned by $DB_USER."
  else
    echo "Database $DB_NAME already exists, leaving it alone."
  fi

  run_psql -c "GRANT ALL ON DATABASE \"$DB_NAME\" TO \"$DB_USER\";"
else
  echo "Skipping database setup (--skip-db)."
fi

# The env file is the only place production secrets live. umask 077 first so a
# concurrently created file is never briefly world-readable, then add only the
# keys that are missing, so a re-run keeps the crypto key it generated before.
ENV_DIR=$(dirname -- "$ENV_FILE")
if [ ! -d "$ENV_DIR" ]; then
  mkdir -p "$ENV_DIR"
fi
if [ ! -f "$ENV_FILE" ]; then
  : > "$ENV_FILE"
fi
chmod 0600 "$ENV_FILE"

ensure_key() {
  # $1 is the KEY, $2 the value to write when the KEY is absent.
  if grep -q "^$1=" "$ENV_FILE"; then
    return 0
  fi
  umask 077
  printf "%s=%s\n" "$1" "$2" >> "$ENV_FILE"
  echo "Wrote $1 to $ENV_FILE."
}

ensure_key "WISPER_DB_USER" "$DB_USER"
ensure_key "WISPER_DB_PASSWORD" "$DB_PASSWORD"

if grep -q "^WISPER_CRYPTO_KEY_1=." "$ENV_FILE"; then
  echo "WISPER_CRYPTO_KEY_1 already present in $ENV_FILE, keeping it."
else
  # 32 random bytes as base64 is the AES-256 key the crypto column format
  # expects. Same command as the README and application.yml comment.
  NEW_KEY=$(openssl rand -base64 32)
  # Filter to the assignment form so a stale empty line cannot shadow the key.
  grep -v "^WISPER_CRYPTO_KEY_1=" "$ENV_FILE" > "$ENV_FILE.tmp" || true
  mv "$ENV_FILE.tmp" "$ENV_FILE"
  chmod 0600 "$ENV_FILE"
  umask 077
  printf "%s=%s\n" "WISPER_CRYPTO_KEY_1" "$NEW_KEY" >> "$ENV_FILE"
  echo "Generated WISPER_CRYPTO_KEY_1 in $ENV_FILE. Back it up: losing it loses every encrypted column."
fi

# application.yml points the panel at localhost:5432 with no indirection, so a
# database anywhere else needs SPRING_DATASOURCE_URL, which Spring Boot binds
# over spring.datasource.url. Written only when the setup host or port says so.
if [ "$DB_HOST" != "localhost" ] || [ "$DB_PORT" != "5432" ]; then
  ensure_key "SPRING_DATASOURCE_URL" "jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
fi

# The unit below is a starting point, not a managed file: edit the jar path and
# the user to match the host, then `systemctl edit wisper-panel` for local
# changes so a future copy does not silently drop them.
UNIT="[Unit]
Description=wisper panel
Documentation=https://github.com/furimeo/wisper
After=network-online.target postgresql.service
Wants=network-online.target postgresql.service

[Service]
Type=simple
User=wisper
WorkingDirectory=/opt/wisper
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java -jar $JAR
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"

if [ -n "$WRITE_UNIT" ]; then
  [ "$(id -u)" = "0" ] || { echo "install-panel.sh: --write-unit needs root." >&2; exit 1; }
  printf "%s" "$UNIT" > /etc/systemd/system/wisper-panel.service
  chmod 0644 /etc/systemd/system/wisper-panel.service
  echo "Wrote /etc/systemd/system/wisper-panel.service. Run: systemctl daemon-reload && systemctl enable --now wisper-panel"
else
  printf "\n%s\n" "---- systemd unit hint (copy to /etc/systemd/system/wisper-panel.service) ----"
  printf "%s" "$UNIT"
fi

cat <<EOF

Next steps:
  1. Run it once by hand:  set -a; . $ENV_FILE; set +a; java -jar $JAR
     Migrations run themselves on boot. HTTP listens on 8080, nodes dial
     gRPC on 9090.
  2. On a database with no accounts the panel creates the first operator and
     prints the password once, at WARN. Sign in, change it, turn on
     two-factor.
  3. Front the panel with a tunnel or reverse proxy that forwards the client
     address (application.yml sets forward-headers-strategy: framework).

Required env vars and their defaults (see README, Running the panel):
  WISPER_DB_USER=$DB_USER (default wisper)
  WISPER_DB_PASSWORD=<from env file $ENV_FILE> (default wisper)
  WISPER_CRYPTO_KEY_1=<from env file $ENV_FILE> (no default, required)
EOF
