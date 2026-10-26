import hashlib
import json

import pytest

from tools.prepare_sisfall import prepare_archive


def fragments(tmp_path):
    data = b"A compact synthetic tar-byte fixture for testing assembly; not sensor records."
    pieces = [data[:25], data[25:50], data[50:]]
    parts = []
    for index, content in enumerate(pieces, 1):
        name = f"sisfall.tar.gz.part{index:02d}"
        (tmp_path / name).write_bytes(content)
        parts.append({"name": name, "size_bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
    manifest = tmp_path / "sisfall-manifest.json"
    manifest.write_text(json.dumps({"schema_version": 1,
                                   "archive": {"name": "sisfall.tar.gz", "size_bytes": len(data),
                                               "sha256": hashlib.sha256(data).hexdigest()},
                                   "parts": parts}))
    return manifest, data


def test_exact_assembly_cached_reuse_and_corrupt_cache_repair(tmp_path):
    manifest, original = fragments(tmp_path)
    cache = tmp_path / "cache"
    archive = prepare_archive(manifest, cache)
    assert archive.read_bytes() == original
    timestamp = archive.stat().st_mtime_ns
    assert prepare_archive(manifest, cache) == archive
    assert archive.stat().st_mtime_ns == timestamp
    archive.write_bytes(b"corrupt cache")
    assert prepare_archive(manifest, cache).read_bytes() == original
    assert not list(cache.glob("*.tmp"))


def test_corrupt_fragment_rejected_even_when_cache_exists(tmp_path):
    manifest, _ = fragments(tmp_path)
    prepare_archive(manifest, tmp_path / "cache")
    piece = tmp_path / "sisfall.tar.gz.part02"
    piece.write_bytes(b"x" * piece.stat().st_size)
    with pytest.raises(ValueError, match="fragment missing or integrity"):
        prepare_archive(manifest, tmp_path / "cache")


def test_traversal_and_final_archive_hash_rejected(tmp_path):
    manifest, _ = fragments(tmp_path)
    data = json.loads(manifest.read_text())
    data["parts"][0]["name"] = "../outside"
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="Invalid fragment"):
        prepare_archive(manifest, tmp_path / "cache")
    manifest, _ = fragments(tmp_path)
    data = json.loads(manifest.read_text())
    data["archive"]["sha256"] = "0" * 64
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="Assembled"):
        prepare_archive(manifest, tmp_path / "cache")
    assert not list((tmp_path / "cache").glob("*.tmp"))
