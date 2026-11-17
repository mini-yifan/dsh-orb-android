#!/usr/bin/env python3
"""Rebuild the arm64 rootfs on macOS by repacking the tar stream in place.

`runtime/build-runtime.sh` needs a Linux x86_64 host (bash 4 associative
arrays, xz, apt). On a Mac the documented path is to reuse the existing arm64
rootfs and rewrite only the members that carry the app plugin:

  opt/dsh-go/plugins/@dsh-mesh/plugin/**   the plugin itself
  etc/dsh-app/dsh-web.patch.yml            the overlay that registers it
  etc/dsh-app/runtime.json                 runtimeVersion, kept in lockstep
                                           with the outer manifest

Two constraints shape this script:

* The archive cannot be unpacked to disk first. Its `opt/python/share/terminfo`
  tree contains symlink chains that resolve to themselves once extracted, and
  `tarfile.extract()` aborts with ELOOP.
* An xz stream from `tarfile` is single-pass: reading any member out of order
  leaves the handle positioned mid-stream, and a later `for member in archive`
  silently yields only a few hundred entries.

So the archive is opened once per concern, and members are copied as a stream
through `extractfile`. Everything not replaced keeps its order, mtime, uid/gid
and mode; replaced members get a fixed mtime, so two runs over the same inputs
are byte-identical.

Usage:
  python3 -I runtime/lib/repack-rootfs.py \
      --rootfs runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz \
      --plugin-dir /path/to/package \
      --overlay runtime/.work/plugin-check/dsh-web.patch.yml \
      --version r15-...-mesh0.1.1-...-presets5 \
      --manifest runtime/out/arm64-v8a/assets/runtime/manifest.json \
      --plugin-name '@dsh-mesh/plugin' --plugin-version 0.1.1 \
      --plugin-sha256 <hex> \
      --rootfs-path /opt/dsh-go/plugins/@dsh-mesh/plugin \
      --config '{"platform":"android","incomingPreset":"phone-background"}'
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import lzma
import os
import shutil
import sys
import tarfile
from typing import NoReturn

EPOCH = 1767225600  # 2026-01-01T00:00:00Z, the fixed mtime the runtime pins


def fail(message: str) -> NoReturn:
    print(f"repack-rootfs: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def plugin_files(plugin_dir: str) -> dict[str, bytes]:
    """Map plugin-relative paths to bytes from an extracted tarball package."""
    base = plugin_dir.rstrip("/")
    files: dict[str, bytes] = {}
    for relative in ("package.json", "dsh-plugin.json", "cordis.patch.yml"):
        path = os.path.join(base, relative)
        if not os.path.isfile(path):
            fail(f"plugin is missing {relative}")
        with open(path, "rb") as handle:
            files[relative] = handle.read()
    lib = os.path.join(base, "lib")
    if not os.path.isdir(lib):
        fail("plugin is missing lib/")
    for name in sorted(os.listdir(lib)):
        if name.endswith(".mjs"):
            with open(os.path.join(lib, name), "rb") as handle:
                files[f"lib/{name}"] = handle.read()
    return files


def read_runtime_json(rootfs: str, version: str) -> bytes:
    """Return etc/dsh-app/runtime.json with the new runtimeVersion.

    Opened on its own handle: see the module docstring for why the caller's
    stream must not be touched here.
    """
    with tarfile.open(rootfs, "r:xz") as archive:
        try:
            member = archive.getmember("etc/dsh-app/runtime.json")
        except KeyError:
            fail("the rootfs has no etc/dsh-app/runtime.json")
        handle = archive.extractfile(member)
        if handle is None:
            fail("etc/dsh-app/runtime.json is not a regular file")
        document = json.loads(handle.read().decode("utf-8"))
    document["runtimeVersion"] = version
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def emit_plugin_tree(outgoing: tarfile.TarFile, prefix: str, files: dict[str, bytes],
                     entries: dict[str, int]) -> int:
    """Write the plugin directory, its `lib/` and every file; return bytes added."""
    info = tarfile.TarInfo(prefix)
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    info.mtime = EPOCH
    outgoing.addfile(info)
    entries["dir"] += 1

    lib_prefix = f"{prefix}/lib"
    info = tarfile.TarInfo(lib_prefix)
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    info.mtime = EPOCH
    outgoing.addfile(info)
    entries["dir"] += 1

    added = 0
    for relative in sorted(files):
        payload = files[relative]
        info = tarfile.TarInfo(f"{prefix}/{relative}")
        info.mode = 0o644
        info.mtime = EPOCH
        info.size = len(payload)
        outgoing.addfile(info, io.BytesIO(payload))
        entries["file"] += 1
        added += len(payload)
    return added


def copy_member(outgoing: tarfile.TarFile, archive: tarfile.TarFile, member: tarfile.TarInfo) -> int:
    """Copy one non-replaced member verbatim; return uncompressed bytes added."""
    info = tarfile.TarInfo(member.name)
    info.mode = member.mode
    info.uid, info.gid = member.uid, member.gid
    info.uname, info.gname = member.uname, member.gname
    info.mtime = member.mtime
    if member.isdir():
        info.type = tarfile.DIRTYPE
        outgoing.addfile(info)
        return 0
    if member.issym():
        info.type = tarfile.SYMTYPE
        info.linkname = member.linkname
        outgoing.addfile(info)
        return 0
    if member.islnk():
        info.type = tarfile.LNKTYPE
        info.linkname = member.linkname
        outgoing.addfile(info)
        return 0
    if member.isfile():
        data = archive.extractfile(member)
        if data is None:
            fail(f"could not read {member.name}")
        info.size = member.size
        outgoing.addfile(info, data)
        return member.size
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rootfs", required=True)
    parser.add_argument("--plugin-dir", required=True)
    parser.add_argument("--overlay", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--plugin-name", required=True)
    parser.add_argument("--plugin-version", required=True)
    parser.add_argument("--plugin-sha256", required=True)
    parser.add_argument("--rootfs-path", required=True)
    parser.add_argument("--config", default="{}")
    parser.add_argument("--out", help="output tar.xz (default: overwrite --rootfs)")
    args = parser.parse_args()

    out_path = args.out or args.rootfs
    # Archive member names are relative ("opt/..."), while the manifest records
    # the in-rootfs absolute path ("/opt/..."). Normalize to the member form so
    # the replacement never emits a second, leading-slash copy of the tree.
    plugin_prefix = args.rootfs_path.strip("/")
    files = plugin_files(args.plugin_dir)
    with open(args.overlay, "rb") as handle:
        overlay = handle.read()
    runtime_json = read_runtime_json(args.rootfs, args.version)

    replaced = {"etc/dsh-app/dsh-web.patch.yml": overlay, "etc/dsh-app/runtime.json": runtime_json}

    source = out_path + ".tmp.tar"
    entries = {"dir": 0, "file": 0, "symlink": 0, "other": 0}
    total = 0
    emitted_plugin_tree = False

    with tarfile.open(args.rootfs, "r:xz") as archive, \
            tarfile.open(source, "w", format=tarfile.GNU_FORMAT) as outgoing:
        for member in archive:
            name = member.name.rstrip("/")

            # The old plugin tree never survives: a chunk file left behind from
            # a previous version would leave index.mjs importing a module that
            # is no longer part of the bundle. Emit the fresh tree in its place
            # so the directory keeps its original position in the archive.
            if name == plugin_prefix or name.startswith(plugin_prefix + "/"):
                if not emitted_plugin_tree:
                    emitted_plugin_tree = True
                    total += emit_plugin_tree(outgoing, plugin_prefix, files, entries)
                continue

            payload = replaced.pop(name, None)
            if payload is not None:
                info = tarfile.TarInfo(member.name)
                info.mode = member.mode
                info.uid, info.gid = member.uid, member.gid
                info.uname, info.gname = member.uname, member.gname
                info.mtime = EPOCH
                info.size = len(payload)
                outgoing.addfile(info, io.BytesIO(payload))
                entries["file"] += 1
                total += len(payload)
                continue

            if member.isdir():
                entries["dir"] += 1
            elif member.issym() or member.islnk():
                entries["symlink"] += 1
            elif member.isfile():
                entries["file"] += 1
            else:
                entries["other"] += 1
            total += copy_member(outgoing, archive, member)

        if not emitted_plugin_tree:
            total += emit_plugin_tree(outgoing, plugin_prefix, files, entries)
        for name in sorted(replaced):
            payload = replaced[name]
            info = tarfile.TarInfo(name)
            info.mode = 0o644
            info.mtime = EPOCH
            info.size = len(payload)
            outgoing.addfile(info, io.BytesIO(payload))
            entries["file"] += 1
            total += len(payload)

    tar_bytes = os.path.getsize(source)
    tar_sha256 = sha256_file(source)
    with open(source, "rb") as handle, lzma.open(out_path, "wb", preset=6) as target:
        shutil.copyfileobj(handle, target, 1 << 20)
    os.remove(source)

    manifest = json.load(open(args.manifest, encoding="utf-8"))
    manifest["runtimeVersion"] = args.version
    manifest["rootfsSha256"] = sha256_file(out_path)
    manifest["rootfsBytes"] = os.path.getsize(out_path)
    manifest["tarBytes"] = tar_bytes
    manifest["tarSha256"] = tar_sha256
    manifest["entries"] = entries
    manifest["uncompressedBytes"] = total
    manifest["dshPlugins"] = [{
        "name": args.plugin_name,
        "version": args.plugin_version,
        "entry": "lib/index.mjs",
        "rootfsPath": "/" + plugin_prefix,
        "sha256": args.plugin_sha256,
        "config": json.loads(args.config),
    }]
    with open(args.manifest, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"repack-rootfs: {out_path}")
    print(f"  version={args.version}")
    print(f"  rootfsSha256={manifest['rootfsSha256']}")
    print(f"  rootfsBytes={manifest['rootfsBytes']}")
    print(f"  entries={entries}")
    print(f"  uncompressedBytes={total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
