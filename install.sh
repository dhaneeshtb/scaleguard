#!/bin/sh
# ScaleGuard installer for macOS and Linux (no Docker).
#
#   ./install.sh                  from a checkout: builds the jar and the admin UI, installs, starts
#   curl -fsSL https://raw.githubusercontent.com/dhaneeshtb/scaleguard/main/install.sh | sh
#                                 from anywhere: downloads the latest release (jar + admin UI) and installs it
#
# Options:  --prefix DIR   where the program goes        (default ~/.local/share/scaleguard)
#           --bin DIR      where the `scaleguard` command goes (default ~/.local/bin)
#           --port N --https-port N   (defaults 8080/8443; 80/443 when run as root)
#           --user NAME    admin user (default scaleguard)
#           --service      also register it to start at login/boot (systemd on Linux, launchd on macOS)
#           --no-start     install only
#           --from-source  build even when not run from a checkout (needs git, Maven, Java 21)
#           --uninstall [--purge]   remove it (--purge also deletes ~/.scaleguard: settings, login and data)
# Everything ScaleGuard keeps is in ~/.scaleguard (set SCALEGUARD_HOME to use <dir>/.scaleguard instead).
# Environment: SCALEGUARD_URL (tarball location), SCALEGUARD_ADMIN_PASSWORD (otherwise asked, or generated).
set -eu

REPO="dhaneeshtb/scaleguard"
PREFIX="${SCALEGUARD_PREFIX:-$HOME/.local/share/scaleguard}"
BIN_DIR="${SCALEGUARD_BIN:-$HOME/.local/bin}"
# one folder for everything: the settings file (scaleguard.env), DuckDB cache + analytics, SQLite store, certificates, log, pid
HOME_DIR="${SCALEGUARD_HOME:-$HOME}"
DATA="$HOME_DIR/.scaleguard"
CONF_DIR="$DATA"
URL="${SCALEGUARD_URL:-https://github.com/$REPO/releases/latest/download/scaleguard.tar.gz}"
ADMIN_USER="scaleguard"; HTTP_PORT=""; HTTPS_PORT=""; SERVICE=no; START=yes; FROM_SOURCE=no; UNINSTALL=no; PURGE=no

say()  { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'scaleguard install: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift ;;
    --bin) BIN_DIR="$2"; shift ;;
    --port) HTTP_PORT="$2"; shift ;;
    --https-port) HTTPS_PORT="$2"; shift ;;
    --user) ADMIN_USER="$2"; shift ;;
    --service) SERVICE=yes ;;
    --no-start) START=no ;;
    --from-source) FROM_SOURCE=yes ;;
    --uninstall) UNINSTALL=yes ;;
    --purge) PURGE=yes ;;
    -h|--help) sed -n '2,17p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (try --help)" ;;
  esac
  shift
done

OS="$(uname -s)"
case "$OS" in Linux|Darwin) ;; *) die "this installer supports macOS and Linux (found $OS); on Windows or elsewhere use Docker: ./scaleguard-docker.sh" ;; esac
APP="$PREFIX/app"
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd || echo .)"
UNIT_LINUX="$HOME/.config/systemd/user/scaleguard.service"
PLIST="$HOME/Library/LaunchAgents/io.scaleguard.plist"

# ------------------------------------------------------------------------------------------------ uninstall
if [ "$UNINSTALL" = yes ]; then
  [ -x "$BIN_DIR/scaleguard" ] && "$BIN_DIR/scaleguard" stop 2>/dev/null || true
  if [ "$OS" = Linux ] && [ -f "$UNIT_LINUX" ]; then systemctl --user disable --now scaleguard 2>/dev/null || true; rm -f "$UNIT_LINUX"; fi
  if [ "$OS" = Darwin ] && [ -f "$PLIST" ]; then launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true; rm -f "$PLIST"; fi
  rm -rf "$APP"; rmdir "$PREFIX" 2>/dev/null || true; rm -f "$BIN_DIR/scaleguard"
  if [ "$PURGE" = yes ]; then rm -rf "$DATA"; say "Removed ScaleGuard and everything in $DATA"; else say "Removed ScaleGuard (settings, login and data in $DATA are kept; add --purge to delete them)"; fi
  exit 0
fi

# ------------------------------------------------------------------------------------------------ java
java_major() { java -version 2>&1 | awk -F'[".]' '/version/ { print ($2 == "1") ? $3 : $2; exit }'; }
java_help() {
  echo "  macOS:          brew install openjdk@21        (then: sudo ln -sfn \$(brew --prefix openjdk@21)/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk)"
  echo "  Debian/Ubuntu:  sudo apt install openjdk-21-jre-headless"
  echo "  Fedora/RHEL:    sudo dnf install java-21-openjdk-headless"
  echo "  Anywhere:       https://adoptium.net"
}
JAVA_OK=yes
if ! command -v java >/dev/null 2>&1; then
  JAVA_OK=no; warn "Java was not found. ScaleGuard needs Java 21 or newer. Install it, for example:"; java_help >&2
