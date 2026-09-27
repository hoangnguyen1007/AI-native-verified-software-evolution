"""Freeze a bounded, credential-free public GitHub source export without extracting or executing it."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import struct
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import BinaryIO


SCHEMA = "m4uv2-l2-source-export-v1"
CHUNK = 1 << 20


def preflight_entry_count(archive: Path | BinaryIO, max_files: int) -> None:
    """Reject oversized ZIP directories before ZipFile allocates entry objects."""
    archive.seek(0, os.SEEK_END)
    length = archive.tell()
    archive.seek(max(0, length - 65_557))
    tail = archive.read()
    position = tail.rfind(b"PK\x05\x06")
    if position < 0 or position + 22 > len(tail):
        raise ValueError("archive end-of-directory record is missing")
    disk, directory_disk, entries_disk, entries_total, directory_bytes, _, comment_bytes = (
        struct.unpack_from("<HHHHIIH", tail, position + 4))
    if (disk or directory_disk or entries_disk != entries_total
            or entries_total == 0xFFFF or directory_bytes == 0xFFFFFFFF
            or entries_total > max_files or directory_bytes > length
            or position + 22 + comment_bytes != len(tail)):
        raise ValueError("archive directory exceeds policy or uses unsupported ZIP64/multidisk form")
    archive.seek(0)


def scan_archive(archive: Path | BinaryIO, repository: str, revision: str,
                 max_files: int, max_expanded: int, max_entry: int) -> dict:
    """Hash every captured entry; never extract paths or follow archive links."""
    rows = []
    seen = set()
    expanded = 0
    if not hasattr(archive, "read"):
        with open(archive, "rb") as stream:
            preflight_entry_count(stream, max_files)
    else:
        preflight_entry_count(archive, max_files)
    with zipfile.ZipFile(archive) as source:
        infos = source.infolist()
        if len(infos) > max_files:
            raise ValueError("archive entry count exceeds policy")
        prefixes = {i.filename.split("/", 1)[0] for i in infos}
        if len(prefixes) != 1:
            raise ValueError("archive does not have one selected root")
        prefix = prefixes.pop()
        for info in infos:
            name = info.filename
            if (len(name) > 4096 or "\\" in name or "\x00" in name or "//" in name
                    or name.startswith("/") or ":" in name
                    or any(part in ("", ".", "..") for part in name.rstrip("/").split("/"))):
                raise ValueError("unsafe archive path")
            if name == prefix + "/":
                continue
            if not name.startswith(prefix + "/"):
                raise ValueError("archive entry escaped selected root")
            relative = name[len(prefix) + 1:].rstrip("/")
            if not relative or relative in seen:
                raise ValueError("duplicate archive path")
            seen.add(relative)
            mode = stat.S_IFMT(info.external_attr >> 16)
            kind = "directory" if info.is_dir() else "symlink" if mode == stat.S_IFLNK else "file"
            if kind == "directory":
                rows.append({"path": relative, "kind": kind, "bytes": 0, "sha256": None})
                continue
            if info.file_size > max_entry or info.file_size > max_expanded - expanded:
                raise ValueError("archive expansion exceeds policy")
            digest = hashlib.sha256()
            length = 0
            with source.open(info) as stream:
                while True:
                    chunk = stream.read(CHUNK)
                    if not chunk:
                        break
                    length += len(chunk)
                    if length > max_entry or length > max_expanded - expanded:
                        raise ValueError("archive expansion exceeds policy")
                    digest.update(chunk)
            if length != info.file_size:
                raise ValueError("archive entry length mismatch")
            expanded += length
            rows.append({"path": relative, "kind": kind, "bytes": length,
                         "sha256": digest.hexdigest()})
    rows.sort(key=lambda row: row["path"])
    tree = hashlib.sha256()
    tree.update((SCHEMA + "\n").encode("utf-8"))
    for row in rows:
        tree.update((json.dumps(row, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":")) + "\n").encode("utf-8"))
    return {"schema": SCHEMA, "repository": repository, "revision": revision,
            "tree_sha256": tree.hexdigest(), "entry_count": len(rows),
            "file_count": sum(row["kind"] == "file" for row in rows),
            "symlink_count": sum(row["kind"] == "symlink" for row in rows),
            "directory_count": sum(row["kind"] == "directory" for row in rows),
            "expanded_bytes": expanded, "entries": rows}


def freeze(repository: str, revision: str, output: Path, max_download: int,
           max_files: int, max_expanded: int, max_entry: int) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository must be an owner/name pair")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("revision must be a full Git commit ID")
    if min(max_download, max_files, max_expanded, max_entry) < 1:
        raise ValueError("all limits must be positive")
    url = f"https://codeload.github.com/{repository}/zip/{revision}"
    request = urllib.request.Request(url, headers={"User-Agent": "M4UV2-L2-source-freeze"})
    with tempfile.TemporaryFile() as captured:
        archive_digest = hashlib.sha256()
        downloaded = 0
        with urllib.request.urlopen(request, timeout=45) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > max_download:
                raise ValueError("download exceeds policy")
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                downloaded += len(chunk)
                if downloaded > max_download:
                    raise ValueError("download exceeds policy")
                archive_digest.update(chunk)
                captured.write(chunk)
        captured.flush()
        captured.seek(0)
        result = scan_archive(captured, repository, revision,
                              max_files, max_expanded, max_entry)
    result["archive_sha256"] = archive_digest.hexdigest()
    result["download_bytes"] = downloaded
    result["policy"] = {"max_download": max_download, "max_files": max_files,
                        "max_expanded": max_expanded, "max_entry": max_entry}
    output.parent.mkdir(parents=True, exist_ok=True)
    serialized = (json.dumps(result, ensure_ascii=False, sort_keys=True,
                             separators=(",", ":")) + "\n").encode("utf-8")
    if output.exists():
        if output.read_bytes() != serialized:
            raise FileExistsError("frozen manifest exists with different bytes")
        return result
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix="l2-freeze-",
                                     suffix=".tmp", delete=False) as stage:
        staged = Path(stage.name)
        stage.write(serialized)
        stage.flush()
        os.fsync(stage.fileno())
    try:
        if output.exists():
            raise FileExistsError("frozen manifest appeared during write")
        os.link(staged, output)
    finally:
        staged.unlink(missing_ok=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("revision")
    parser.add_argument("output", type=Path)
    parser.add_argument("--max-download", type=int, default=120_000_000)
    parser.add_argument("--max-files", type=int, default=100_000)
    parser.add_argument("--max-expanded", type=int, default=500_000_000)
    parser.add_argument("--max-entry", type=int, default=40_000_000)
    args = parser.parse_args()
    result = freeze(args.repository, args.revision, args.output, args.max_download,
                    args.max_files, args.max_expanded, args.max_entry)
    print(json.dumps({key: value for key, value in result.items() if key != "entries"},
                     sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
