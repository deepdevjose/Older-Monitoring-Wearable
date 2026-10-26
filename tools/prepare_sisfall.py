#!/usr/bin/env python3
"""Verify the three versioned SisFall fragments and assemble one local tar cache."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAX_PART_BYTES = 90 * 1024 * 1024


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare_archive(manifest_path: Path | str | None = None, cache_dir: Path | str | None = None) -> Path:
    """Return a verified local archive, assembling bytes without unpacking members.

    Manifests and fragments are source assets, not authenticated downloads. Protect
    the checkout normally; these hashes detect corruption relative to that source.
    Both fragment integrity and final-archive integrity are checked on every call.
    """
    manifest_path = Path(manifest_path) if manifest_path is not None else ROOT / "datasets/sisfall-manifest.json"
    cache_dir = Path(cache_dir) if cache_dir is not None else ROOT / ".local/datasets"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    archive = manifest.get("archive", {})
    parts = manifest.get("parts", [])
    if manifest.get("schema_version") != 1 or archive.get("name") != "sisfall.tar.gz" or len(parts) != 3:
        raise ValueError("Invalid SisFall fragment manifest")
    digest_pattern = re.compile(r"^[0-9a-f]{64}$")
    if (type(archive.get("size_bytes")) is not int or archive["size_bytes"] < 1
        or not digest_pattern.fullmatch(str(archive.get("sha256", "")))):
        raise ValueError("Invalid archive size or digest")
    part_paths = []
    total = 0
    for index, part in enumerate(parts, 1):
        if (part.get("name") != f"sisfall.tar.gz.part{index:02d}"
            or type(part.get("size_bytes")) is not int or not 1 <= part["size_bytes"] <= MAX_PART_BYTES
            or not digest_pattern.fullmatch(str(part.get("sha256", "")))):
            raise ValueError("Invalid fragment name, size or digest")
        path = manifest_path.parent / part["name"]
        if path.is_symlink() or not path.is_file() or path.stat().st_size != part["size_bytes"] or file_digest(path) != part["sha256"]:
            raise ValueError(f"SisFall fragment missing or integrity check failed: {part['name']}")
        total += part["size_bytes"]
        part_paths.append(path)
    if total != archive["size_bytes"]:
        raise ValueError("Fragment sizes do not match the archive manifest")
    cache_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    destination = cache_dir / archive["name"]
    if destination.is_symlink():
        raise ValueError("SisFall cache must not be a symbolic link")
    if destination.is_file() and destination.stat().st_size == total and file_digest(destination) == archive["sha256"]:
        return destination
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache_dir, prefix=".sisfall-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            count = 0
            for path in part_paths:
                with path.open("rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(block)
                        digest.update(block)
                        count += len(block)
            output.flush()
            os.fsync(output.fileno())
        if count != total or digest.hexdigest() != archive["sha256"]:
            raise ValueError("Assembled SisFall archive integrity check failed")
        os.replace(temporary, destination)
        temporary = None
        return destination
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "datasets/sisfall-manifest.json")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".local/datasets")
    args = parser.parse_args()
    archive = prepare_archive(args.manifest, args.cache_dir)
    print(json.dumps({"archive": str(archive), "size_bytes": archive.stat().st_size,
                      "sha256": file_digest(archive), "verified": True}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
