#!/usr/bin/env python3
"""Publish a finished dist/ to the OSS update origin (and the dshorb.top mirror).

Why this shape (docs/release.md):
- dshorb.top is not ICP-filed, so direct HTTPS to the Aliyun server is reset on
  many mainland networks. The app's update origin is an Aliyun OSS bucket
  endpoint instead: it needs no filing and is reachable.
- OSS refuses to serve objects whose key ends in `.apk`
  (`ApkDownloadForbidden`, default endpoint). The app therefore downloads
  `<name>.bin` — Android installs by content, verified on device with
  `pm install file.bin` — and the manifest's asset name is rewritten to match.
- The website offers `<name>.zip` (the original APK inside), which OSS allows;
  the download card derives that URL from the manifest name.

Usage (needs `oss2` and ~/.dsh-orb/private/oss-ram.csv with AccessKeyId/Secret):

    python3 tooling/publish_oss.py --dry-run
    python3 tooling/publish_oss.py
"""

import argparse
import csv
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DIST = ROOT / "dist"
CREDS = pathlib.Path.home() / ".dsh-orb/private/oss-ram.csv"
BUCKET = "dsh-orb-release"
ENDPOINT = "https://oss-cn-beijing.aliyuncs.com"
PUBLIC = f"https://{BUCKET}.oss-cn-beijing.aliyuncs.com"
SSH_HOST = "dsh-mesh"
MIRROR = "/opt/dsh-orb-site/downloads/app"


class PublishError(Exception):
    pass


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare(dist: pathlib.Path, work: pathlib.Path) -> dict:
    manifest = json.loads((dist / "update.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise PublishError("unexpected manifest schema")
    assets = manifest.get("assets") or {}
    if len(assets) != 1:
        raise PublishError(f"expected exactly one flavor, got {sorted(assets)}")
    flavor, entry = next(iter(assets.items()))
    apk = dist / entry["name"]
    if not apk.is_file():
        raise PublishError(f"{apk.name} is missing from dist/")
    if sha256(apk) != entry["sha256"] or apk.stat().st_size != entry["size"]:
        raise PublishError(f"{apk.name} does not match update.json")
    tag = manifest.get("tag") or ""
    if not tag.startswith("v"):
        raise PublishError(f"bad tag {tag!r}")

    bin_name = entry["name"].removesuffix(".apk") + ".bin"
    zip_name = entry["name"].removesuffix(".apk") + ".zip"
    bin_path = work / bin_name
    shutil.copy2(apk, bin_path)

    manifest["assets"][flavor]["name"] = bin_name
    manifest_path = work / "update.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    return {
        "version": tag[1:],
        "bin": bin_path,
        "zip_name": zip_name,
        "manifest": manifest_path,
    }


def upload(bin_path: pathlib.Path, manifest: pathlib.Path, version: str) -> None:
    import oss2

    row = next(csv.DictReader(CREDS.open(encoding="utf-8-sig")))
    auth = oss2.Auth(row["AccessKeyId"], row["AccessKeySecret"])
    bucket = oss2.Bucket(auth, ENDPOINT, BUCKET)
    key = f"app/download/{version}/{bin_path.name}"
    print(f"uploading {bin_path.name} -> {key}")
    oss2.resumable_upload(
        bucket, key, str(bin_path),
        store=oss2.ResumableStore(root="/tmp/oss2-store"),
        multipart_threshold=16 * 1024 * 1024, part_size=8 * 1024 * 1024, num_threads=3,
    )
    bucket.put_object_from_file("app/update.json", str(manifest))
    print("uploaded manifest: app/update.json")


def finalize(version: str, bin_name: str, zip_name: str) -> None:
    script = f"""
set -e
cd /tmp && rm -rf orb-pub && mkdir orb-pub && cd orb-pub
aliyun oss cp oss://{BUCKET}/app/download/{version}/{bin_name} ./{bin_name} -f >/dev/null
cp {bin_name} {bin_name[:-4]}.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile("{zip_name}", "w", compression=zipfile.ZIP_STORED) as z:
    z.write("{bin_name[:-4]}.apk", arcname="{bin_name[:-4]}.apk")
PY
curl -s {PUBLIC}/app/update.json -o update.json
BINSHA=$(sha256sum {bin_name} | cut -d' ' -f1)
ZIPSHA=$(sha256sum {zip_name} | cut -d' ' -f1)
printf '%s  %s\\n%s  %s\\n' "$BINSHA" {bin_name} "$ZIPSHA" {zip_name} > SHA256SUMS
aliyun oss cp {zip_name} oss://{BUCKET}/app/download/{version}/{zip_name} -f >/dev/null
aliyun oss cp SHA256SUMS oss://{BUCKET}/app/download/{version}/SHA256SUMS -f >/dev/null
mkdir -p {MIRROR}/download/{version}
cp {bin_name} {zip_name} update.json SHA256SUMS {MIRROR}/download/{version}/
cp update.json {MIRROR}/update.json
rm -rf /tmp/orb-pub
echo -n 'oss manifest: '; curl -s {PUBLIC}/app/update.json | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["version"], d["assets"]["arm64"]["name"])'
echo -n 'oss zip: '; curl -s -o /dev/null -w '%{{http_code}}\\n' {PUBLIC}/app/download/{version}/{zip_name}
"""
    subprocess.run(["ssh", SSH_HOST, script], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dist", type=pathlib.Path, default=DEFAULT_DIST)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        files = prepare(args.dist.resolve(), pathlib.Path(tmp))
        print(f"version {files['version']}: {files['bin'].name}")
        if args.dry_run:
            print("would upload:", files["bin"].name, "+ app/update.json")
            print("would build zip on server:", files["zip_name"])
            return
        upload(files["bin"], files["manifest"], files["version"])
        finalize(files["version"], files["bin"].name, files["zip_name"])
        print("done")


if __name__ == "__main__":
    try:
        main()
    except PublishError as e:
        sys.exit(f"publish_oss: {e}")
