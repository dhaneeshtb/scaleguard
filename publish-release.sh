#!/bin/sh
# Builds dist/scaleguard.tar.gz (make-release.sh) and uploads it to a GitHub release, replacing the files of the same name.
# Creates the release when the tag has none. Needs curl, python3 and a token with write access to the repo's contents.
#
#   export GH_TOKEN=...            (never paste it into chat or commit it)
#   ./publish-release.sh [TAG]     TAG defaults to v1.0.0; --no-build skips the build and uploads what is in dist/
set -eu
cd "$(dirname "$0")"
REPO="dhaneeshtb/scaleguard"
TAG="v1.0.0"; BUILD=yes
for a in "$@"; do case "$a" in --no-build) BUILD=no ;; *) TAG="$a" ;; esac; done
[ -n "${GH_TOKEN:-}" ] || { echo "set GH_TOKEN first" >&2; exit 1; }

[ "$BUILD" = yes ] && ./make-release.sh
[ -f dist/scaleguard.tar.gz ] && [ -f dist/scaleguard.tar.gz.sha256 ] || { echo "dist/ has no package; run without --no-build" >&2; exit 1; }

api() { curl -fsS -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

# the release for this tag, created if missing
if REL="$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null)"; then :
else REL="$(api -X POST "https://api.github.com/repos/$REPO/releases" -d "{\"tag_name\":\"$TAG\",\"name\":\"ScaleGuard ${TAG#v}\"}")"; fi
ID="$(printf '%s' "$REL" | json 'd["id"]')"

for f in scaleguard.tar.gz.sha256 scaleguard.tar.gz; do
  old="$(printf '%s' "$REL" | json "next((a['id'] for a in d.get('assets',[]) if a['name']=='$f'), '')")"
  [ -z "$old" ] || api -X DELETE "https://api.github.com/repos/$REPO/releases/assets/$old"
  api -X POST -H "Content-Type: application/octet-stream" --data-binary "@dist/$f" \
    "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$f" | json 'd["name"], d["size"], d["state"]'
done
echo "Published: https://github.com/$REPO/releases/tag/$TAG"