elif [ "$(java_major)" -lt 21 ] 2>/dev/null; then
  JAVA_OK=no; warn "Java $(java_major) found, but ScaleGuard needs Java 21 or newer. Install a newer one, for example:"; java_help >&2
else
  say "Java $(java_major) found"
fi

# ------------------------------------------------------------------------------------------------ get the program
mkdir -p "$PREFIX" "$BIN_DIR" "$DATA"
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

build_from() {                      # $1 = checkout directory
  src="$1"
  command -v mvn >/dev/null 2>&1 || die "Maven is needed to build from source (https://maven.apache.org). Or install a release: curl -fsSL https://raw.githubusercontent.com/$REPO/main/install.sh | sh"
  [ "$JAVA_OK" = yes ] || die "building needs Java 21 (see above)"
  jar="$(ls "$src"/target/scaleguard-*-SNAPSHOT.jar 2>/dev/null | head -n1 || true)"
  if [ -z "$jar" ] || [ -n "$(find "$src/src" "$src/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]; then
    say "Building the jar (a few minutes the first time)"
    (cd "$src" && mvn -q -DskipTests package) || die "the Maven build failed"
    jar="$(ls "$src"/target/scaleguard-*-SNAPSHOT.jar | head -n1)"
  else
    say "Jar is up to date: $jar"
  fi
  if [ -f "$src/adminui/index.html" ] && [ -z "$(find "$src/admin-ui/src" "$src/admin-ui/package.json" -newer "$src/adminui/index.html" -print -quit 2>/dev/null)" ]; then
    say "Admin UI is up to date"
  elif command -v npm >/dev/null 2>&1; then
    say "Building the admin UI"
    (cd "$src/admin-ui" && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && sh build.sh) || warn "the admin UI build failed; the server works, /adminui will be missing"
  else
    warn "npm not found: the admin UI is not built (install Node.js and re-run, or use a release)"
  fi
  rm -rf "$APP.new"; mkdir -p "$APP.new"
  cp "$jar" "$APP.new/scaleguard.jar"
  [ -d "$src/adminui" ] && cp -R "$src/adminui" "$APP.new/adminui"
}

if [ "$FROM_SOURCE" = no ] && [ -f "$SCRIPT_DIR/pom.xml" ] && [ -d "$SCRIPT_DIR/src" ]; then
  say "Installing from this checkout ($SCRIPT_DIR)"
  build_from "$SCRIPT_DIR"
elif [ "$FROM_SOURCE" = yes ]; then
  command -v git >/dev/null 2>&1 || die "git is needed for --from-source"
  tmp="$(mktemp -d)"; say "Cloning $REPO"; git clone -q --depth 1 "https://github.com/$REPO.git" "$tmp/src" || die "clone failed"
  build_from "$tmp/src"
else
  command -v curl >/dev/null 2>&1 || die "curl is required"
  say "Downloading $URL"
  tmp="$(mktemp -d)"
  if [ -t 2 ]; then curl -fL --connect-timeout 15 --retry 3 --progress-bar "$URL" -o "$tmp/pkg.tar.gz" || fetch_failed=1
  else curl -fsSL --connect-timeout 15 --retry 3 "$URL" -o "$tmp/pkg.tar.gz" || fetch_failed=1; fi
  if [ "${fetch_failed:-0}" = 1 ]; then
    die "could not download a release. If none is published yet, install from source:  git clone https://github.com/$REPO.git && cd scaleguard && ./install.sh"
  fi
  if sum="$(curl -fsSL --connect-timeout 15 "$URL.sha256" 2>/dev/null)"; then
    [ "$(printf '%s' "$sum" | awk '{print $1}')" = "$(sha256_of "$tmp/pkg.tar.gz")" ] || die "checksum mismatch, nothing installed"
    say "Checksum OK"
  else
    warn "no checksum published; skipping verification"
  fi
  rm -rf "$APP.new"; mkdir -p "$APP.new"
  tar xzf "$tmp/pkg.tar.gz" -C "$APP.new" --strip-components=1 || die "could not unpack the download"
  [ -f "$APP.new/scaleguard.jar" ] || die "the download does not contain scaleguard.jar"
fi
[ -x "$BIN_DIR/scaleguard" ] && "$BIN_DIR/scaleguard" stop >/dev/null 2>&1 || true      # stop the old version before replacing it
rm -rf "$APP.old"; [ -d "$APP" ] && mv "$APP" "$APP.old"
mv "$APP.new" "$APP"; rm -rf "$APP.old"
say "Installed to $APP"

