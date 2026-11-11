#!/usr/bin/env bash
# Local check for the B0 "third-party DSH plugin tgz" path. Runs on macOS and Linux (does not
# need PRoot, an Android device or the full runtime build):
#
#   bash runtime/tests/check-plugin-install.sh
#   DSH_PREFIX=/path/to/dsh-prefix bash runtime/tests/check-plugin-install.sh --with-host
#   bash runtime/tests/check-plugin-install.sh --keep
#
# Checks, in order:
#   1. bash -n for build-runtime.sh, tooling/build-plugin-probe.sh and this script
#   2. the fixture tgz builds twice with the same sha256, and an independent hash matches
#   3. RUNTIME_PLUGIN_CHECK=1 build-runtime.sh verifies/fetches, unpacks and renders the overlay
#   4. rtbuild.py extract-plugin (independent) reproduces the same tree; no native artifacts
#   5. the rendered overlay has the app plugin row after the shared rows, inside the insert list,
#      with the absolute /opt/dsh-go/plugins/... path (and PyYAML parses it when available)
#   6. Node imports the unpacked entry with a mock cordis ctx and apply() prints the health line
#   7. --with-host: a real `dsh web` loads the plugin (requires DSH_PREFIX with DSH >= 0.2.2-rc.1
#      and Node >= 24.9). Paths are rewritten from the rootfs to the host for this run only.
#
# What still needs Linux x86_64 / a device: the full `bash runtime/build-runtime.sh arm64-v8a`
# (rootfs assembly, PRoot, APK) and the on-device log check. See tooling/plugin-probe/README.md.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
RTBUILD="$ROOT/runtime/lib/rtbuild.py"
PLUGIN_NAME='@dsh-orb-android/plugin-probe'

WITH_HOST=0
KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-host) WITH_HOST=1; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/dsh-plugin-check.XXXXXX")"
DSH_PID=""
cleanup() {
  if [[ -n "$DSH_PID" ]]; then
    kill -TERM "$DSH_PID" 2>/dev/null || true
    wait "$DSH_PID" 2>/dev/null || true
  fi
  if [[ "$KEEP" == 1 ]]; then printf 'kept: %s\n' "$TMP"; else rm -rf "$TMP"; fi
}
trap cleanup EXIT

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '  note: %s\n' "$*"; }

command -v python3 >/dev/null || fail "python3 is required"
command -v node >/dev/null || fail "node 24.9+ is required"
NODE_VERSION="$(node -p 'process.versions.node')"
python3 -I - "$NODE_VERSION" <<'PY' || fail "node 24.9+ is required (found $NODE_VERSION)"
import sys
major, minor = map(int, sys.argv[1].split(".")[:2])
raise SystemExit(0 if (major, minor) >= (24, 9) else 1)
PY

echo "== shell syntax"
bash -n "$ROOT/runtime/build-runtime.sh"
bash -n "$ROOT/tooling/build-plugin-probe.sh"
bash -n "${BASH_SOURCE[0]}"
pass "bash -n (build-runtime.sh, build-plugin-probe.sh, check-plugin-install.sh)"

echo "== fixture tgz"
BUILD_OUT="$(bash "$ROOT/tooling/build-plugin-probe.sh")"
TGZ="$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^tgz=//p' | head -1)"
SHA="$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^sha256=//p' | head -1)"
VER="$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^version=//p' | head -1)"
[[ -f "$TGZ" && -n "$SHA" && -n "$VER" ]] || fail "build-plugin-probe.sh did not report tgz/sha256/version"
REBUILD_SHA="$(bash "$ROOT/tooling/build-plugin-probe.sh" | sed -n 's/^sha256=//p' | head -1)"
[[ "$SHA" == "$REBUILD_SHA" ]] || fail "fixture tgz is not reproducible ($SHA != $REBUILD_SHA)"
ACTUAL_SHA="$(python3 -I -c 'import hashlib,sys; f=open(sys.argv[1],"rb"); print(hashlib.file_digest(f,"sha256").hexdigest())' "$TGZ")"
[[ "$ACTUAL_SHA" == "$SHA" ]] || fail "reported sha256 $SHA does not match the file ($ACTUAL_SHA)"
pass "reproducible tgz $VER sha256=$SHA"

echo "== build-runtime.sh plugin path (RUNTIME_PLUGIN_CHECK=1)"
CHECK_LOG="$TMP/build-check.log"
(
  unset DSH_PLUGIN_NAME DSH_PLUGIN_VERSION DSH_PLUGIN_ENTRY DSH_PLUGIN_PREFIX DSH_PLUGIN_TGZ
  unset DSH_PLUGIN_URL DSH_PLUGIN_SHA256
  RUNTIME_PLUGIN_CHECK=1 bash "$ROOT/runtime/build-runtime.sh"
) > "$CHECK_LOG" 2>&1 || { cat "$CHECK_LOG" >&2; fail "RUNTIME_PLUGIN_CHECK=1 build-runtime.sh failed"; }
grep -q "app plugin: $PLUGIN_NAME@$VER sha256=$SHA" "$CHECK_LOG" \
  || { cat "$CHECK_LOG" >&2; fail "build-runtime.sh did not report the verified plugin"; }
