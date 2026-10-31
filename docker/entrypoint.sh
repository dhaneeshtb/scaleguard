#!/bin/sh
# Container entrypoint: turns environment variables into what ScaleGuard reads, then starts it.
# Everything is optional except SCALEGUARD_ADMIN_PASSWORD (there is deliberately no default login).
set -eu

fail() { echo "[scaleguard] $*" >&2; exit 1; }

[ -n "${SCALEGUARD_ADMIN_PASSWORD:-}" ] || fail "SCALEGUARD_ADMIN_PASSWORD is not set. Set it (see .env.example); there is no default password."
ADMIN_USER="${SCALEGUARD_ADMIN_USER:-scaleguard}"

# data directory (volume): SQLite/ledger, certificates, licence, DuckDB cache and analytics live here
DATA="${SCALEGUARD_DATA:-/data}"
CONF="$DATA/.scaleguard"
mkdir -p "$CONF"
# DuckDB (and everything else) must sit on a mounted volume, or it is lost when the container is removed
if [ -r /proc/self/mountinfo ] && ! grep -q " $DATA " /proc/self/mountinfo; then
  echo "[scaleguard] WARNING: $DATA is not a mounted volume; configuration, certificates and DuckDB files will be lost when the container is removed. Mount a volume at $DATA (docker run -v scaleguard-data:$DATA)." >&2
fi

# --- PostgreSQL (optional): SCALEGUARD_DB_URL switches the configuration store from SQLite to Postgres ------------------
MARK="# written by the container entrypoint from SCALEGUARD_DB_* (edit the environment, not this file)"
if [ -n "${SCALEGUARD_DB_URL:-}" ]; then
  esc() { printf '%s' "$1" | sed 's/\\/\\\\/g'; }          # .properties values: backslash is the escape character
  umask 077
  {
    echo "$MARK"
    printf 'driver=%s\n' "$(esc "${SCALEGUARD_DB_DRIVER:-org.postgresql.Driver}")"
    printf 'url=%s\n' "$(esc "$SCALEGUARD_DB_URL")"
    printf 'username=%s\n' "$(esc "${SCALEGUARD_DB_USER:-}")"
    printf 'password=%s\n' "$(esc "${SCALEGUARD_DB_PASSWORD:-}")"
  } > "$CONF/postgres.properties"
  echo "[scaleguard] configuration store: PostgreSQL ($SCALEGUARD_DB_URL)"
else
  # only remove a file this entrypoint wrote earlier, so going back to SQLite is just removing the variable
  if [ -f "$CONF/postgres.properties" ] && head -n1 "$CONF/postgres.properties" | grep -qF "$MARK"; then rm -f "$CONF/postgres.properties"; fi
  echo "[scaleguard] configuration store: SQLite ($CONF)"
fi

# --- DuckDB: response cache and analytics ----------------------------------------------------------------------------
CACHE_BACKEND="${SCALEGUARD_CACHE_BACKEND:-duckdb}"
if [ "$CACHE_BACKEND" = "duckdb" ]; then
  CACHE_ARGS="-Dcache.backend=duckdb -Dduckdb.cache.path=${SCALEGUARD_CACHE_PATH:-$DATA/cache.duckdb} -Dduckdb.cache.l1.size=${SCALEGUARD_L1_CACHE_SIZE:-2000}"
else
  CACHE_ARGS="-Dcache.backend=memory"
fi
ANALYTICS_ARGS="-Dscaleguard.analytics.path=${SCALEGUARD_ANALYTICS_PATH:-$DATA/analytics.duckdb}"
[ "$CACHE_BACKEND" = "duckdb" ] && echo "[scaleguard] DuckDB cache: ${SCALEGUARD_CACHE_PATH:-$DATA/cache.duckdb}"
echo "[scaleguard] DuckDB analytics: ${SCALEGUARD_ANALYTICS_PATH:-$DATA/analytics.duckdb}"
[ "${SCALEGUARD_ANALYTICS:-true}" = "false" ] && ANALYTICS_ARGS="-Dscaleguard.analytics=false"

# the admin login goes through the environment, not the command line (visible in `ps`)
export adminUser="$ADMIN_USER" adminPassword="$SCALEGUARD_ADMIN_PASSWORD"
export SCALEGUARD_ADMIN_USER="$ADMIN_USER"

# shellcheck disable=SC2086  # the *_OPTS / *_ARGS variables are meant to be split into words
exec java \
  ${SCALEGUARD_JAVA_OPTS:--XX:+UseParallelGC} \
  -XX:InitialRAMPercentage="${SCALEGUARD_HEAP_PERCENT:-50}" -XX:MaxRAMPercentage="${SCALEGUARD_HEAP_PERCENT:-50}" \
  -Djava.security.egd=file:///dev/./urandom \
  -Dio.netty.buffer.checkAccessible=false -Dio.netty.buffer.checkBounds=false \
  -Duser.home="$DATA" \
  -Dport="${SCALEGUARD_HTTP_PORT:-80}" -Dsport="${SCALEGUARD_HTTPS_PORT:-443}" \
  -Dscaleguard.rateLimit="${SCALEGUARD_RATE_LIMIT:-10000}" \
  ${CACHE_ARGS} ${ANALYTICS_ARGS} \
  ${SCALEGUARD_EXTRA_OPTS:-} \
  -jar scaleguard.jar
