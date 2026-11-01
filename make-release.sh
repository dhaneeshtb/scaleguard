#!/bin/sh
# Builds the package the one-line installer downloads: dist/scaleguard.tar.gz (+ .sha256) holding scaleguard.jar and adminui/.
# Upload both files to a GitHub release (Releases -> Draft a new release -> attach), then
#   curl -fsSL https://raw.githubusercontent.com/dhaneeshtb/scaleguard/main/install.sh | sh
# installs it on any macOS/Linux machine with Java 21.
# Usage: ./make-release.sh [TAG]. With GH_TOKEN set it then uploads the package to that GitHub release (publish-release.sh).
set -eu
cd "$(dirname "$0")"
mvn -q -DskipTests clean package
(cd admin-ui && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && sh build.sh)
rm -rf dist/pkg; mkdir -p dist/pkg/scaleguard
cp target/scaleguard-*-SNAPSHOT.jar dist/pkg/scaleguard/scaleguard.jar
cp -R adminui dist/pkg/scaleguard/adminui
tar czf dist/scaleguard.tar.gz -C dist/pkg scaleguard
rm -rf dist/pkg
(cd dist && { shasum -a 256 scaleguard.tar.gz 2>/dev/null || sha256sum scaleguard.tar.gz; } > scaleguard.tar.gz.sha256)
ls -l dist/scaleguard.tar.gz; cat dist/scaleguard.tar.gz.sha256
if [ -n "${GH_TOKEN:-}" ]; then ./publish-release.sh "$@"; else echo "GH_TOKEN not set: built only. To publish: export GH_TOKEN=... && ./publish-release.sh"; fi
