#!/bin/sh
# =============================================================================
# ScaleGuard — start / stop / status script
#
# Usage:
#   ./run.sh             # start (default)
#   ./run.sh stop        # stop a running instance
#   ./run.sh status      # show whether the server is running
#
# Override any setting by exporting the variable before calling this script:
#
#   SCALEGUARD_JAR        Path to the fat JAR  (default: target/scaleguard-1.0-SNAPSHOT.jar)
#   SCALEGUARD_DATA       Runtime data dir     (default: ~/.scaleguard)
#   SCALEGUARD_HEAP       JVM heap, e.g. 2g    (default: 1g)
#   SCALEGUARD_HTTP_PORT  HTTP listen port     (default: 80)
#   SCALEGUARD_HTTPS_PORT HTTPS listen port    (default: 443)
#   SCALEGUARD_ADMIN_USER Admin API username   (default: scaleguard)
#   SCALEGUARD_ADMIN_PASS Admin API password
#
#   -- Cache --
#   SCALEGUARD_CACHE_BACKEND  duckdb | memory  (default: duckdb)
#   SCALEGUARD_CACHE_PATH     DuckDB file path (default: ~/.scaleguard/cache.duckdb)
#   SCALEGUARD_L1_CACHE_SIZE  In-memory L1 entries (default: 2000)
#
#   -- Performance --
#   SCALEGUARD_MAX_BODY_BYTES  Max HTTP body in bytes  (default: 10485760 = 10 MB)
#   SCALEGUARD_RATE_LIMIT      Req/min per client IP   (default: 10000)
#   SCALEGUARD_POOL_MAX_CONNS  Outbound pool per upstream (default: 512)
#
# Native transport is auto-detected — no config needed:
#   Linux  → Epoll + SO_REUSEPORT  |  macOS → KQueue  |  Other → NIO
#
# DuckDB cache is fully embedded — no external server required.
# =============================================================================

set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── defaults ──────────────────────────────────────────────────────────────────
SCALEGUARD_JAR="${SCALEGUARD_JAR:-${SCRIPT_DIR}/scaleguard.jar}"
SCALEGUARD_DATA="${SCALEGUARD_DATA:-${HOME}/.scaleguard}"
SCALEGUARD_HEAP="${SCALEGUARD_HEAP:-1g}"
SCALEGUARD_HTTP_PORT="${SCALEGUARD_HTTP_PORT:-80}"
SCALEGUARD_HTTPS_PORT="${SCALEGUARD_HTTPS_PORT:-443}"
SCALEGUARD_ADMIN_USER="${SCALEGUARD_ADMIN_USER:-scaleguard}"
SCALEGUARD_ADMIN_PASS="${SCALEGUARD_ADMIN_PASS:-Scaleguard123$}"
SCALEGUARD_PID="${SCRIPT_DIR}/scaleguard.pid"
SCALEGUARD_LOG="${SCRIPT_DIR}/scaleguard.log"

# cache
SCALEGUARD_CACHE_BACKEND="${SCALEGUARD_CACHE_BACKEND:-duckdb}"
SCALEGUARD_CACHE_PATH="${SCALEGUARD_CACHE_PATH:-${SCALEGUARD_DATA}/cache.duckdb}"
SCALEGUARD_L1_CACHE_SIZE="${SCALEGUARD_L1_CACHE_SIZE:-2000}"

# performance
SCALEGUARD_MAX_BODY_BYTES="${SCALEGUARD_MAX_BODY_BYTES:-10485760}"
SCALEGUARD_RATE_LIMIT="${SCALEGUARD_RATE_LIMIT:-10000}"
SCALEGUARD_POOL_MAX_CONNS="${SCALEGUARD_POOL_MAX_CONNS:-512}"
# ─────────────────────────────────────────────────────────────────────────────

info() { echo "[scaleguard] $*"; }
die()  { echo "[scaleguard] ERROR: $*" >&2; exit 1; }

# ── sub-commands ──────────────────────────────────────────────────────────────
CMD="${1:-start}"

