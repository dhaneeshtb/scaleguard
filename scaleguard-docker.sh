#!/usr/bin/env bash
# One-command ScaleGuard in Docker.
#   ./scaleguard-docker.sh                 build and start (creates .env with a generated admin password on first run)
#   ./scaleguard-docker.sh up --postgres   also run PostgreSQL and keep the configuration there
#   ./scaleguard-docker.sh up --dns        also publish port 53 and turn the DNS server on
#   ./scaleguard-docker.sh logs | status | stop | down | reset
# Choices are remembered in .env, so plain `docker compose up -d` keeps working afterwards.
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '==> %s\n' "$*"; }
die() { printf 'scaleguard-docker: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "Docker is not installed (https://docs.docker.com/get-docker/)"
docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop / the docker service and retry."
docker compose version >/dev/null 2>&1 || die "'docker compose' (v2) is required: https://docs.docker.com/compose/install/"

# get KEY from .env (empty if unset)
getenv() { [ -f .env ] && sed -n "s/^$1=//p" .env | tail -n1 || true; }
# set KEY=VALUE in .env, replacing an existing line
setenv() {
  local tmp; tmp="$(mktemp)"
  if [ -f .env ] && grep -q "^$1=" .env; then
    awk -v k="$1" -v v="$2" 'BEGIN{FS=OFS="="} $1==k {print k "=" v; next} {print}' .env > "$tmp"
  else
    { [ -f .env ] && cat .env; printf '%s=%s\n' "$1" "$2"; } > "$tmp"
  fi
  cat "$tmp" > .env; rm -f "$tmp"; chmod 600 .env
}
# 24 random letters/digits (cut reads all of its input, so no pipe is closed early under `set -o pipefail`)
secret() { dd if=/dev/urandom bs=512 count=1 2>/dev/null | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-24; }

cmd="${1:-up}"; [ $# -gt 0 ] && shift
postgres=no; dns=no
for a in "$@"; do
  case "$a" in
    --postgres) postgres=yes ;;
    --dns) dns=yes ;;
    -y|--yes) ;;
    *) die "unknown option $a" ;;
  esac
done

case "$cmd" in
  up|start)
    if [ ! -f .env ]; then
      cp .env.example .env; chmod 600 .env
      say "Created .env"
    fi
    if [ -z "$(getenv SCALEGUARD_ADMIN_PASSWORD)" ]; then
      pw="$(secret)"; setenv SCALEGUARD_ADMIN_PASSWORD "$pw"
      say "Generated an admin password: $pw   (saved in .env)"
    fi
    files="docker-compose.yml"; profiles=""
    if [ "$postgres" = yes ]; then
      [ -n "$(getenv POSTGRES_PASSWORD)" ] || setenv POSTGRES_PASSWORD "$(secret)"
      db="$(getenv POSTGRES_DB)"; db="${db:-scaleguard}"
      dbuser="$(getenv POSTGRES_USER)"; dbuser="${dbuser:-scaleguard}"
      setenv SCALEGUARD_DB_URL "jdbc:postgresql://postgres:5432/$db"
      setenv SCALEGUARD_DB_USER "$dbuser"
      setenv SCALEGUARD_DB_PASSWORD "$(getenv POSTGRES_PASSWORD)"
      profiles="postgres"
    elif [ "$(getenv COMPOSE_PROFILES)" = postgres ]; then
      profiles="postgres"            # remembered from an earlier --postgres run
    fi
    if [ "$dns" = yes ]; then setenv SCALEGUARD_DNS true; fi
    [ "$(getenv SCALEGUARD_DNS)" = true ] && files="$files:docker-compose.dns.yml"
    setenv COMPOSE_FILE "$files"
    setenv COMPOSE_PROFILES "$profiles"

    say "Building and starting ScaleGuard (the first build downloads dependencies and can take several minutes)"
    docker compose up -d --build
    say "Waiting for it to become healthy"
    for _ in $(seq 1 90); do
      state="$(docker inspect --format '{{.State.Health.Status}}' scaleguard 2>/dev/null || echo starting)"
      [ "$state" = healthy ] && break
      [ "$(docker inspect --format '{{.State.Running}}' scaleguard 2>/dev/null || echo false)" = true ] || { docker compose logs --tail 30 scaleguard; die "the container stopped; see the log above"; }
      sleep 2
    done
    [ "$state" = healthy ] || { docker compose logs --tail 30 scaleguard; die "not healthy after 3 minutes; see the log above"; }
    port="$(getenv SCALEGUARD_HTTP_PUBLISH)"; port="${port:-80}"; [ "$port" = 80 ] && hostport="" || hostport=":$port"
    echo
    say "ScaleGuard is up"
    echo "    Admin UI : http://localhost${hostport}/adminui/"
    echo "    Login    : $(getenv SCALEGUARD_ADMIN_USER) / (the password in .env)"
    dd="$(getenv SCALEGUARD_DATA_DIR)"; dd="${dd:-scaleguard-data}"
    echo "    Store    : $([ -n "$(getenv SCALEGUARD_DB_URL)" ] && echo PostgreSQL || echo SQLite) + DuckDB cache and analytics in $dd (mounted at /data)"
    if [ "$(getenv SCALEGUARD_DNS)" = true ]; then dp="$(getenv SCALEGUARD_DNS_PUBLISH)"; echo "    DNS      : udp/tcp port ${dp:-53}"; fi
    echo "    Logs     : ./scaleguard-docker.sh logs      Stop: ./scaleguard-docker.sh down"
    ;;
  logs) docker compose logs -f --tail 100 ;;
  status|ps) docker compose ps ;;
  stop) docker compose stop ;;
  down) docker compose down; say "Stopped. Data is kept (run '$0 reset' to delete it)." ;;
  reset)
    printf 'This deletes ALL ScaleGuard data (configuration, certificates, cache, analytics, and PostgreSQL data). Type yes: '
    read -r answer; [ "$answer" = yes ] || die "cancelled"
    docker compose down --volumes
    ;;
  *) die "unknown command '$cmd' (up, logs, status, stop, down, reset)" ;;
esac
