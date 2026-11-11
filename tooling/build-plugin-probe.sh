#!/usr/bin/env bash
# Builds the deterministic fixture tarball for the B0 "third-party plugin tgz" spike.
#
#   tooling/build-plugin-probe.sh                 # -> tooling/plugin-probe/dist/<name>-<version>.tgz
#   OUT=/tmp/probe-dist tooling/build-plugin-probe.sh
#
# The tarball is byte-for-byte reproducible: fixed mtime/uid/gid, sorted members, gzip mtime=0.
# It contains only package/package.json, package/cordis.patch.yml, package/lib/index.js and
# package/README.md (pure JavaScript, no dependencies, no native artifacts). Prints:
#   tgz=<absolute path>
#   sha256=<tarball sha256>
#   version=<package version>
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/plugin-probe"
OUT="${OUT:-$SRC/dist}"

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
[[ -f "$SRC/package.json" && -f "$SRC/lib/index.js" ]] || { echo "fixture source is incomplete: $SRC" >&2; exit 1; }
mkdir -p "$OUT"

python3 -I - "$SRC" "$OUT" <<'PY'
import gzip
import hashlib
import io
import json
import os
import sys
import tarfile

src, outdir = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
with open(os.path.join(src, "package.json"), encoding="utf-8") as f:
    pkg = json.load(f)
if pkg.get("name") != "@dsh-orb-android/plugin-probe":
    sys.exit("unexpected fixture package name: %r" % pkg.get("name"))
if pkg.get("private") is not True:
    sys.exit("fixture must stay private")
version = pkg.get("version")
if not isinstance(version, str) or not version:
    sys.exit("fixture package.json has no version")

members = ["package.json", "cordis.patch.yml", "lib/index.js", "README.md"]
missing = [rel for rel in members if not os.path.isfile(os.path.join(src, rel))]
if missing:
    sys.exit("fixture source is missing: " + ", ".join(missing))

tarname = "dsh-orb-android-plugin-probe-%s.tgz" % version
out = os.path.join(outdir, tarname)
epoch = 1767225600  # 2026-01-01T00:00:00Z, same fixed mtime as the runtime rootfs

# Parents first, then their children; a fixed order makes the gzip output reproducible.
names = sorted({"package", "package/lib", *("package/" + rel for rel in members)},
               key=lambda p: (p.count("/"), p))
tmp = out + ".tmp"
with open(tmp, "wb") as raw:
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as tar:
            for name in names:
                info = tarfile.TarInfo(name)
                info.mtime = epoch
                info.uid = info.gid = 0
                info.uname = info.gname = "root"
                if name in ("package", "package/lib"):
                    info.type = tarfile.DIRTYPE
                    info.mode = 0o755
                    tar.addfile(info)
                    continue
                with open(os.path.join(src, name[len("package/"):]), "rb") as f:
                    data = f.read()
                info.type = tarfile.REGTYPE
                info.mode = 0o644
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
os.replace(tmp, out)

with tarfile.open(out, "r:gz") as tar:
    got = sorted(member.name for member in tar.getmembers())
if got != sorted(names):
    sys.exit("tarball contents differ from the intended fixture: %s" % got)
with open(out, "rb") as f:
    digest = hashlib.file_digest(f, "sha256").hexdigest()
print("tgz=" + out)
print("sha256=" + digest)
print("version=" + version)
PY