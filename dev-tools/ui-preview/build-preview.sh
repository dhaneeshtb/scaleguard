#!/bin/bash
# Builds a self-contained admin-UI preview (no server needed) at admin-ui/design-preview.html.
# Uses a hash router (REACT_APP_HASH_ROUTER=true) and mock.js, which fakes every admin API response.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="$HERE/../../admin-ui"
OUT="$(mktemp -d)"
cd "$UI"
REACT_APP_HASH_ROUTER=true BUILD_PATH="$OUT" GENERATE_SOURCEMAP=false npx react-scripts build >/dev/null
python3 - "$OUT" "$HERE/mock.js" "$UI/design-preview.html" <<'PY'
import re, base64, glob, sys
out, mock, dest = sys.argv[1] + "/", sys.argv[2], sys.argv[3]
html = open(out + "index.html").read()
logo = "data:image/svg+xml;base64," + base64.b64encode(open(out + "lb.svg", "rb").read()).decode()
css = open(glob.glob(out + "static/css/main.*.css")[0]).read()
js = open(glob.glob(out + "static/js/main.*.js")[0]).read().replace('"/lb.svg"', '"' + logo + '"').replace("</script", "<\\/script")
html = re.sub(r'<script defer="defer" src="/static/js/main[^"]*"></script>', "", html)
html = re.sub(r'<link href="/static/css/main[^"]*" rel="stylesheet">', lambda m: "<style>" + css + "</style>", html)
html = html.replace('<link rel="icon" href="/favicon.ico"/>', "").replace("<head>", "<head><script>" + open(mock).read() + "</script>", 1)
html = html.replace("</body>", "<script>" + js + "</script></body>")
open(dest, "w").write(html)
print("wrote", dest)
PY
rm -rf "$OUT"
