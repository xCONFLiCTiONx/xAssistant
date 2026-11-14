#!/usr/bin/env bash
set -Eeuo pipefail

# Install/update xAssistant's API only. This does not change Ollama models,
# firewall rules, Cloudflare, or any existing xServer service.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
SERVER_SOURCE="$ROOT/server"
APP_ROOT="/opt/xassistant-server"
APP_DIR="$APP_ROOT/app"
VENV="$APP_ROOT/venv"
ENV_FILE="/etc/xassistant-server.env"
UNIT_SOURCE="$SERVER_SOURCE/deploy/xassistant-api.service"
UNIT_TARGET="/etc/systemd/system/xassistant-api.service"
API_HOST="192.168.88.50"
OLLAMA_URL="http://127.0.0.1:11434"
MODEL="qwen3.5:4b-q4_K_M"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '\n========== %s ==========\n' "$*"; }

[[ "$EUID" -eq 0 ]] || fail "Run with sudo: sudo bash server/deploy/install.sh"
[[ -f "$SERVER_SOURCE/pyproject.toml" ]] || fail "Run this from the xAssistant repository checkout."
[[ -f "$UNIT_SOURCE" ]] || fail "Missing systemd unit: $UNIT_SOURCE"

info "CHECK SERVER NETWORK"
ip -4 -o addr show | awk '{print $4}' | grep -q "^192\\.168\\.88\\.50/" \
  || fail "192.168.88.50 is not assigned to this server. No files or services were changed."

info "CHECK OLLAMA"
command -v curl >/dev/null || fail "curl is required."
curl --fail --silent --show-error --max-time 5 "$OLLAMA_URL/api/tags" >/dev/null \
  || fail "Ollama is not responding at $OLLAMA_URL."
if ! command -v ollama >/dev/null || ! OLLAMA_HOST="$OLLAMA_URL" ollama list | awk 'NR>1 {print $1}' | grep -Fxq "$MODEL"; then
  fail "Required model $MODEL is not installed. This installer will not download models."
fi

info "CHECK PYTHON"
command -v python3 >/dev/null || fail "python3 is required."
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3,12) else 1)' \
  || fail "Python 3.12 or newer is required."
python3 -m venv --help >/dev/null 2>&1 || fail "Python venv support is missing. Install python3-venv first."

info "CREATE SERVICE ACCOUNT"
if ! id xassistant >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/xassistant --create-home \
    --shell /usr/sbin/nologin xassistant
fi

info "INSTALL APPLICATION"
install -d -o xassistant -g xassistant -m 0750 /var/lib/xassistant
install -d -o root -g root -m 0755 "$APP_DIR"
cp -a "$SERVER_SOURCE/." "$APP_DIR/"
if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --upgrade pip
"$VENV/bin/python" -m pip install "$APP_DIR"

info "CONFIGURE ENVIRONMENT"
if [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<EOF
XASSISTANT_ENVIRONMENT=production
XASSISTANT_HOST=$API_HOST
XASSISTANT_PORT=5081
XASSISTANT_OLLAMA_URL=$OLLAMA_URL
XASSISTANT_OLLAMA_MODEL=$MODEL
XASSISTANT_OLLAMA_TIMEOUT_SECONDS=60
XASSISTANT_OLLAMA_KEEP_ALIVE=5m
XASSISTANT_TIMEZONE=America/Chicago
XASSISTANT_DATABASE_PATH=/var/lib/xassistant/xassistant.sqlite3
EOF
  chmod 0600 "$ENV_FILE"
  chown root:root "$ENV_FILE"
else
  printf 'Preserving existing %s\n' "$ENV_FILE"
fi

info "INSTALL SYSTEMD UNIT"
install -o root -g root -m 0644 "$UNIT_SOURCE" "$UNIT_TARGET"
systemctl daemon-reload
systemctl enable xassistant-api.service
systemctl restart xassistant-api.service

info "VERIFY API"
for attempt in $(seq 1 15); do
  if curl --fail --silent --show-error --max-time 2 \
    "http://$API_HOST:5081/health"; then
    printf '\n\nAPI is responding.\n'
    break
  fi
  if [[ "$attempt" -eq 15 ]]; then
    systemctl --no-pager --full status xassistant-api.service || true
    journalctl -u xassistant-api.service -n 60 --no-pager || true
    fail "API health check failed."
  fi
  sleep 2
done

info "SERVICE STATUS"
systemctl is-active xassistant-api.service
printf '\nInstalled xAssistant API at http://%s:5081\n' "$API_HOST"
printf 'Environment: %s\n' "$ENV_FILE"
printf 'Database: /var/lib/xassistant/xassistant.sqlite3\n'
printf '\nFirewall was not changed. Allow TCP 5081 from 192.168.88.0/24 only after reviewing your current rules.\n'