OVERLAY="$ROOT/runtime/.work/plugin-check/dsh-web.patch.yml"
UNPACKED="$ROOT/runtime/.work/plugin-check/unpacked"
[[ -f "$OVERLAY" && -f "$UNPACKED/lib/index.js" ]] || fail "build-runtime.sh did not produce the overlay/unpacked tree"
pass "fetched with sha256 verification, unpacked to runtime/.work/plugin-check, rendered the overlay"

echo "== independent extract-plugin"
UNPACK2="$TMP/unpacked"
python3 -I "$RTBUILD" extract-plugin --tgz "$TGZ" --sha256 "$SHA" \
  --name "$PLUGIN_NAME" --version "$VER" --entry lib/index.js --prefix /opt/dsh-go/plugins \
  --out "$UNPACK2" --out-json "$TMP/plugin.json" > "$TMP/extract.log" 2>&1 \
  || { cat "$TMP/extract.log" >&2; fail "extract-plugin failed"; }
diff -r "$UNPACKED" "$UNPACK2" >/dev/null || fail "build-runtime extraction and a fresh extract-plugin differ"
BAD="$(find "$UNPACK2" \( -type l -o -name '*.node' -o -name '*.so' -o -name '*.dylib' \
  -o -name '*.dll' -o -name '*.exe' -o -name '*.wasm' \) -print)"
[[ -z "$BAD" ]] || fail "unpacked plugin contains native/symlink artifacts: $BAD"
python3 -I - "$TMP/plugin.json" "$PLUGIN_NAME" "$VER" <<'PY' || fail "extraction summary is wrong"
import json, sys
summary, name, version = sys.argv[1:4]
d = json.load(open(summary))
assert d["name"] == name, d
assert d["version"] == version, d
assert d["entry"] == "lib/index.js", d
assert d["rootfsPath"] == "/opt/dsh-go/plugins/" + name, d
assert d["warnings"] == [], d
PY
pass "sha256 checked, package.json name/version/entry match, no native artifacts"

echo "== overlay structure"
python3 -I - "$OVERLAY" "$UNPACK2" <<'PY' || fail "overlay text checks failed"
import os, sys
text = open(sys.argv[1], encoding="utf-8").read()
unpacked = sys.argv[2]
shared = text.index("    # --- shared rows ---")
app = text.index("    # --- app plugin rows ---")
assert shared < app, "app plugin rows must follow the shared rows"
row = ("    - id: app-plugin-dsh-orb-android-plugin-probe\n"
       "      name: '/opt/dsh-go/plugins/@dsh-orb-android/plugin-probe/lib/index.js'\n")
assert row in text[app:], "materialised app plugin row is missing or wrong"
assert "@DSH_PLUGIN_" not in text, "template placeholder leaked into the overlay"
assert text.index("- id: dsh-go-bridge") < app, "dsh-go-bridge must be inserted before app plugin rows"
for line in text[app:].splitlines():
    assert not line.startswith("- "), "app plugin row escaped the insert list: " + line
assert os.path.isfile(os.path.join(unpacked, "lib/index.js"))
print("  text structure: ok (shared rows -> app plugin row inside insert)")
PY
python3 -I - "$OVERLAY" <<'PY' || fail "PyYAML rejected the overlay"
import sys
try:
    import yaml
except ImportError:
    print("  note: PyYAML not installed; text checks only")
    raise SystemExit(0)
text = open(sys.argv[1], encoding="utf-8").read()
class Loader(yaml.SafeLoader):
    pass
Loader.add_multi_constructor("tag:yaml.org,2002:js", lambda loader, suffix, node: loader.construct_scalar(node))
doc = yaml.load(text, Loader=Loader)
assert isinstance(doc, list) and doc, "overlay must be a non-empty list"
lists = [row["insert"] for row in doc if isinstance(row, dict) and "insert" in row]
assert len(lists) == 1, "overlay must have exactly one insert list"
rows = lists[0]
assert rows[0]["id"] == "dsh-go-bridge", rows[0]
assert rows[-1]["id"] == "app-plugin-dsh-orb-android-plugin-probe", rows[-1]
assert rows[-1]["name"] == "/opt/dsh-go/plugins/@dsh-orb-android/plugin-probe/lib/index.js", rows[-1]
print("  PyYAML structure: ok")
PY
pass "app plugin row is after the shared rows, inside the insert list, absolute path"

