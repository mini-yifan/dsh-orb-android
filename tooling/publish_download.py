#!/usr/bin/env python3
"""Publish dist/ release artifacts to the self-hosted download origin (see docs/release.md).

The in-app updater asks `https://dshorb.top/app/update.json` (compiled into the
app as updateDownloadOrigin, app/lib/update/update_config.dart) and downloads
`https://dshorb.top/app/download/<tag minus v>/<file>`. The server side is the
independently deployed site container in /opt/dsh-orb-site on the dshorb.top
host (ssh alias `dsh-mesh`): this script is the bridge between a finished
`release_assets.py` run in dist/ and that directory.

What it does:

1. Validates dist/ consistency first: update.json's arm64 asset must exist on
   disk and match the manifest's sha256 and size exactly (guards against a
   half-finished release where update.json was regenerated for a newer build
   than the APKs next to it).
2. Packs update.json + the APK + SHA256SUMS + any *.dshdelta into a tarball and
   uploads it over scp.
3. On the server: extracts into app/download/<tag minus v>/, strips macOS
   AppleDouble files, copies the manifest to app/update.json, repoints the
   app/download/latest/dsh-orb-latest-<flavor>.apk symlink at the new APK, and
   re-verifies the remote APK's sha256 against the manifest.

The website's download section reads the same manifest, so after this script
succeeds the site and the in-app updater both serve the new version with no
further steps.

    python3 tooling/publish_download.py             # publish dist/
    python3 tooling/publish_download.py --dry-run   # print the plan, change nothing
"""

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

APP = "dsh-orb"
DEFAULT_HOST = "dsh-mesh"
DEFAULT_BASE = "/opt/dsh-orb-site/downloads/app"
ROOT_DIST = pathlib.Path(__file__).resolve().parent.parent / "dist"


class PublishError(Exception):
    pass


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_dist(dist: pathlib.Path) -> dict:
    """Read dist/update.json and check the artifacts next to it really match."""
    manifest_path = dist / "update.json"
    if not manifest_path.is_file():
        raise PublishError(f"{manifest_path} not found — run release_assets.py first")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        raise PublishError(f"unexpected manifest schema: {manifest.get('schema')!r}")
    assets = manifest.get("assets") or {}
    if not assets:
        raise PublishError("manifest has no assets")
    for flavor, entry in assets.items():
        apk = dist / entry["name"]
        if not apk.is_file():
            raise PublishError(f"manifest lists {entry['name']} but it is not in dist/")
        if entry.get("sha256") and sha256(apk) != entry["sha256"]:
            raise PublishError(f"{entry['name']} does not match update.json sha256 — rebuild or regenerate the manifest")
        if entry.get("size") and apk.stat().st_size != entry["size"]:
            raise PublishError(f"{entry['name']} size {apk.stat().st_size} != manifest {entry['size']}")
    tag = manifest.get("tag") or ""
    if not tag.startswith("v"):
        raise PublishError(f"manifest tag {tag!r} does not start with 'v'")
    return manifest


def collect_files(dist: pathlib.Path, manifest: dict) -> list[pathlib.Path]:
    """The files that travel together: manifest, APKs, checksums, delta patches."""
    wanted = [dist / "update.json", dist / "SHA256SUMS"]
    for entry in manifest["assets"].values():
        wanted.append(dist / entry["name"])
    wanted += sorted(dist.glob("*.dshdelta"))
    missing = [p.name for p in wanted if not p.is_file()]
    if missing:
        raise PublishError(f"missing dist files: {', '.join(missing)}")
    return wanted


def run(cmd: list[str], dry: bool) -> None:
    print("+", " ".join(cmd))
    if not dry:
        subprocess.run(cmd, check=True)


def ssh_run(host: str, script: str, dry: bool) -> None:
    run(["ssh", host, script], dry)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dist", type=pathlib.Path, default=ROOT_DIST,
                        help="dist/ produced by release_assets.py (default %(default)s)")
    parser.add_argument("--host", default=DEFAULT_HOST,
                        help=f"ssh alias of the download server (default {DEFAULT_HOST})")
    parser.add_argument("--base", default=DEFAULT_BASE,
                        help=f"server-side download root (default {DEFAULT_BASE})")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the plan and validate locally, change nothing")
    args = parser.parse_args()

    dist = args.dist.resolve()
    manifest = load_dist(dist)
    files = collect_files(dist, manifest)
    tag = manifest["tag"]
    version_dir = tag[1:]
    assets = manifest["assets"]
    if len(assets) != 1:
        raise PublishError(f"expected exactly one flavor, got {sorted(assets)}")
    flavor, entry = next(iter(assets.items()))
    apk_name = entry["name"]
    # dsh-orb-<version>-<flavor>.apk -> the stable-name symlink for this flavor.
    latest_name = apk_name.replace(f"{tag[1:]}-", "latest-", 1)
    tmp = f"/tmp/{APP}-publish-{version_dir}.tar.gz"

    remote = "\n".join([
        "set -e",
        f"mkdir -p {args.base}/download/{version_dir} {args.base}/download/latest",
        f"tar -xzf {tmp} -C {args.base}/download/{version_dir} 2>/dev/null || true",
        f"find {args.base} -name '._*' -delete",
        f"cp {args.base}/download/{version_dir}/update.json {args.base}/update.json",
        f"ln -sfn ../{version_dir}/{apk_name} {args.base}/download/latest/{latest_name}",
        f"rm -f {tmp}",
        "remote=$(sha256sum "
        f"{args.base}/download/{version_dir}/{apk_name} | cut -d' ' -f1)",
        f"local={entry['sha256']}",
        'if [ "$remote" != "$local" ]; then echo "REMOTE SHA256 MISMATCH: $remote" >&2; exit 1; fi',
        f"echo published {tag} at {args.base}/download/{version_dir}/",
    ])

    print(f"dist: {dist}")
    print(f"manifest: {manifest['version']} ({tag}) -> {args.host}:{args.base}/download/{version_dir}/")
    for p in files:
        print(f"  {p.name}  {p.stat().st_size:>12,} bytes")

    if args.dry_run:
        print("--- dry run: would run ---")
        print(f"tar -czf {tmp} -C {dist} " + " ".join(p.name for p in files))
        print(f"scp {tmp} {args.host}:{tmp}")
        print(remote)
        print("--- nothing changed ---")
        return

    run(["tar", "-czf", tmp, "-C", str(dist), *[p.name for p in files]], dry=False)
    run(["scp", tmp, f"{args.host}:{tmp}"], dry=False)
    ssh_run(args.host, remote, dry=False)
    pathlib.Path(tmp).unlink(missing_ok=True)
    print("done: https://dshorb.top/app/update.json now serves", manifest["version"])


if __name__ == "__main__":
    try:
        main()
    except PublishError as e:
        sys.exit(f"publish: {e}")
    except subprocess.CalledProcessError as e:
        sys.exit(f"publish: command failed with exit {e.returncode}")
