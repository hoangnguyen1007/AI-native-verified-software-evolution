"""Verify checked-in M4-R0 evidence, with optional external-cache verification."""

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
EVIDENCE = REPO / "docs/reproducibility/m4-r0-2026-09-11"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class VerificationError(ValueError):
    """A deterministic package-integrity failure."""


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    return sha_bytes(path.read_bytes())


def normalized_text(path):
    text = path.read_bytes().decode("utf-8")
    return text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")


def require_recorded_text_hash(path, expected):
    data = path.read_bytes()
    require(
        sha_bytes(data) == expected or sha_bytes(normalized_text(path)) == expected,
        f"INPUT_DIGEST_MISMATCH:{path.relative_to(REPO).as_posix()}",
    )


def read(path):
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        name = path.relative_to(REPO).as_posix()
        raise VerificationError(f"INVALID_JSON:{name}:{type(error).__name__}") from error


def load_external_locks():
    artifacts = read(ROOT / "artifacts.lock.json")["artifacts"]
    sources = read(ROOT / "sources-acquired.lock.json")["sources"]
    return artifacts, sources


def verify_lock_metadata(artifacts, sources):
    require(len(artifacts) == 26, "ARTIFACT_LOCK_DENOMINATOR")
    require(len({a["coordinate"] for a in artifacts}) == 26, "DUPLICATE_ARTIFACT_COORDINATE")
    require(len({a["file"] for a in artifacts}) == 26, "DUPLICATE_ARTIFACT_FILE")
    for artifact in artifacts:
        require(SHA256.fullmatch(artifact["sha256"]) is not None, "INVALID_ARTIFACT_DIGEST")
        require(isinstance(artifact["bytes"], int) and artifact["bytes"] > 0, "INVALID_ARTIFACT_SIZE")
        require(
            artifact["url"].startswith("https://repo.maven.apache.org/maven2/"),
            "UNAPPROVED_ARTIFACT_ORIGIN",
        )

    require(len(sources) == 37, "SOURCE_LOCK_DENOMINATOR")
    require(len({source["id"] for source in sources}) == 37, "DUPLICATE_SOURCE_ID")
    require({source["status"] for source in sources} <= {"ACQUIRED", "UNAVAILABLE"}, "UNREGISTERED_SOURCE_STATUS")
    acquired = [source for source in sources if source["status"] == "ACQUIRED"]
    require(len(acquired) == 34, "ACQUIRED_SOURCE_DENOMINATOR")
    for source in acquired:
        require(SHA256.fullmatch(source["sha256"]) is not None, "INVALID_SOURCE_DIGEST")
        require(isinstance(source["bytes"], int) and source["bytes"] > 0, "INVALID_SOURCE_SIZE")
    return acquired


def verify_local_links():
    documents = [
        "docs/current-state.md",
        "docs/project-context.md",
        "docs/roadmap.md",
        "docs/architecture/m4-spring-intelligence.md",
        "docs/architecture/conditional-architecture-semantics.md",
        "docs/research/research-questions.md",
        "docs/architecture/m4-r0-semantics-gate.md",
        "docs/research/2026-09-11-m4-r0-spring-semantics.md",
        "docs/reproducibility/m4-r0-2026-09-11/README.md",
        "benchmarks/m4-r0/README.md",
        "benchmarks/m4-r0/PROTOCOL.md",
    ]
    links = 0
    repo = REPO.resolve()
    for name in documents:
        path = REPO / name
        require(path.is_file(), f"DOCUMENT_MISSING:{name}")
        for _, target in re.findall(r"\[([^]\n]+)\]\(([^)\n]+)\)", path.read_text("utf-8")):
            if target.startswith(("http:", "https:", "#", "mailto:")):
                continue
            local = target.split("#", 1)[0]
            resolved = (path.parent / local).resolve()
            require(resolved.is_relative_to(repo), f"LINK_ESCAPES_REPOSITORY:{name}:{target}")
            require(resolved.exists(), f"BROKEN_LOCAL_LINK:{name}:{target}")
            links += 1
    return links