echo "== DSH peer resolution seam"
python3 -I - "$RTBUILD" "$UNPACK2" "$PLUGIN_NAME" <<'PY' || fail "the plugin path has no DSH node_modules seam"
import importlib.util, sys
rtbuild, unpacked, name = sys.argv[1:4]
spec = importlib.util.spec_from_file_location("rtbuild_plugin_check", rtbuild)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
plugins = module.parse_plugins([name + "=" + unpacked], [name + "=lib/index.js"], [], "/opt/dsh-go/plugins")
entries = module.overlay_entries({}, with_pnpm=True, with_bridge=True, plugins=plugins)
seam = entries["opt/dsh-go/plugins/node_modules"]
assert seam.kind == "symlink", seam.kind
assert seam.link == "../../dsh/node_modules", seam.link
print("  /opt/dsh-go/plugins/node_modules -> ../../dsh/node_modules")
PY
pass "plugin path resolves DSH peer packages through the bundled DSH node_modules"

echo "== plugin module load (mock cordis ctx)"
node "$ROOT/runtime/tests/plugin-probe-load.mjs" "$UNPACK2" \
  || fail "plugin entry did not load/apply with the expected contract"
pass "ESM entry, inject, apply, frozen health marker and load line"

if [[ "$WITH_HOST" == 1 ]]; then
  echo "== real dsh web load (host)"
  PREFIX="${DSH_PREFIX:-}"
  if [[ -z "$PREFIX" ]]; then
    for candidate in "$ROOT/runtime/.work/x86_64/dsh-prefix" "$ROOT/runtime/.work/arm64-v8a/dsh-prefix"; do
      if [[ -f "$candidate/node_modules/@deepseek-ai/dsh/lib/bin.js" ]]; then PREFIX="$candidate"; break; fi
    done
  fi
  [[ -n "$PREFIX" && -f "$PREFIX/node_modules/@deepseek-ai/dsh/lib/bin.js" ]] \
    || fail "set DSH_PREFIX to a tree with node_modules/@deepseek-ai/dsh/lib/bin.js"
  INSTALLED="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' \
    "$PREFIX/node_modules/@deepseek-ai/dsh/package.json")"
  note "using DSH $INSTALLED from $PREFIX"

  HOST_PATCH="$TMP/host.patch.yml"
  python3 -I - "$OVERLAY" "$HOST_PATCH" "$ROOT/runtime/bridge/dsh-go-bridge" "$UNPACK2" <<'PY'
import sys
source, target, bridge, unpacked = sys.argv[1:5]
text = open(source, encoding="utf-8").read()
text = text.replace("/opt/dsh-go/dsh-go-bridge", bridge)
text = text.replace("/opt/dsh-go/plugins/@dsh-orb-android/plugin-probe", unpacked)
open(target, "w", encoding="utf-8").write(text)
PY
  HOST_HOME="$TMP/host-home"
  mkdir -p "$HOST_HOME/.dsh"
  PORT="$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
  HOST_LOG="$TMP/host.log"
  env -i PATH="$(dirname "$(command -v node)"):/usr/local/bin:/usr/bin:/bin" \
    HOME="$HOST_HOME" DSH_HOME="$HOST_HOME/.dsh" \
    DEEPSEEK_API_KEY=sk-dsh-plugin-check-not-a-real-key DSH_TELEMETRY_DISABLED=1 \
    LANG=C.UTF-8 TZ="${TZ:-Asia/Shanghai}" \
    node "$PREFIX/node_modules/@deepseek-ai/dsh/lib/bin.js" web --patch "$HOST_PATCH" \
    --host 127.0.0.1 --port "$PORT" --no-open > "$HOST_LOG" 2>&1 &
  DSH_PID=$!
  READY=0
  for _ in $(seq 1 120); do
    if grep -q '^dsh web: http' "$HOST_LOG"; then READY=1; break; fi
    kill -0 "$DSH_PID" 2>/dev/null || break
    sleep 0.5
  done
  kill -TERM "$DSH_PID" 2>/dev/null || true
  wait "$DSH_PID" 2>/dev/null || true
  DSH_PID=""
  [[ "$READY" == 1 ]] || { tail -40 "$HOST_LOG" >&2; fail "dsh web did not start"; }
  grep -q "^\[plugin-probe\] loaded $PLUGIN_NAME@" "$HOST_LOG" \
    || { tail -40 "$HOST_LOG" >&2; fail "dsh web started but did not load the plugin"; }
  pass "real dsh web loaded $PLUGIN_NAME from the rendered overlay"
else
  note "host boot skipped (pass --with-host and DSH_PREFIX for a real dsh web load)"
fi

echo
if [[ "$WITH_HOST" == 1 ]]; then
  echo "ok: tgz -> sha256 -> unpack -> overlay -> module load -> dsh web"
else
  echo "ok: tgz -> sha256 -> unpack -> overlay -> module load"
fi
echo "full rootfs/PRoot/device verification: see tooling/plugin-probe/README.md"