# ------------------------------------------------------------------------------------------------ settings (kept across upgrades)
ENV_FILE="$CONF_DIR/scaleguard.env"
getv() { [ -f "$ENV_FILE" ] && sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1 || true; }
if [ ! -f "$ENV_FILE" ]; then
  pw="${SCALEGUARD_ADMIN_PASSWORD:-}"
  if [ -z "$pw" ] && [ -t 0 ]; then
    printf 'Choose an admin password (Enter to generate one): '; stty -echo 2>/dev/null || true; read -r pw || pw=""; stty echo 2>/dev/null || true; echo
  fi
  if [ -z "$pw" ]; then
    pw="$(dd if=/dev/urandom bs=512 count=1 2>/dev/null | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-24)"
    say "Generated an admin password: $pw   (saved in $ENV_FILE)"
  fi
  if [ "$(id -u)" = 0 ]; then def_http=80; def_https=443; else def_http=8080; def_https=8443; fi
  umask 077
  cat > "$ENV_FILE" <<EOF
# ScaleGuard settings, read by the \`scaleguard\` command. Change them and run: scaleguard restart
SCALEGUARD_ADMIN_USER=$ADMIN_USER
SCALEGUARD_ADMIN_PASSWORD=$pw
SCALEGUARD_HTTP_PORT=${HTTP_PORT:-$def_http}
SCALEGUARD_HTTPS_PORT=${HTTPS_PORT:-$def_https}
# The DuckDB cache (cache.duckdb) and analytics (analytics.duckdb), the SQLite store and certificates are in this same folder.
SCALEGUARD_CACHE_BACKEND=duckdb
# extra JVM flags, e.g. -Dscaleguard.http3=false
SCALEGUARD_EXTRA_OPTS=
EOF
  chmod 600 "$ENV_FILE"
else
  say "Keeping your settings in $ENV_FILE"
  [ -n "$HTTP_PORT" ] && sed "s/^SCALEGUARD_HTTP_PORT=.*/SCALEGUARD_HTTP_PORT=$HTTP_PORT/" "$ENV_FILE" > "$ENV_FILE.tmp" && cat "$ENV_FILE.tmp" > "$ENV_FILE" && rm -f "$ENV_FILE.tmp"
  [ -n "$HTTPS_PORT" ] && sed "s/^SCALEGUARD_HTTPS_PORT=.*/SCALEGUARD_HTTPS_PORT=$HTTPS_PORT/" "$ENV_FILE" > "$ENV_FILE.tmp" && cat "$ENV_FILE.tmp" > "$ENV_FILE" && rm -f "$ENV_FILE.tmp"
fi

# ------------------------------------------------------------------------------------------------ the `scaleguard` command
cat > "$BIN_DIR/scaleguard" <<EOF
#!/bin/sh
# ScaleGuard control: scaleguard start | stop | restart | status | logs | run | password | version
set -eu
APP="$APP"
ENV_FILE="$ENV_FILE"
[ -f "\$ENV_FILE" ] || { echo "scaleguard: settings not found (\$ENV_FILE). Re-run the installer." >&2; exit 1; }
set -a; . "\$ENV_FILE"; set +a
HOME_DIR="$HOME_DIR"
DATA="$DATA"
PID="\$DATA/scaleguard.pid"; LOG="\$DATA/scaleguard.log"
mkdir -p "\$DATA"

is_running() { [ -f "\$PID" ] && kill -0 "\$(cat "\$PID")" 2>/dev/null; }

java_cmd() {
  command -v java >/dev/null 2>&1 || { echo "scaleguard: Java 21 or newer is required but was not found (macOS: brew install openjdk@21, Debian/Ubuntu: sudo apt install openjdk-21-jre-headless, Fedora: sudo dnf install java-21-openjdk-headless, or https://adoptium.net)" >&2; exit 1; }
  export adminUser="\$SCALEGUARD_ADMIN_USER" adminPassword="\$SCALEGUARD_ADMIN_PASSWORD"
  cd "\$APP"                                   # the admin UI is served from ./adminui
  if [ "\${SCALEGUARD_CACHE_BACKEND:-duckdb}" = duckdb ]; then CACHE="-Dcache.backend=duckdb -Dduckdb.cache.path=\$DATA/cache.duckdb"; else CACHE="-Dcache.backend=memory"; fi
  # shellcheck disable=SC2086
  exec java \${SCALEGUARD_JAVA_OPTS:--XX:+UseParallelGC} -XX:InitialRAMPercentage=50 -XX:MaxRAMPercentage=50 \\
    -Dio.netty.buffer.checkAccessible=false -Dio.netty.buffer.checkBounds=false \\
    -Duser.home="\$HOME_DIR" -Dport="\$SCALEGUARD_HTTP_PORT" -Dsport="\$SCALEGUARD_HTTPS_PORT" \\
    \$CACHE \${SCALEGUARD_EXTRA_OPTS:-} -jar "\$APP/scaleguard.jar"
}

