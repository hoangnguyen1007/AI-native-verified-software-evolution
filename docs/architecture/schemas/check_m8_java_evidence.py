"""Design-only schema checks. Synthetic shapes are NOT native/semantic evidence.

Requires jsonschema==4.23.0 (verification dependency only, not a product dependency).
Run: python docs/architecture/schemas/check_m8_java_evidence.py
Optional: --dependency-path <directory containing a temporary jsonschema install>
"""
import argparse
import base64
import copy
import hashlib
import json
import sys
from pathlib import Path


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-path", type=Path)
    parser.add_argument("--write-codec-positives", type=Path,
                        help="Create deterministic authored positive-shape test data; never semantic evidence")
    parser.add_argument("--check-codec-positives", type=Path,
                        help="Check the saved production-codec fixture against this generator without rewriting it")
    args = parser.parse_args()
    if args.dependency_path:
        sys.path.insert(0, str(args.dependency_path.resolve()))
    from jsonschema import Draft202012Validator

    path = Path(__file__).with_name("m8-java-evidence-v1.schema.json")
    schema = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    definitions = schema["$defs"]
    digest = "sha256:" + hashlib.sha256(b"synthetic schema example only").hexdigest()
    gap = "gap:" + digest
    handle = {"digest": digest, "size": 29}
    version = {"id": "fixture.provider", "version": "design-only"}
    scope = {key: digest for key in
             ("inventory", "readiness", "selection", "context", "logicalScope")}
    scope.update(baseSnapshot="snapshot:" + digest, module="module:" + digest,
                 sourceSet="main", variant="baseline")
    locator = {"container": handle, "members": [],
               "path": {"rawBase64": base64.b64encode(b"generated/Example.java").decode("ascii")}}

    def table(name):
        return {"schema": name + "-table:m8.4-v1",
                "content": {"digest": "sha256:" + hashlib.sha256(b"").hexdigest(), "size": 0},
                "rows": 0}

    def record(name, **values):
        return {"schema": name + ":m8.4-v1", **values}

    samples = [
        record("java-evidence-input", role="SOURCE", content=handle,
               locator=locator, provider=version),
        record("java-generation-lineage", scope=scope, generator=version,
               compiler=version, compilerApplicability="APPLICABLE",
               compilerApplicabilityProof=None, toolchain=digest, recipe=digest, configuration=digest,
               environment=digest, nativeResolution=digest,
               inputs={**table("java-evidence-input"), "rows": 1},
               outputCensus=handle, inputClosure=digest, closure="COMPLETE", gaps=[]),
        record("java-output-row", key=digest, scope=scope, role="generated-java",
               expectation="REQUIRED", locator=locator, kind="FILE", content=handle,
               lineage=digest, rootProof=digest, category="JAVA_SOURCE",
               disposition="ACCEPTED", reason=None),
        record("java-generated-overlay", scope=scope, outputCensus=handle,
               lineages=table("java-generation-lineage"), outputs=table("java-output-row"),
               provider=version, census="SEALED", closure="COMPLETE", frontier=[], gaps=[],
               counts=dict.fromkeys(("total", "accepted", "retained", "absent", "rejected",
                                     "unavailable", "notReached"), 0)),
        record("java-resolution-slot", scope=scope, space="CLASS_PATH", ordinal=0,
               logicalName="model-classes", kind="CLASS_DIRECTORY", original=handle,
               tree=table("java-output-row"), selectedView=handle, origin="PROJECT",
               module="module:" + digest, proof=digest, disposition="ACCEPTED", gaps=[]),
        record("java-resolution-manifest", scope=scope, overlay=digest, compiler=version,
               sourceLanguage="21", bytecodeTarget="21", release="21", preview=False,
               encoding="UTF-8", platform=digest, sourceMembership=table("java-evidence-input"),
               slots=table("java-resolution-slot"), arguments=[], argfiles=[],
               contextProof=digest, status="EXACT", gaps=[]),
        record("java-evidence-proof", scope=scope, question="java.output-bytes",
               subject="role:generated-java", predicate=version, validator=version,
               policy=digest, answer=handle, inputClosure=digest,
               inputs=table("java-evidence-input"),
               checks=[{"name": "CONTENT_HASH_SIZE", "outcome": "PASS",
                        "evidence": [handle], "reason": None}],
               outcome="ACCEPT", trust="CAPTURED_BYTES", limitations=[]),
        record("java-closure-requirement", scope=scope, question="java.output-bytes",
               subject="role:generated-java", category="generation", predicate=version,
               gapRefs=[], occurrences=[handle], readinessRefs=["requirement:fixture"],
               prerequisites=[]),
        record("java-closure-decision", requirement=digest, state="SATISFIED",
               proofs=[digest], prerequisites=[], conflicts=[], supersedes=[], reason=None),
        record("java-closure-ledger", scope=scope, census=digest,
               requirements=table("java-closure-requirement"),
               decisions=table("java-closure-decision"),
               bindings=table("java-evidence-binding"), previous=None),
        record("java-closure-current-row", requirement=digest, state="SATISFIED",
               answers=[digest], proofContents=[digest], blockers=[], conflicts=[]),
        record("java-closure-projection", scope=scope, census=digest, validator=version,
               policy=digest, current=table("java-closure-current-row"), denominator="CLOSED",
               counts=dict.fromkeys(("total", "pending", "narrowed", "satisfied",
                                     "notApplicable", "conflicted", "reopened", "active"), 0)),
        record("java-evidence-binding", stableRecord=digest, publication=handle,
               attempts=["synthetic-attempt"], authority=[], legacyResolutions=[]),
        record("java-source-map-row", scope=scope, locator=locator, content=handle,
               projectedPath=".evo-generated/fixture/Example.java",
               projectedDocument="source:" + digest, role="GENERATED_MAIN",
               lineage=digest, supportingSources=[]),
        record("java-semantic-input-view", scope=scope, baseInventory=digest,
               overlay=digest, model=digest, sourcePlan=digest, resolution=digest,
               sourceMap=table("java-source-map-row"),
               correspondence=table("java-evidence-input"), preAnalysisClosure=digest,
               providers=[version], policy=digest, derivedSnapshot="snapshot:" + digest),
        record("java-source-membership", scope=scope, sourceEvidence=handle, content=handle,
               rootProof=digest, sourceSet="main", role="MAIN",
               disposition="INCLUDED", reason=None),
        record("java-correspondence", scope=scope, sourceEvidence=handle,
               binaryOrGeneratedEvidence=handle, sourceContent=handle, outputContent=handle,
               outcome="SAME_DECLARATION", predicate=version, proof=digest,
               supportingSpans=[], reason=None),
    ]
    positive = negative = 0
    positive_shapes = []

    def accept(value):
        nonlocal positive
        errors = list(validator.iter_errors(value))
        if errors:
            raise AssertionError(f"positive rejected: {value['schema']}: {errors[0].message[:200]}")
        positive += 1
        positive_shapes.append(copy.deepcopy(value))

    def reject(value, label):
        nonlocal negative
        if validator.is_valid(value):
            raise AssertionError("negative accepted: " + label)
        negative += 1

    assert {s["schema"] for s in samples} == {
        definitions[r["$ref"].split("/")[-1]]["properties"]["schema"]["const"]
        for r in schema["oneOf"]}
    for sample in samples:
        accept(sample)
        reject({**sample, "unknownField": True}, "unknown field")
        reject({**sample, "schema": sample["schema"] + "-future"}, "unsupported version")
        for field in sample:
            value = copy.deepcopy(sample)
            del value[field]
            reject(value, "missing " + field)

    by_name = {s["schema"].split(":")[0]: s for s in samples}

    def changed(name, **values):
        return {**copy.deepcopy(by_name[name]), **copy.deepcopy(values)}

    accept(changed("java-generation-lineage", generator=None, closure="OPEN", gaps=[gap]))
    accept(changed("java-generation-lineage", compiler=None, compilerApplicability="NOT_APPLICABLE",
                   compilerApplicabilityProof=digest))
    accept(changed("java-output-row", disposition="UNAVAILABLE", content=None, reason="MISSING"))
    accept(changed("java-output-row", disposition="RETAINED", kind="LINK",
                   content=None, reason="LINK_PROOF_REQUIRED"))
    accept(changed("java-generated-overlay", census="OPEN", closure="PARTIAL",
                   frontier=[digest], gaps=[gap]))
    accept(changed("java-resolution-manifest", status="WITHHELD", platform=None, gaps=[gap]))
    accept(changed("java-closure-decision", state="REOPENED", supersedes=[digest], reason="REVOKED"))
    accept(changed("java-closure-decision", state="CONFLICTED", conflicts=[digest], reason="CONFLICT"))
    accept(changed("java-correspondence", outcome="UNKNOWN", proof=None,
                   sourceContent=None, sourceEvidence=None, reason="BINARY_ONLY"))
    for name, values in [
        ("java-generation-lineage", {"generator": None}),
        ("java-generation-lineage", {"closure": "OPEN"}),
        ("java-generation-lineage", {"compilerApplicability": "UNKNOWN"}),
        ("java-generation-lineage", {"compiler": None}),
        ("java-generation-lineage", {"compiler": None, "compilerApplicability": "NOT_APPLICABLE"}),
        ("java-output-row", {"content": None}),
        ("java-output-row", {"rootProof": None}),
        ("java-output-row", {"kind": "DIRECTORY"}),
        ("java-output-row", {"disposition": "ABSENT"}),
        ("java-generated-overlay", {"census": "OPEN"}),
        ("java-generated-overlay", {"frontier": [digest]}),
        ("java-resolution-slot", {"tree": None}),
        ("java-resolution-slot", {"origin": None}),
        ("java-resolution-slot", {"module": None}),
        ("java-resolution-slot", {"ordinal": -1}),
        ("java-resolution-manifest", {"platform": None}),
        ("java-resolution-manifest", {"status": "WITHHELD"}),
        ("java-resolution-manifest", {"gaps": [gap]}),
        ("java-evidence-proof", {"answer": None}),
        ("java-evidence-proof", {"outcome": "INCOMPLETE", "trust": "VALIDATED_OBSERVATION"}),
        ("java-closure-decision", {"proofs": []}),
        ("java-closure-decision", {"state": "CONFLICTED", "reason": "CONFLICT"}),
        ("java-closure-decision", {"state": "REOPENED", "reason": "REVOKED"}),
        ("java-closure-current-row", {"blockers": [digest]}),
        ("java-closure-current-row", {"answers": []}),
        ("java-evidence-binding", {"attempts": []}),
        ("java-source-membership", {"rootProof": None}),
        ("java-correspondence", {"proof": None}),
    ]:
        reject(changed(name, **values), name + " " + str(values))
    for size in [-1, 0.5, 9223372036854775808]:
        reject(changed("java-evidence-input", content={**handle, "size": size}), "invalid size")
    for bad_digest in ["sha256:ABC", "f" * 64, "sha256:" + "F" * 64, digest + "\n"]:
        reject(changed("java-evidence-input", content={**handle, "digest": bad_digest}),
               "invalid digest")
    bad_proof = changed("java-evidence-proof")
    bad_proof["checks"][0]["outcome"] = "UNKNOWN"
    bad_proof["checks"][0]["reason"] = "UNAVAILABLE"
    reject(bad_proof, "accepted unknown check")
    for raw in ['{"a":1,"a":2}', '{"outer":{"x":1,"x":2}}']:
        try:
            json.loads(raw, object_pairs_hook=strict_object)
        except ValueError:
            negative += 1
        else:
            raise AssertionError("duplicate raw key accepted")
    fixture = ''.join(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n'
                      for value in positive_shapes).encode('utf-8')
    if args.write_codec_positives:
        with args.write_codec_positives.open('xb') as output:
            output.write(fixture)
    if args.check_codec_positives and args.check_codec_positives.read_bytes() != fixture:
        raise AssertionError('saved production-codec fixture differs from the schema generator')
    print(f"PASS: meta-schema; {len(samples)} record kinds; "
          f"{positive} positive shapes; {negative} rejected shapes/duplicate-key controls.")
    print("Scope: design/structure only; no CAS, authority, production codec or native semantic claim.")


if __name__ == "__main__":
    main()