def verify_checked_in():
    checks = {}
    artifacts, sources = load_external_locks()
    acquired = verify_lock_metadata(artifacts, sources)
    checks.update(artifactLockEntries=len(artifacts), acquiredSourceLockEntries=len(acquired))

    catalog = read(ROOT / "mechanisms.json")["mechanisms"]
    ids = {mechanism["id"] for mechanism in catalog}
    require(len(ids) == len(catalog) == 29, "MECHANISM_CATALOG_DENOMINATOR")
    require("spring.mechanism.unclassified" in ids, "UNCLASSIFIED_MECHANISM_MISSING")
    checks["catalogRows"] = len(catalog)

    census = read(ROOT / "annotation-census.json")
    annotations = census["annotations"]
    require(census["classFiles"] == 15594, "CLASSFILE_CENSUS_DENOMINATOR")
    require(len(annotations) == 402, "ANNOTATION_CENSUS_DENOMINATOR")
    require(len({(a["artifact"], a["entry"]) for a in annotations}) == 402, "DUPLICATE_ANNOTATION")
    require(all(set(a["candidateFamilies"]) <= ids for a in annotations), "UNKNOWN_CANDIDATE_FAMILY")
    checks["annotationDeclarations"] = len(annotations)

    runtime = read(EVIDENCE / "runtime-run-2/runtime-results.json")
    formal = read(EVIDENCE / "oracle-run-3/results.json")
    for item in runtime["inputs"]:
        require_recorded_text_hash(ROOT / item["path"], item["sha256"])
    for path, digest in formal["inputs"].items():
        require_recorded_text_hash(ROOT / path, digest)
    require(len(runtime["cases"]) == 79, "RUNTIME_CASE_DENOMINATOR")
    require(all(case["status"] == "PASS" for case in runtime["cases"]), "RUNTIME_CASE_DISAGREEMENT")
    require(len(formal["cases"]) == 70, "FORMAL_CASE_DENOMINATOR")
    require(all(case["status"] == "PASS" for case in formal["cases"]), "FORMAL_CASE_DISAGREEMENT")
    require(len(formal["solver"]) == 64, "CNF_CASE_DENOMINATOR")
    require(all(case["status"] == "PASS" for case in formal["solver"]), "CNF_CASE_DISAGREEMENT")
    require(
        (EVIDENCE / "runtime-run-2/runtime-results.json").read_bytes()
        == (EVIDENCE / "runtime-run-3/runtime-results.json").read_bytes(),
        "RUNTIME_REPLAY_DIVERGED",
    )
    require(
        (EVIDENCE / "oracle-run-3/results.json").read_bytes()
        == (EVIDENCE / "oracle-run-4/results.json").read_bytes(),
        "FORMAL_REPLAY_DIVERGED",
    )
    checks.update(runtimeCases=79, formalCases=70, cnfCases=64, semanticReplay="BYTE_IDENTICAL")

    gap_directory = EVIDENCE / "gap-run-2"
    gaps = read(gap_directory / "capability-gaps.json")
    snapshot = read(gap_directory / "study-snapshot.json")
    observation_file = ROOT / "gap-observations.tsv"
    observations = observation_file.read_text("utf-8").splitlines()
    payloads = {"sha256:" + sha_bytes(row.encode("utf-8")) for row in observations}
    observation_identity = "sha256:" + sha_bytes(normalized_text(observation_file))
    require(len(gaps) == len(observations) == 452, "CAPABILITY_GAP_DENOMINATOR")
    require(len({gap["gapIdentity"] for gap in gaps}) == 452, "DUPLICATE_CAPABILITY_GAP")
    for gap in gaps:
        require(gap["schemaVersion"] == "capability-gap-record-v1", "GAP_SCHEMA_MISMATCH")
        require(gap["context"]["snapshotIdentity"] == snapshot["identity"], "GAP_SNAPSHOT_MISMATCH")
        require(gap["sourceSpans"] == [], "UNEXPECTED_RESEARCH_SOURCE_SPAN")
        require(gap["evidenceRequirements"] and gap["affectedOutputs"], "INCOMPLETE_GAP_RECORD")
        for observation in gap["observationReferences"]:
            require(observation["sourceResultIdentity"] == observation_identity, "GAP_SOURCE_IDENTITY_MISMATCH")
            require(observation["payloadDigest"] in payloads, "GAP_PAYLOAD_MISSING")
    snapshot_files = snapshot["files"]
    require(
        len({item["path"] for item in snapshot_files}) == len(snapshot_files),
        "DUPLICATE_SNAPSHOT_FILE",
    )
    require(
        all(
            isinstance(item.get("contentDigest"), str)
            and item["contentDigest"].startswith("sha256:")
            and SHA256.fullmatch(item["contentDigest"].removeprefix("sha256:")) is not None
            for item in snapshot_files
        ),
        "INVALID_SNAPSHOT_FILE_DIGEST",
    )
    snapshot_content = "sha256:" + sha_bytes(
        json.dumps(snapshot_files, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    require(snapshot["contentDigest"] == snapshot_content, "SNAPSHOT_CONTENT_DIGEST_MISMATCH")
    identity_preimage = {
        "components": [snapshot["repository"], snapshot_content],
        "kind": "snapshot",
        "version": 1,
    }
    expected_snapshot_identity = "snapshot:sha256:" + sha_bytes(
        json.dumps(identity_preimage, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    require(snapshot["identity"] == expected_snapshot_identity, "SNAPSHOT_IDENTITY_MISMATCH")
    gap_summary = read(gap_directory / "summary.json")
    require(gap_summary["gaps"] == len(gaps), "GAP_SUMMARY_DENOMINATOR")
    require(gap_summary["digest"] == "sha256:" + sha(gap_directory / "capability-gaps.json"), "GAP_SUMMARY_DIGEST")
    require(gap_summary["snapshotIdentity"] == snapshot["identity"], "GAP_SUMMARY_SNAPSHOT")
    require(
        (gap_directory / "capability-gaps.json").read_bytes()
        == (EVIDENCE / "gap-run-3/capability-gaps.json").read_bytes(),
        "GAP_REPLAY_DIVERGED",
    )
    checks.update(capabilityGapRecords=452, gapReplay="BYTE_IDENTICAL")
    checks["localLinks"] = verify_local_links()
    return checks


def verify_external_cache(cache_root, artifacts, sources):
    expected = [
        ("artifact", cache_root / "jars" / artifact["file"], artifact["bytes"], artifact["sha256"])
        for artifact in artifacts
    ]
    expected.extend(
        ("source", cache_root / "sources" / f"{source['id']}.txt", source["bytes"], source["sha256"])
        for source in sources
        if source["status"] == "ACQUIRED"
    )
    missing = []
    mismatches = []
    verified = 0
    for kind, path, expected_bytes, expected_sha in expected:
        label = f"{kind}:{path.name}"
        if not path.is_file():
            missing.append(label)
            continue
        if path.stat().st_size != expected_bytes or sha(path) != expected_sha:
            mismatches.append(label)
            continue
        verified += 1
    status = "FAILED" if mismatches else "UNAVAILABLE" if missing else "VERIFIED"
    return {
        "status": status,
        "expectedInputs": len(expected),
        "verifiedInputs": verified,
        "missingCount": len(missing),
        "missing": missing,
        "mismatches": mismatches,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-cache",
        action="store_true",
        help="also require and hash every Git-ignored downloaded source and JAR",
    )
    args = parser.parse_args()
    try:
        checks = verify_checked_in()
        artifacts, sources = load_external_locks()
        if args.require_cache:
            cache = verify_external_cache(ROOT / ".cache", artifacts, sources)
            checks["externalCache"] = cache
            print(json.dumps(checks, sort_keys=True))
            return 0 if cache["status"] == "VERIFIED" else 2 if cache["status"] == "UNAVAILABLE" else 1
        checks["externalCache"] = {
            "status": "NOT_REQUESTED",
            "expectedInputs": len(artifacts) + sum(source["status"] == "ACQUIRED" for source in sources),
        }
        print(json.dumps(checks, sort_keys=True))
        return 0
    except (KeyError, OSError, UnicodeError, VerificationError) as error:
        print(json.dumps({"status": "FAILED", "reason": str(error)}, sort_keys=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