case "\${1:-status}" in
  run) java_cmd ;;                                  # foreground (used by the service)
  start)
    if is_running; then echo "ScaleGuard is already running (PID \$(cat "\$PID"))"; exit 0; fi
    ( java_cmd >>"\$LOG" 2>&1 & echo \$! >"\$PID" )
    sleep 3
    if is_running; then echo "ScaleGuard started (PID \$(cat "\$PID")). Admin UI: http://localhost:\$SCALEGUARD_HTTP_PORT/adminui/   Log: \$LOG"
    else echo "ScaleGuard did not start; last log lines:" >&2; tail -n 15 "\$LOG" >&2; rm -f "\$PID"; exit 1; fi ;;
  stop)
    if is_running; then kill "\$(cat "\$PID")"; for _ in 1 2 3 4 5 6 7 8 9 10; do is_running || break; sleep 1; done; is_running && kill -9 "\$(cat "\$PID")" || true; rm -f "\$PID"; echo "ScaleGuard stopped"
    else rm -f "\$PID"; echo "ScaleGuard is not running"; fi ;;
  restart) "\$0" stop; "\$0" start ;;
  status) if is_running; then echo "ScaleGuard is running (PID \$(cat "\$PID")), http://localhost:\$SCALEGUARD_HTTP_PORT/adminui/"; else echo "ScaleGuard is not running"; exit 3; fi ;;
  logs) tail -n 100 -f "\$LOG" ;;
  password) echo "user: \$SCALEGUARD_ADMIN_USER"; echo "password: \$SCALEGUARD_ADMIN_PASSWORD" ;;
  version) ls -l "\$APP/scaleguard.jar" ;;
  *) echo "usage: scaleguard start|stop|restart|status|logs|run|password" >&2; exit 2 ;;
esac
EOF
chmod +x "$BIN_DIR/scaleguard"

# ------------------------------------------------------------------------------------------------ optional service
if [ "$SERVICE" = yes ]; then
  if [ "$OS" = Linux ]; then
    command -v systemctl >/dev/null 2>&1 || die "--service needs systemd on Linux"
    mkdir -p "$(dirname "$UNIT_LINUX")"
    cat > "$UNIT_LINUX" <<EOF
[Unit]
Description=ScaleGuard
After=network-online.target

[Service]
ExecStart=$BIN_DIR/scaleguard run
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload && systemctl --user enable --now scaleguard && say "Service enabled (systemd --user). To start at boot without logging in: sudo loginctl enable-linger $USER"
    START=no
  else
    mkdir -p "$(dirname "$PLIST")"
    cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.scaleguard</string>
  <key>ProgramArguments</key><array><string>$BIN_DIR/scaleguard</string><string>run</string></array>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/.scaleguard/scaleguard.log</string>
  <key>StandardErrorPath</key><string>$HOME/.scaleguard/scaleguard.log</string>
</dict></plist>
EOF
    launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$PLIST" && say "Service enabled (launchd): starts at login"
    START=no
  fi
fi

# ------------------------------------------------------------------------------------------------ start and summarise
if [ "$START" = yes ] && [ "$JAVA_OK" = yes ]; then "$BIN_DIR/scaleguard" start || true; fi
case ":$PATH:" in *":$BIN_DIR:"*) ;; *) warn "$BIN_DIR is not on your PATH. Add it:  export PATH=\"$BIN_DIR:\$PATH\"" ;; esac
port="$(getv SCALEGUARD_HTTP_PORT)"
echo
say "ScaleGuard is installed"
echo "    Admin UI : http://localhost:${port:-8080}/adminui/"
echo "    Login    : $(getv SCALEGUARD_ADMIN_USER) / (scaleguard password)"
echo "    Control  : scaleguard start | stop | restart | status | logs | password"
echo "    Folder   : $DATA   (settings in scaleguard.env, DuckDB cache.duckdb + analytics.duckdb, SQLite, certificates)"
[ "$JAVA_OK" = yes ] || echo "    Install Java 21 (see above), then run: scaleguard start"
if [ "${port:-8080}" -ge 1024 ] 2>/dev/null; then echo "    Ports    : using ${port:-8080}; ports below 1024 need root (re-run with sudo, or put a port-forward / --port 80 under sudo)"; fi
