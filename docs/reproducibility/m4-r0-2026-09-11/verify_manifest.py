"""Verify the immutable, checked-in M4-R0 package manifest."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
BENCHMARK = REPO / "benchmarks/m4-r0"
MANIFEST = HERE / "manifest.json"
SCHEMA = "m4-r0-deliverable-manifest-v2"
TEXT_SUFFIXES = {
    ".diff",
    ".java",
    ".json",
    ".jsonl",
    ".md",
    ".properties",
    ".py",
    ".tsv",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
HISTORICAL_EXTERNAL_PATHS = (
    "docs/architecture/conditional-architecture-semantics.md",
    "docs/architecture/m4-r0-semantics-gate.md",
    "docs/architecture/m4-spring-intelligence.md",
    "docs/current-state.md",
    "docs/project-context.md",
    "docs/research/2026-09-11-m4-r0-spring-semantics.md",
    "docs/research/research-questions.md",
    "docs/roadmap.md",
)


class ManifestError(ValueError):
    """A deterministic manifest structure or content failure."""


def require(condition, message):
    if not condition:
        raise ManifestError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def normalized_content(data, mode):
    if mode == "raw-v1":
        return data
    require(mode == "utf8-lf-v1", f"UNSUPPORTED_HASH_MODE:{mode}")
    text = data.decode("utf-8")
    return text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")


def hash_mode(path):
    return "utf8-lf-v1" if path.suffix.lower() in TEXT_SUFFIXES else "raw-v1"


def selected_files():
    selected = set()
    for directory in (BENCHMARK, HERE):
        for path in directory.rglob("*"):
            if not path.is_file() or path.is_symlink() or path == MANIFEST:
                continue
            relative = path.relative_to(REPO)
            if ".cache" in relative.parts or "__pycache__" in relative.parts or path.suffix == ".class":
                continue
            selected.add(path)
    return sorted(selected, key=lambda path: path.relative_to(REPO).as_posix())


def file_row(path):
    mode = hash_mode(path)
    data = normalized_content(path.read_bytes(), mode)
    return {
        "path": path.relative_to(REPO).as_posix(),
        "hashMode": mode,
        "canonicalBytes": len(data),
        "sha256": sha(data),
    }


def load_manifest():
    try:
        return json.loads(MANIFEST.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"INVALID_MANIFEST:{type(error).__name__}") from error


def verified_payload(content):
    require(isinstance(content, dict), "MANIFEST_NOT_OBJECT")
    require("manifestPayloadSha256" in content, "PAYLOAD_DIGEST_MISSING")
    body = {key: value for key, value in content.items() if key != "manifestPayloadSha256"}
    require(sha(canonical(body)) == content["manifestPayloadSha256"], "PAYLOAD_DIGEST_MISMATCH")
    return body


def resolve_row_path(value):
    require(isinstance(value, str) and "\\" not in value, "INVALID_MANIFEST_PATH")
    pure = PurePosixPath(value)
    require(not pure.is_absolute() and ".." not in pure.parts and pure.as_posix() == value, f"INVALID_MANIFEST_PATH:{value}")
    path = (REPO / Path(*pure.parts)).resolve()
    require(path.is_relative_to(REPO.resolve()), f"PATH_ESCAPES_REPOSITORY:{value}")
    require(path.is_file() and not path.is_symlink(), f"MANIFEST_FILE_MISSING:{value}")
    return path


def verify():
    body = verified_payload(load_manifest())
    require(body.get("schema") == SCHEMA, f"UNSUPPORTED_SCHEMA:{body.get('schema')}")
    rows = body.get("files")
    require(isinstance(rows, list), "FILES_NOT_ARRAY")
    paths = [row.get("path") for row in rows]
    require(len(paths) == len(set(paths)), "DUPLICATE_MANIFEST_PATH")
    selected = {path.relative_to(REPO).as_posix() for path in selected_files()}
    recorded = set(paths)
    require(recorded == selected, f"PACKAGE_SCOPE_MISMATCH:missing={sorted(selected-recorded)}:extra={sorted(recorded-selected)}")
    for row in rows:
        path = resolve_row_path(row["path"])
        mode = row.get("hashMode")
        require(mode == hash_mode(path), f"HASH_MODE_MISMATCH:{row['path']}")
        data = normalized_content(path.read_bytes(), mode)
        require(len(data) == row.get("canonicalBytes"), f"CANONICAL_SIZE_MISMATCH:{row['path']}")
        require(sha(data) == row.get("sha256"), f"FILE_DIGEST_MISMATCH:{row['path']}")

    references = body.get("historicalExternalReferences")
    require(isinstance(references, list), "HISTORICAL_REFERENCES_NOT_ARRAY")
    reference_paths = [row.get("path") for row in references]
    require(reference_paths == list(HISTORICAL_EXTERNAL_PATHS), "HISTORICAL_REFERENCE_SCOPE_MISMATCH")
    for row in references:
        require(row.get("hashMode") == "raw-v1", f"HISTORICAL_HASH_MODE_MISMATCH:{row.get('path')}")
        require(row.get("verification") == "REFERENCE_ONLY", f"HISTORICAL_STATUS_MISMATCH:{row.get('path')}")
        require(isinstance(row.get("recordedBytes"), int), f"HISTORICAL_SIZE_MISSING:{row.get('path')}")
        require(isinstance(row.get("sha256"), str) and len(row["sha256"]) == 64, f"HISTORICAL_DIGEST_MISSING:{row.get('path')}")
    return {
        "schema": SCHEMA,
        "filesVerified": len(rows),
        "historicalExternalReferences": len(references),
        "manifestPayloadSha256": sha(canonical(body)),
    }


def migrate_v1():
    original = load_manifest()
    body = verified_payload(original)
    require(body.get("schema") == "m4-r0-deliverable-manifest-v1", "MIGRATION_REQUIRES_V1")
    original_rows = {row["path"]: row for row in body.get("files", [])}
    references = []
    for path in HISTORICAL_EXTERNAL_PATHS:
        require(path in original_rows, f"V1_HISTORICAL_REFERENCE_MISSING:{path}")
        row = original_rows[path]
        references.append(
            {
                "path": path,
                "hashMode": "raw-v1",
                "recordedBytes": row["bytes"],
                "sha256": row["sha256"],
                "verification": "REFERENCE_ONLY",
                "reason": "Living architecture/governance document; this digest preserves the v1 review-time reference and is not compared with later revisions.",
            }
        )
    migrated = {
        "schema": SCHEMA,
        "scope": "checked-in benchmarks/m4-r0 and immutable M4-R0 evidence package",
        "textIdentity": "UTF-8 decoded bytes with CRLF and CR normalized to LF; binary files remain raw",
        "migratedFromManifestPayloadSha256": original["manifestPayloadSha256"],
        "files": [file_row(path) for path in selected_files()],
        "historicalExternalReferences": references,
    }
    content = dict(migrated, manifestPayloadSha256=sha(canonical(migrated)))
    MANIFEST.write_bytes(canonical(content))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--migrate-v1",
        action="store_true",
        help="one-time, payload-checked migration of the original v1 manifest",
    )
    args = parser.parse_args()
    try:
        if args.migrate_v1:
            migrate_v1()
        print(json.dumps(verify(), sort_keys=True))
        return 0
    except (KeyError, OSError, UnicodeError, ManifestError) as error:
        print(json.dumps({"status": "FAILED", "reason": str(error)}, sort_keys=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
