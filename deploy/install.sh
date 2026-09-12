#!/bin/sh
# wisper node installer, offline edition.
#
# This is the copy in the repository, for the two cases the panel's own installer cannot
# cover: a node with no route to the panel's web interface, and a developer installing a
# binary they have just built (design section 7.1, "cài offline").
#
# The panel serves a different script at /install.sh. That one is generated, with the
# panel's URL and the SHA-256 of every published binary baked into it, and it is the one
# an operator should use when the machine can reach the panel:
#
#   curl -fsSL https://panel.example/install.sh -o install.sh
#   sha256sum -c <<< "<the hash shown on the panel>  install.sh"
#   sudo ./install.sh --token-file token.txt
#
# What this script does is find a sasayaki binary, check it is the one you meant, and hand
# over to `sasayaki install`. Everything that matters afterwards - the preflight, the
# enrolment, the systemd unit, the fact that nothing is written when a required check
# fails - happens in that binary, so both paths behave identically and there is only one
# implementation of any of it.
#
# POSIX sh: a freshly provisioned node may not have bash, and this is the first thing that
# runs on it.
set -eu

usage() {
  cat >&2 <<'USAGE'
Usage: install.sh --token-file <path> --panel <url> [options]

  --token-file <path>  The single-use bootstrap token, or - to read it from stdin.
                       The file is deleted once it has been read.
  --panel <url>        The panel's gRPC endpoint, https://panel.example[:port].
                       Optional when this node is already enrolled.
  --binary <path>      The sasayaki binary to install. Defaults to the first of
                       ./sasayaki, ./dist/sasayaki-linux-<arch> or
                       ../sasayaki/dist/sasayaki-linux-<arch> that exists.
  --sha256 <hex>       Refuse the binary unless it hashes to this. A <binary>.sha256
                       file beside it is used automatically when this is not given.
  --state-dir <path>   Customer data, SQLite and certificates. Default /var/lib/wisper.
  --config <path>      The node credential. Default /etc/wisper/node.json.
  --skip-doctor        Install even when a required preflight check fails.

The token is never accepted as an argument: argv is readable by every user on this
machine through ps.
USAGE
  exit 2
}

TOKEN_FILE=""
PANEL=""
BINARY=""
EXPECTED_SHA=""
STATE_DIR="/var/lib/wisper"
CONFIG="/etc/wisper/node.json"
SKIP_DOCTOR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --token-file) [ $# -ge 2 ] || usage; TOKEN_FILE="$2"; shift 2 ;;
    --panel)      [ $# -ge 2 ] || usage; PANEL="$2";      shift 2 ;;
    --binary)     [ $# -ge 2 ] || usage; BINARY="$2";     shift 2 ;;
    --sha256)     [ $# -ge 2 ] || usage; EXPECTED_SHA="$2"; shift 2 ;;
    --state-dir)  [ $# -ge 2 ] || usage; STATE_DIR="$2";  shift 2 ;;
    --config)     [ $# -ge 2 ] || usage; CONFIG="$2";     shift 2 ;;
    --skip-doctor) SKIP_DOCTOR="yes"; shift ;;
    --token|--token=*)
      echo "install.sh: refusing --token. argv is readable by every user on this" >&2
      echo "            machine through ps. Use --token-file, or - for stdin." >&2
      exit 2 ;;
    -h|--help) usage ;;
    *) echo "install.sh: unknown argument $1" >&2; usage ;;
  esac
done

[ -n "$TOKEN_FILE" ] || usage
[ "$(id -u)" = "0" ] || { echo "install.sh: run this as root." >&2; exit 1; }

# Where this script is, so the default search below works from any directory.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

case "$(uname -m)" in
  x86_64|amd64)  ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *)             ARCH="$(uname -m)" ;;
esac

if [ -z "$BINARY" ]; then
  for candidate in \
    "$SCRIPT_DIR/sasayaki" \
    "$SCRIPT_DIR/sasayaki-linux-$ARCH" \
    "$SCRIPT_DIR/dist/sasayaki-linux-$ARCH" \
    "$SCRIPT_DIR/../sasayaki/dist/sasayaki-linux-$ARCH" \
    "./sasayaki" \
    "./sasayaki-linux-$ARCH"
  do
    if [ -f "$candidate" ]; then BINARY="$candidate"; break; fi
  done
fi

[ -n "$BINARY" ] && [ -f "$BINARY" ] || {
  echo "install.sh: no sasayaki binary found for $ARCH. Build one with" >&2
  echo "            \`make -C sasayaki build-linux\`, or pass --binary <path>." >&2
  exit 1; }

# A checksum beside the binary is what `make build-linux` writes and what an operator
# downloading a release by hand ends up with. Using it automatically means the offline
# path verifies by default rather than only when somebody remembers to ask.
if [ -z "$EXPECTED_SHA" ]; then
  if [ -f "$BINARY.sha256" ]; then
    EXPECTED_SHA=$(cut -d' ' -f1 < "$BINARY.sha256")
  elif [ -f "$SCRIPT_DIR/SHA256SUMS" ]; then
    BIN_NAME=$(basename "$BINARY")
    EXPECTED_SHA=$(grep "  $BIN_NAME\$" "$SCRIPT_DIR/SHA256SUMS" | cut -d' ' -f1 || true)
  elif [ -f "./SHA256SUMS" ]; then
    BIN_NAME=$(basename "$BINARY")
    EXPECTED_SHA=$(grep "  $BIN_NAME\$" "./SHA256SUMS" | cut -d' ' -f1 || true)
  fi
fi

if [ -n "$EXPECTED_SHA" ]; then
  command -v sha256sum >/dev/null 2>&1 || {
    echo "install.sh: sha256sum is not installed, so the binary cannot be verified." >&2
    exit 1; }
  ACTUAL_SHA=$(sha256sum "$BINARY" | cut -d' ' -f1)
  if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    echo "install.sh: $BINARY is not the binary you asked for." >&2
    echo "            expected $EXPECTED_SHA" >&2
    echo "            received $ACTUAL_SHA" >&2
    echo "            Nothing has been installed." >&2
    exit 1
  fi
  echo "Verified $BINARY ($ACTUAL_SHA)"
else
  echo "install.sh: no checksum given and none beside $BINARY, so it is being trusted" >&2
  echo "            as it is. Pass --sha256 with the hash the panel shows." >&2
fi

if [ ! -x "$BINARY" ]; then
  chmod 0755 "$BINARY"
fi

# From here on the binary does the work. It runs the preflight before it writes anything,
# so a machine that fails a required check ends this script with nothing installed on it.
set -- install --token-file "$TOKEN_FILE" --config "$CONFIG" --state-dir "$STATE_DIR"
if [ -n "$PANEL" ]; then
  set -- "$@" --panel "$PANEL"
fi
if [ -n "$SKIP_DOCTOR" ]; then
  set -- "$@" --skip-doctor
fi

exec "$BINARY" "$@"