if [ "${CMD}" = "stop" ]; then
  if [ -f "${SCALEGUARD_PID}" ]; then
    OLD_PID=$(cat "${SCALEGUARD_PID}" 2>/dev/null || true)
    if [ -n "${OLD_PID}" ] && kill -0 "${OLD_PID}" 2>/dev/null; then
      info "Stopping ScaleGuard (PID ${OLD_PID})..."
      kill "${OLD_PID}" 2>/dev/null || true
      sleep 2
      kill -0 "${OLD_PID}" 2>/dev/null && kill -9 "${OLD_PID}" 2>/dev/null || true
      info "Stopped."
    else
      info "No running process found for PID ${OLD_PID}."
    fi
    rm -f "${SCALEGUARD_PID}"
  else
    info "No PID file found — server may not be running."
  fi
  exit 0
fi

if [ "${CMD}" = "status" ]; then
  if [ -f "${SCALEGUARD_PID}" ]; then
    OLD_PID=$(cat "${SCALEGUARD_PID}" 2>/dev/null || true)
    if [ -n "${OLD_PID}" ] && kill -0 "${OLD_PID}" 2>/dev/null; then
      info "ScaleGuard is RUNNING (PID ${OLD_PID})"
    else
      info "ScaleGuard is NOT running (stale PID file)"
    fi
  else
    info "ScaleGuard is NOT running"
  fi
  exit 0
fi

# ── pre-flight checks ─────────────────────────────────────────────────────────
[ -f "${SCALEGUARD_JAR}" ] || die "JAR not found: ${SCALEGUARD_JAR}"
command -v java >/dev/null 2>&1 || die "java not found on PATH. Set JAVA_HOME or install a JDK."

# ── directories ───────────────────────────────────────────────────────────────
mkdir -p "${SCALEGUARD_DATA}"

if [ "${SCALEGUARD_CACHE_BACKEND}" = "duckdb" ]; then
  mkdir -p "$(dirname "${SCALEGUARD_CACHE_PATH}")"
  info "DuckDB cache: ${SCALEGUARD_CACHE_PATH}  (L1 entries: ${SCALEGUARD_L1_CACHE_SIZE})"
  CACHE_JVM_ARGS="-Dcache.backend=duckdb \
-Dduckdb.cache.path=${SCALEGUARD_CACHE_PATH} \
-Dduckdb.cache.l1.size=${SCALEGUARD_L1_CACHE_SIZE}"
else
  info "In-memory cache enabled"
  CACHE_JVM_ARGS="-Dcache.backend=memory"
fi

# ── stop any previously running instance ─────────────────────────────────────
if [ -f "${SCALEGUARD_PID}" ]; then
  OLD_PID=$(cat "${SCALEGUARD_PID}" 2>/dev/null || true)
  if [ -n "${OLD_PID}" ] && kill -0 "${OLD_PID}" 2>/dev/null; then
    info "Stopping existing instance (PID ${OLD_PID})..."
    kill "${OLD_PID}" 2>/dev/null || true
    sleep 2
    kill -0 "${OLD_PID}" 2>/dev/null && kill -9 "${OLD_PID}" 2>/dev/null || true
  fi
  rm -f "${SCALEGUARD_PID}"
fi

# ── start ─────────────────────────────────────────────────────────────────────
info "Starting ScaleGuard..."
info "  JAR  : ${SCALEGUARD_JAR}"
info "  Heap : ${SCALEGUARD_HEAP}  |  HTTP: ${SCALEGUARD_HTTP_PORT}  HTTPS: ${SCALEGUARD_HTTPS_PORT}"
info "  Log  : ${SCALEGUARD_LOG}"

# shellcheck disable=SC2086
nohup java \
  -Xmx"${SCALEGUARD_HEAP}" \
  -Xms"${SCALEGUARD_HEAP}" \
  -Dport="${SCALEGUARD_HTTP_PORT}" \
  -Dsport="${SCALEGUARD_HTTPS_PORT}" \
  -DadminUser="${SCALEGUARD_ADMIN_USER}" \
  -DadminPassword="${SCALEGUARD_ADMIN_PASS}" \
  -Dscaleguard.maxBodyBytes="${SCALEGUARD_MAX_BODY_BYTES}" \
  -Dscaleguard.rateLimit="${SCALEGUARD_RATE_LIMIT}" \
  -Dscaleguard.pool.maxConns="${SCALEGUARD_POOL_MAX_CONNS}" \
  ${CACHE_JVM_ARGS} \
  -jar "${SCALEGUARD_JAR}" \
  >>"${SCALEGUARD_LOG}" 2>&1 &

echo $! >"${SCALEGUARD_PID}"
info "Started — PID $(cat "${SCALEGUARD_PID}")"
info "Tail logs:  tail -f ${SCALEGUARD_LOG}"
