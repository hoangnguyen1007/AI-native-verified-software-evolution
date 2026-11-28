#!/usr/bin/env python3
"""Two-Tier Real Comprehension Checklist Benchmark Harness (RepoProbe ASE '26 + Chinthareddy 45-Q).

Implements:
1. RepoProbe 10-Point Atomic Checklist Scorer (Sec. 4.1-4.2, ASE '26):
   - k in {1..4} discrete knowledge items summing strictly to 9 points
   - + 1 discrete Clarity & Organization item (1 or 0) -> total 10 points
   - Rationale-first judge prompt builder & strict response validator
   - Cross-family multi-judge aggregation (n >= 3 judges across >= 2 families,
     e.g., OpenAI GPT + Google Gemini + Anthropic Claude), tracking Overall %,
     Edit Bias rate %, Citation Validity %, and Judge StdDev % (<= 2.4% target).
2. Automated SKG path:line & [E#] citation verification (passive read-only).
3. Tier 1: 45-Question Java Enterprise Suite (Chinthareddy-DKB schema:
   15 Shopizer 6a4a0a65 + 15 ThingsBoard + 15 OpenMRS Core).
4. Tier 2: Polyglot RepoProbe Paired Benchmark (Raw File/Grep vs Evo MCP across
   Java/Spring, Python, TypeScript, Go, and Rust, with explicit holdout split
   and anti-Edit-Bias CONFIG_ONLY_RESOLUTION items).
5. Offline deterministic --dry-run evaluation mode (explicitly labeled
   DRY_RUN_SYNTHETIC so synthetic validation is never confused with live LLM claims).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import math
import re
import statistics
import sys
from pathlib import Path
from typing import Any

SCHEMA = "evo.comprehension-checklist-benchmark:v1"
KNOWLEDGE_ITEMS_MIN = 1
KNOWLEDGE_ITEMS_MAX = 4
KNOWLEDGE_POINTS_TOTAL = 9
CLARITY_POINTS_MAX = 1
CHECKLIST_TOTAL_POINTS = 10
JUDGE_STDDEV_TARGET_PCT = 2.4
MIN_CROSS_FAMILY_JUDGES = 3
MIN_MODEL_FAMILIES = 2

ERROR_TAXONOMY = (
    "MISINTERPRETATION",
    "SHALLOW_EXPLANATION",
    "CONTEXT_MISS",
    "EDIT_BIAS",
    "HALLUCINATION",
)

PATH_LINE_RE = re.compile(
    r"(?<![A-Za-z0-9_./:-])"
    r"([A-Za-z0-9_./-]*\.env(?:\.[A-Za-z0-9_-]+)?|"
    r"[A-Za-z0-9_./-]+\.(?:java|kt|kts|py|ts|tsx|js|jsx|go|rs|cs|php|rb|yml|yaml|properties|json|xml|md|mdx|sql|tf|proto|gradle|toml))"
    r"(?::|#L)(\d{1,6})(?:(?:-|\.\.|#L|-L)(\d{1,6}))?(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
EVIDENCE_ID_RE = re.compile(r"\[E(\d{1,4})\]")
UNIFIED_DIFF_RE = re.compile(r"(?m)^(?:diff --git |--- a/|\+\+\+ b/|@@ -\d+)")
CODE_FENCE_RE = re.compile(r"```([A-Za-z0-9_+-]*)\n(.*?)```", re.DOTALL)
EDIT_PATCH_DIRECTIVE_RE = re.compile(
    r"\b(?:modify\s+(?:the\s+)?(?:class|method|service|controller|file)|"
    r"patch\s+(?:the\s+)?(?:source|code|class)|"
    r"refactor\s+(?:the\s+)?(?:method|class)|"
    r"add\s+(?:a\s+)?new\s+(?:method|field|class|filter|interceptor)|"
    r"replace\s+lines?\s+\d+)\b",
    re.IGNORECASE,
)
CONFIG_ONLY_SIGNAL_RE = re.compile(
    r"\b(?:CONFIG_ONLY_RESOLUTION|application(?:-[a-z0-9_-]+)?\.(?:ya?ml|properties)|"
    r"spring\.profiles\.active|environment\s+variable|feature\s+flag|toggle\s+property|"
    r"set\s+`?[a-z0-9_.-]+`?\s+to)\b",
    re.IGNORECASE,
)
CONFIG_LANG_FENCES = {"yaml", "yml", "properties", "env", "dotenv", "toml", "ini", "bash", "sh", "shell"}


@dataclasses.dataclass(frozen=True)
class ChecklistItem:
    item_id: str
    description: str
    max_points: int
    allowed_scores: tuple[int, ...]
    required_evidence: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "item_id": self.item_id,
            "description": self.description,
            "max_points": self.max_points,
            "allowed_scores": list(self.allowed_scores),
            "required_evidence": list(self.required_evidence),
        }


@dataclasses.dataclass(frozen=True)
class ComprehensionQuestion:
    question_id: str
    tier: str  # "tier1_chinthareddy_45" or "tier2_repoprobe_polyglot"
    repo: str
    language: str
    category: str
    question: str
    checklist_items: tuple[ChecklistItem, ...]
    config_only_expected: bool = False
    expected_config_key: str = ""
    expected_citations: tuple[str, ...] = ()
    split: str = "holdout"

    def to_dict(self) -> dict[str, Any]:
        return {
            "question_id": self.question_id,
            "tier": self.tier,
            "repo": self.repo,
            "language": self.language,
            "category": self.category,
            "question": self.question,
            "checklist_items": [item.to_dict() for item in self.checklist_items],
            "clarity_item": {
                "item_id": "clarity_organization",
                "description": "Clarity & Organization: concise, structured, directly answers the question without fluff.",
                "max_points": CLARITY_POINTS_MAX,
                "allowed_scores": [0, 1],
            },
            "config_only_expected": self.config_only_expected,
            "expected_config_key": self.expected_config_key,
            "expected_citations": list(self.expected_citations),
            "split": self.split,
        }


@dataclasses.dataclass(frozen=True)
class JudgeEvaluation:
    judge_id: str
    model_family: str
    question_id: str
    variant: str  # e.g., "evo_mcp" or "raw_grep"
    validated_response: dict[str, Any]


# ---------------------------------------------------------------------------
# 1. RepoProbe 10-Point Atomic Checklist Validation & Rationale-First Protocol
# ---------------------------------------------------------------------------


def validate_checklist(question: ComprehensionQuestion) -> None:
    """Enforces RepoProbe Sec. 4.1-4.2 atomic checklist invariants:
    - k in {1, 2, 3, 4} discrete knowledge items
    - sum(item.max_points) == 9
    - Each item uses strictly increasing discrete integers starting at 0 and ending at max_points
    - Total with Clarity & Organization (0/1) == 10.
    """
    items = question.checklist_items
    if not (KNOWLEDGE_ITEMS_MIN <= len(items) <= KNOWLEDGE_ITEMS_MAX):
        raise ValueError(
            f"{question.question_id}: knowledge item count k={len(items)} must be in "
            f"[{KNOWLEDGE_ITEMS_MIN}, {KNOWLEDGE_ITEMS_MAX}]"
        )
    seen_ids: set[str] = set()
    total_pts = 0
    for item in items:
        if not item.item_id or not item.item_id.strip():
            raise ValueError(f"{question.question_id}: blank checklist item_id")
        if item.item_id in seen_ids:
            raise ValueError(f"{question.question_id}: duplicate item_id '{item.item_id}'")
        seen_ids.add(item.item_id)
        if not item.description or not item.description.strip():
            raise ValueError(f"{question.question_id}/{item.item_id}: blank description")
        if isinstance(item.max_points, bool) or not isinstance(item.max_points, int) or item.max_points < 1:
            raise ValueError(f"{question.question_id}/{item.item_id}: max_points must be positive int")
        if not item.allowed_scores:
            raise ValueError(f"{question.question_id}/{item.item_id}: allowed_scores must be non-empty")
        for s in item.allowed_scores:
            if isinstance(s, bool) or not isinstance(s, int):
                raise ValueError(f"{question.question_id}/{item.item_id}: non-integer rubric score {s!r}")
        if list(item.allowed_scores) != sorted(set(item.allowed_scores)):
            raise ValueError(f"{question.question_id}/{item.item_id}: allowed_scores must be strictly ascending unique ints")
        if item.allowed_scores[0] != 0 or item.allowed_scores[-1] != item.max_points:
            raise ValueError(
                f"{question.question_id}/{item.item_id}: allowed_scores {item.allowed_scores} must start at 0 "
                f"and end at max_points={item.max_points}"
            )
        total_pts += item.max_points

    if total_pts != KNOWLEDGE_POINTS_TOTAL:
        raise ValueError(
            f"{question.question_id}: knowledge items sum to {total_pts}, expected exactly {KNOWLEDGE_POINTS_TOTAL} "
            f"(+ {CLARITY_POINTS_MAX} Clarity = {CHECKLIST_TOTAL_POINTS})"
        )


def build_rationale_first_judge_prompt(
    question: ComprehensionQuestion,
    candidate_answer: str,
    reference_evidence: list[str] | None = None,
) -> str:
    """Builds a strict RepoProbe Rationale-First discrete rubric judge prompt."""
    validate_checklist(question)
    rubric_lines = []
    for idx, item in enumerate(question.checklist_items, start=1):
        scores_str = ", ".join(str(s) for s in item.allowed_scores)
        ev_hint = f" [Required anchors: {', '.join(item.required_evidence)}]" if item.required_evidence else ""
        rubric_lines.append(
            f"  {idx}. id={item.item_id} | max={item.max_points} | allowed_scores=[{scores_str}] | {item.description}{ev_hint}"
        )
    evidence_block = ""
    if reference_evidence:
        evidence_block = "\nREFERENCE VERIFIED EVIDENCE:\n" + "\n".join(f"- {e}" for e in reference_evidence) + "\n"

    config_note = ""
    if question.config_only_expected:
        config_note = (
            f"\nANTI-EDIT-BIAS NOTE: This task is resolvable via configuration (`{question.expected_config_key}`). "
            "Tag error_tags with `EDIT_BIAS` if the answer proposes unnecessary source code patches instead of the config switch.\n"
        )

    return (
        "You are an independent software architecture evaluation judge following the RepoProbe 10-Point Atomic Checklist protocol.\n"
        "STRICT RULES:\n"
        "1. RATIONALE-FIRST: You MUST write the concise factual `rationale` BEFORE assigning `score` for EVERY item, "
        "and `clarity_rationale` BEFORE `clarity_score`.\n"
        "2. DISCRETE RUBRIC ONLY: Choose `score` strictly from each item's `allowed_scores` integer set, and `clarity_score` in {0, 1}. "
        "Never award partial or out-of-rubric floats.\n"
        "3. LENGTH INVARIANCE: Do NOT reward verbosity; grade solely on verified technical accuracy against the atomic checklist.\n"
        "4. ERROR TAXONOMY: Populate `error_tags` from "
        f"{list(ERROR_TAXONOMY)} whenever a defect is observed.\n\n"
        f"QUESTION ({question.question_id} | {question.repo} | {question.language}):\n{question.question}\n"
        f"{evidence_block}{config_note}\n"
        "ATOMIC CHECKLIST (9 knowledge points + 1 Clarity & Organization = 10 points):\n"
        + "\n".join(rubric_lines)
        + "\n  C. id=clarity_organization | max=1 | allowed_scores=[0, 1] | Clear, concise, well-organized explanation.\n\n"
        f"CANDIDATE ANSWER:\n{candidate_answer}\n\n"
        "OUTPUT STRICT JSON WITH THIS EXACT KEY ORDER (rationale BEFORE score):\n"
        '{\n'
        '  "items": [\n'
        '    {"item_id": "<id>", "rationale": "<verify facts first>", "score": <int in allowed_scores>}\n'
        '  ],\n'
        '  "clarity_rationale": "<assess clarity & organization first>",\n'
        '  "clarity_score": <0 or 1>,\n'
        '  "error_tags": [],\n'
        '  "total_score": <int 0..10>\n'
        '}\n'
    )


def _check_rationale_before_score_in_raw_json(raw_json: str) -> None:
    """Verifies textual Rationale-First key ordering inside raw JSON response."""
    item_obj_re = re.compile(r"\{[^{}]*\"item_id\"[^{}]*\}", re.DOTALL)
    for m in item_obj_re.finditer(raw_json):
        chunk = m.group(0)
        r_pos = chunk.find('"rationale"')
        s_pos = chunk.find('"score"')
        if r_pos < 0 or s_pos < 0:
            raise ValueError("Each checklist item must contain both 'rationale' and 'score'")
        if r_pos > s_pos:
            raise ValueError("Rationale-first violation: 'score' appeared before 'rationale' in raw JSON item")

    cr_pos = raw_json.find('"clarity_rationale"')
    cs_pos = raw_json.find('"clarity_score"')
    if cr_pos < 0 or cs_pos < 0:
        raise ValueError("Judge response must contain both 'clarity_rationale' and 'clarity_score'")
    if cr_pos > cs_pos:
        raise ValueError("Rationale-first violation: 'clarity_score' appeared before 'clarity_rationale' in raw JSON")


def validate_judge_response(
    question: ComprehensionQuestion,
    raw_json_or_dict: str | dict[str, Any],
    min_rationale_chars: int = 12,
) -> dict[str, Any]:
    """Strictly validates a judge response against the question's 9+1 discrete checklist and Rationale-first rule."""
    validate_checklist(question)
    if isinstance(raw_json_or_dict, str):
        _check_rationale_before_score_in_raw_json(raw_json_or_dict)
        parsed = json.loads(raw_json_or_dict)
    elif isinstance(raw_json_or_dict, dict):
        parsed = raw_json_or_dict
    else:
        raise ValueError("Judge response must be a JSON string or dict")

    if not isinstance(parsed, dict):
        raise ValueError("Judge response must decode to a JSON object")

    top_keys = list(parsed.keys())
    if "clarity_rationale" not in parsed or "clarity_score" not in parsed:
        raise ValueError("Missing 'clarity_rationale' or 'clarity_score'")
    if top_keys.index("clarity_rationale") > top_keys.index("clarity_score"):
        raise ValueError("Rationale-first violation: 'clarity_score' precedes 'clarity_rationale'")

    clarity_rationale = parsed.get("clarity_rationale")
    if not isinstance(clarity_rationale, str) or len(clarity_rationale.strip()) < min_rationale_chars:
        raise ValueError(f"clarity_rationale must be a string with at least {min_rationale_chars} chars")

    clarity_score = parsed.get("clarity_score")
    if isinstance(clarity_score, bool) or clarity_score not in (0, 1):
        raise ValueError(f"clarity_score must be integer 0 or 1, got {clarity_score!r}")

    raw_items = parsed.get("items")
    if not isinstance(raw_items, list):
        raise ValueError("Judge response 'items' must be a list")

    expected_by_id = {item.item_id: item for item in question.checklist_items}
    seen: set[str] = set()
    validated_items: list[dict[str, Any]] = []
    knowledge_total = 0

    for entry in raw_items:
        if not isinstance(entry, dict):
            raise ValueError("Each item in judge response must be a JSON object")
        keys = list(entry.keys())
        if "rationale" not in entry or "score" not in entry or "item_id" not in entry:
            raise ValueError("Each item must include 'item_id', 'rationale', and 'score'")
        if keys.index("rationale") > keys.index("score"):
            raise ValueError(f"Rationale-first violation on item '{entry.get('item_id')}': 'score' precedes 'rationale'")

        item_id = entry["item_id"]
        if item_id not in expected_by_id:
            raise ValueError(f"Unknown checklist item_id '{item_id}' for {question.question_id}")
        if item_id in seen:
            raise ValueError(f"Duplicate checklist item_id '{item_id}' in judge response")
        seen.add(item_id)

        rationale = entry["rationale"]
        if not isinstance(rationale, str) or len(rationale.strip()) < min_rationale_chars:
            raise ValueError(f"Item '{item_id}' rationale must be at least {min_rationale_chars} chars")

        score = entry["score"]
        rubric_item = expected_by_id[item_id]
        if isinstance(score, bool) or not isinstance(score, int) or score not in rubric_item.allowed_scores:
            raise ValueError(
                f"Item '{item_id}' score {score!r} is not in discrete rubric {list(rubric_item.allowed_scores)}"
            )

        knowledge_total += score
        validated_items.append(
            {
                "item_id": item_id,
                "rationale": rationale.strip(),
                "score": score,
                "max_points": rubric_item.max_points,
            }
        )

    if seen != set(expected_by_id.keys()):
        missing = sorted(set(expected_by_id.keys()) - seen)
        raise ValueError(f"Missing checklist item(s) in judge response: {missing}")

    computed_total = knowledge_total + clarity_score
    if "total_score" in parsed:
        declared = parsed["total_score"]
        if isinstance(declared, bool) or not isinstance(declared, int) or declared != computed_total:
            raise ValueError(f"Declared total_score={declared!r} does not equal computed sum {computed_total}")

    raw_tags = parsed.get("error_tags", [])
    if not isinstance(raw_tags, list):
        raise ValueError("error_tags must be a list")
    normalized_tags: list[str] = []
    for tag in raw_tags:
        t = str(tag).strip().upper()
        if t not in ERROR_TAXONOMY:
            raise ValueError(f"Invalid error_tag '{tag}'; allowed={list(ERROR_TAXONOMY)}")
        if t not in normalized_tags:
            normalized_tags.append(t)

    return {
        "question_id": question.question_id,
        "items": validated_items,
        "knowledge_score": knowledge_total,
        "clarity_rationale": clarity_rationale.strip(),
        "clarity_score": clarity_score,
        "total_score": computed_total,
        "max_score": CHECKLIST_TOTAL_POINTS,
        "pct_score": round(computed_total * 10.0, 2),
        "error_tags": normalized_tags,
    }


# ---------------------------------------------------------------------------
# 2. Edit Bias Detector & Automated SKG Citation Verification
# ---------------------------------------------------------------------------


def detect_edit_bias(
    answer_text: str,
    config_only_expected: bool = False,
    pre_edit_gate_decision: str | None = None,
    judge_error_tags: list[str] | None = None,
) -> dict[str, Any]:
    """Detects RepoProbe Edit Bias (proposing invasive source code patches when a
    configuration switch or factual architectural explanation suffices).
    """
    text = answer_text or ""
    reasons: list[str] = []

    if judge_error_tags and any(t.upper() == "EDIT_BIAS" for t in judge_error_tags):
        reasons.append("judge_tagged_edit_bias")

    if pre_edit_gate_decision and pre_edit_gate_decision.upper() == "CONFIG_ONLY_RESOLUTION":
        # Gate successfully resolved via config -> no Edit Bias unless answer ALSO emitted code diffs
        if UNIFIED_DIFF_RE.search(text):
            reasons.append("emitted_code_diff_despite_config_gate")
        return {"edit_bias": bool(reasons), "reasons": reasons}

    has_diff = bool(UNIFIED_DIFF_RE.search(text))
    has_patch_directive = bool(EDIT_PATCH_DIRECTIVE_RE.search(text))
    has_config_resolution = bool(CONFIG_ONLY_SIGNAL_RE.search(text))

    code_patch_lines = 0
    for lang, body in CODE_FENCE_RE.findall(text):
        lang_low = (lang or "").strip().lower()
        if lang_low in CONFIG_LANG_FENCES:
            continue
        non_empty = [ln for ln in body.splitlines() if ln.strip()]
        if len(non_empty) >= 4:
            code_patch_lines += len(non_empty)

    if has_diff:
        reasons.append("unified_code_diff_emitted")
    if config_only_expected and not has_config_resolution and (code_patch_lines >= 4 or has_patch_directive):
        reasons.append("invasive_code_patch_for_config_only_task")
    elif not config_only_expected and code_patch_lines >= 12 and has_patch_directive:
        reasons.append("unsolicited_code_patch_in_comprehension_qa")

    return {"edit_bias": bool(reasons), "reasons": sorted(set(reasons))}


def verify_answer_citations(
    answer_text: str,
    skg_file_lines: dict[str, int] | None = None,
    repo_root: Path | None = None,
    evo_citation_verification: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Passively verifies path:line citations in answer_text against SKG file line counts
    or a read-only local repository snapshot (zero target execution, I1).
    """
    if evo_citation_verification and isinstance(evo_citation_verification, dict):
        total = int(evo_citation_verification.get("totalCitationsChecked", 0))
        valid = int(evo_citation_verification.get("verifiedCount", 0))
        invalid = int(evo_citation_verification.get("unverifiedCount", 0))
        if total > 0:
            return {
                "total_citations": total,
                "valid_citations": valid,
                "invalid_citations": invalid,
                "validity_pct": round((valid / total) * 100.0, 2),
                "verified_spans": list(evo_citation_verification.get("verifiedSpans", [])),
                "unverified": list(evo_citation_verification.get("unverifiedCitations", [])),
                "source": "evo_ask_machine_verifier",
            }

    text = answer_text or ""
    norm_index: dict[str, tuple[str, int]] = {}
    if skg_file_lines:
        for p, nlines in skg_file_lines.items():
            norm_p = p.replace("\\", "/").lstrip("./")
            norm_index[norm_p.lower()] = (norm_p, int(nlines))

    verified_spans: list[dict[str, Any]] = []
    unverified: list[dict[str, Any]] = []
    seen: set[tuple[str, int, int]] = set()

    for m in PATH_LINE_RE.finditer(text):
        start_idx = m.start()
        if start_idx >= 3 and text[start_idx - 3 : start_idx] == "://":
            continue
        raw_path = m.group(1).replace("\\", "/").lstrip("./")
        start_line = int(m.group(2))
        end_line = int(m.group(3)) if m.group(3) else start_line
        key = (raw_path.lower(), start_line, end_line)
        if key in seen:
            continue
        seen.add(key)

        resolved_path = None
        total_lines = None

        if norm_index:
            exact = norm_index.get(raw_path.lower())
            if exact:
                resolved_path, total_lines = exact
            else:
                suffix = "/" + raw_path.lower()
                hits = [v for k, v in norm_index.items() if k.endswith(suffix)]
                if len(hits) == 1:
                    resolved_path, total_lines = hits[0]
        elif repo_root is not None:
            candidate = (repo_root / raw_path).resolve()
            try:
                candidate.relative_to(repo_root.resolve())
            except ValueError:
                candidate = None
            if candidate is not None and candidate.is_file():
                resolved_path = raw_path
                total_lines = len(candidate.read_text(encoding="utf-8", errors="replace").splitlines())

        ref = f"{raw_path}:{start_line}" + (f"-{end_line}" if end_line != start_line else "")
        if resolved_path is None or total_lines is None:
            unverified.append({"citation": ref, "reason": "HALLUCINATED_PATH"})
        elif start_line < 1 or end_line < start_line or end_line > total_lines:
            unverified.append(
                {
                    "citation": ref,
                    "reason": "LINE_OUT_OF_BOUNDS",
                    "total_lines": total_lines,
                }
            )
        else:
            verified_spans.append(
                {
                    "citation": ref,
                    "path": resolved_path,
                    "start_line": start_line,
                    "end_line": end_line,
                    "total_lines": total_lines,
                }
            )

    total = len(verified_spans) + len(unverified)
    validity_pct = round((len(verified_spans) / total) * 100.0, 2) if total > 0 else 0.0
    return {
        "total_citations": total,
        "valid_citations": len(verified_spans),
        "invalid_citations": len(unverified),
        "validity_pct": validity_pct,
        "verified_spans": verified_spans,
        "unverified": unverified,
        "source": "skg_line_bounds_checker",
    }


# ---------------------------------------------------------------------------
# 3. Cross-Family Multi-Judge Aggregation (Fixing Single-Family Judge Bias)
# ---------------------------------------------------------------------------


def aggregate_cross_family_judges(
    evaluations: list[JudgeEvaluation],
    strict_cross_family: bool = True,
) -> dict[str, Any]:
    """Aggregates checklist scores across a cross-family judge panel (n >= 3 judges,
    >= 2 model families) and computes Overall %, Judge StdDev %, and error taxonomy.
    """
    if not evaluations:
        raise ValueError("At least one JudgeEvaluation is required")

    judges_to_family: dict[str, str] = {}
    scores_by_judge: dict[str, list[float]] = {}
    scores_by_question: dict[str, list[float]] = {}
    error_counts: dict[str, int] = {k: 0 for k in ERROR_TAXONOMY}

    for ev in evaluations:
        fam = ev.model_family.strip().lower()
        jid = ev.judge_id.strip()
        if not jid or not fam:
            raise ValueError("judge_id and model_family must be non-blank")
        if jid in judges_to_family and judges_to_family[jid] != fam:
            raise ValueError(f"Inconsistent model_family for judge '{jid}'")
        judges_to_family[jid] = fam

        pct = float(ev.validated_response["pct_score"])
        scores_by_judge.setdefault(jid, []).append(pct)
        scores_by_question.setdefault(ev.question_id, []).append(pct)
        for tag in ev.validated_response.get("error_tags", []):
            if tag in error_counts:
                error_counts[tag] += 1

    distinct_judges = sorted(judges_to_family.keys())
    distinct_families = sorted(set(judges_to_family.values()))
    if strict_cross_family:
        if len(distinct_judges) < MIN_CROSS_FAMILY_JUDGES:
            raise ValueError(
                f"Cross-family panel requires >= {MIN_CROSS_FAMILY_JUDGES} judges, got {len(distinct_judges)}"
            )
        if len(distinct_families) < MIN_MODEL_FAMILIES:
            raise ValueError(
                f"Cross-family panel requires >= {MIN_MODEL_FAMILIES} distinct model families "
                f"(mitigating single-family judge bias), got {distinct_families}"
            )

    judge_means = {jid: statistics.fmean(vals) for jid, vals in sorted(scores_by_judge.items())}
    judge_mean_list = list(judge_means.values())
    overall_pct = statistics.fmean(judge_mean_list)
    judge_stddev_pct = statistics.stdev(judge_mean_list) if len(judge_mean_list) >= 2 else 0.0

    per_question: dict[str, dict[str, Any]] = {}
    q_stddevs: list[float] = []
    for qid, vals in sorted(scores_by_question.items()):
        q_std = statistics.stdev(vals) if len(vals) >= 2 else 0.0
        q_stddevs.append(q_std)
        per_question[qid] = {
            "mean_pct": round(statistics.fmean(vals), 2),
            "median_pct": round(statistics.median(vals), 2),
            "judge_stddev_pct": round(q_std, 2),
            "n_judges": len(vals),
        }

    total_evals = len(evaluations)
    error_rates_pct = {
        k: round((v / total_evals) * 100.0, 2) for k, v in error_counts.items()
    }
    return {
        "judge_count": len(distinct_judges),
        "judges": distinct_judges,
        "model_families": distinct_families,
        "judge_overall_pct": {k: round(v, 2) for k, v in judge_means.items()},
        "overall_pct": round(overall_pct, 2),
        "judge_stddev_pct": round(judge_stddev_pct, 2),
        "mean_per_question_judge_stddev_pct": round(statistics.fmean(q_stddevs), 2) if q_stddevs else 0.0,
        "meets_stddev_target": judge_stddev_pct <= JUDGE_STDDEV_TARGET_PCT,
        "stddev_target_pct": JUDGE_STDDEV_TARGET_PCT,
        "error_taxonomy_counts": error_counts,
        "error_taxonomy_rates_pct": error_rates_pct,
        "per_question": per_question,
    }


# ---------------------------------------------------------------------------
# 4. Built-In Tier 1 (45-Q Chinthareddy) & Tier 2 (Polyglot RepoProbe) Suites
# ---------------------------------------------------------------------------


def _mk_three_item_checklist(
    prefix: str,
    item1: tuple[str, int, tuple[str, ...]],
    item2: tuple[str, int, tuple[str, ...]],
    item3: tuple[str, int, tuple[str, ...]],
) -> tuple[ChecklistItem, ...]:
    items = []
    for idx, (desc, pts, evs) in enumerate((item1, item2, item3), start=1):
        items.append(
            ChecklistItem(
                item_id=f"{prefix}_k{idx}",
                description=desc,
                max_points=pts,
                allowed_scores=tuple(range(0, pts + 1)),
                required_evidence=evs,
            )
        )
    return tuple(items)


def build_tier1_chinthareddy_45_suite() -> list[ComprehensionQuestion]:
    """Constructs the 45-question Java Enterprise architectural comprehension suite
    modeled on Chinthareddy-DKB (15 Shopizer 6a4a0a65 + 15 ThingsBoard + 15 OpenMRS Core),
    upgraded from single-rater subjective grading to strict RepoProbe 9+1 atomic checklists.
    """
    specs: list[tuple[str, str, str, str, bool, str, tuple[str, ...]]] = [
        # --- Shopizer (6a4a0a65): 15 questions ---
        ("shopizer-6a4a0a65", "spring_wiring", "How are catalog ProductService and MerchantStoreService wired across sm-core and sm-shop?",
         "sm-shop/src/main/java/com/salesmanager/shop/store/api/v1/product/ProductApi.java", False, "", ("ProductApi", "ProductService", "MerchantStoreService")),
        ("shopizer-6a4a0a65", "data_flow", "Trace the checkout order placement flow from ShoppingCartApi through OrderFacade and OrderService to JPA persistence.",
         "sm-shop/src/main/java/com/salesmanager/shop/store/api/v1/order/OrderApi.java", False, "", ("OrderApi", "OrderFacade", "OrderService")),
        ("shopizer-6a4a0a65", "extension_point", "How does PaymentService dynamically select and invoke configured PaymentModule implementations?",
         "sm-core/src/main/java/com/salesmanager/core/business/services/payments/PaymentServiceImpl.java", False, "", ("PaymentServiceImpl", "PaymentModule")),
        ("shopizer-6a4a0a65", "config_resolution", "How do you switch the active search indexing backend or disable search indexing without modifying Java service code?",
         "sm-shop/src/main/resources/application.properties", True, "config.index.products", ("application.properties", "config.index.products")),
        ("shopizer-6a4a0a65", "security_auth", "Trace JWT authentication filter registration and role authorization in the v1 REST API security chain.",
         "sm-shop/src/main/java/com/salesmanager/shop/store/security/JWTAdminAuthenticationManager.java", False, "", ("JWTAdminAuthenticationManager", "SecurityConfig")),
        ("shopizer-6a4a0a65", "module_boundary", "Which modules depend on sm-core-model and how are DTO/populator boundaries enforced in sm-shop?",
         "sm-shop/src/main/java/com/salesmanager/shop/populator/AbstractDataPopulator.java", False, "", ("sm-core-model", "AbstractDataPopulator")),
        ("shopizer-6a4a0a65", "dependency_trace", "Trace category hierarchy resolution from CategoryApi to CategoryRepository and cache eviction boundaries.",
         "sm-shop/src/main/java/com/salesmanager/shop/store/api/v1/category/CategoryApi.java", False, "", ("CategoryApi", "CategoryService", "CategoryRepository")),
        ("shopizer-6a4a0a65", "config_resolution", "How is the database dialect and Hibernate DDL strategy configured across profiles without editing Java config classes?",
         "sm-shop/src/main/resources/application.properties", True, "db.dialect", ("application.properties", "db.dialect")),
        ("shopizer-6a4a0a65", "data_flow", "How does PricingService compute product final prices, discounts, and currency formatting?",
         "sm-core/src/main/java/com/salesmanager/core/business/services/catalog/pricing/PricingServiceImpl.java", False, "", ("PricingServiceImpl", "FinalPrice")),
        ("shopizer-6a4a0a65", "extension_point", "How are shipping quote modules discovered and chained by ShippingServiceImpl?",
         "sm-core/src/main/java/com/salesmanager/core/business/services/shipping/ShippingServiceImpl.java", False, "", ("ShippingServiceImpl", "ShippingQuoteModule")),
        ("shopizer-6a4a0a65", "spring_wiring", "How are EmailService and FreeMarker templates configured for transactional order emails?",
         "sm-core/src/main/java/com/salesmanager/core/business/services/system/EmailServiceImpl.java", False, "", ("EmailServiceImpl", "HtmlEmailSender")),
        ("shopizer-6a4a0a65", "dependency_trace", "Trace customer registration validation, password encoding, and group assignment across facades and services.",
         "sm-shop/src/main/java/com/salesmanager/shop/store/controller/customer/facade/CustomerFacadeImpl.java", False, "", ("CustomerFacadeImpl", "CustomerService")),
        ("shopizer-6a4a0a65", "config_resolution", "How can CORS allowed origins and store default language be changed via configuration rather than controller code?",
         "sm-shop/src/main/resources/application.properties", True, "config.cors.allowedOrigins", ("application.properties", "config.cors.allowedOrigins")),
        ("shopizer-6a4a0a65", "data_flow", "Trace tax calculation from TaxServiceImpl through TaxRate and MerchantStore tax class mappings.",
         "sm-core/src/main/java/com/salesmanager/core/business/services/tax/TaxServiceImpl.java", False, "", ("TaxServiceImpl", "TaxRateService")),
        ("shopizer-6a4a0a65", "module_boundary", "How does sm-core-modules isolate CMS asset storage providers from core catalog services?",
         "sm-core-modules/src/main/java/com/salesmanager/core/modules/cms/product/ProductFileManager.java", False, "", ("ProductFileManager", "StaticContentFileManager")),
        # --- ThingsBoard: 15 questions ---
        ("thingsboard", "data_flow", "Trace device telemetry ingestion from TransportService through TbQueueProducer into the Rule Engine actor system.",
         "common/transport/transport-api/src/main/java/org/thingsboard/server/common/transport/service/DefaultTransportService.java", False, "", ("DefaultTransportService", "TbQueueProducer")),
        ("thingsboard", "spring_wiring", "How are monolith vs microservice queue transport beans selected via Spring @ConditionalOnProperty annotations?",
         "application/src/main/java/org/thingsboard/server/service/queue/DefaultTbCoreConsumerService.java", False, "", ("DefaultTbCoreConsumerService", "@ConditionalOnProperty")),
        ("thingsboard", "config_resolution", "How do you switch the message queue backend (in-memory vs Kafka) via application configuration without patching Java classes?",
         "application/src/main/resources/thingsboard.yml", True, "queue.type", ("thingsboard.yml", "queue.type")),
        ("thingsboard", "extension_point", "How does ComponentDiscoveryService scan and register custom Rule Node implementations?",
         "application/src/main/java/org/thingsboard/server/service/component/AnnotationComponentDiscoveryService.java", False, "", ("AnnotationComponentDiscoveryService", "RuleNode")),
        ("thingsboard", "dependency_trace", "Trace ActorSystemContext initialization and tenant/device actor hierarchy creation.",
         "application/src/main/java/org/thingsboard/server/actors/ActorSystemContext.java", False, "", ("ActorSystemContext", "AppActor", "TenantActor")),
        ("thingsboard", "security_auth", "Trace JWT token validation, tenant isolation, and AccessControlService authorization checks on REST endpoints.",
         "application/src/main/java/org/thingsboard/server/service/security/permission/DefaultAccessControlService.java", False, "", ("DefaultAccessControlService", "JwtTokenAuthenticationProcessingFilter")),
        ("thingsboard", "data_flow", "Trace time-series persistence routing between SQL and Cassandra Dao implementations.",
         "dao/src/main/java/org/thingsboard/server/dao/timeseries/BaseTimeseriesService.java", False, "", ("BaseTimeseriesService", "TimeseriesDao")),
        ("thingsboard", "config_resolution", "How can telemetry TTL retention and rate-limiting thresholds be adjusted via configuration instead of code edits?",
         "application/src/main/resources/thingsboard.yml", True, "sql.ttl.ts.ts_key_value_ttl", ("thingsboard.yml", "sql.ttl.ts.ts_key_value_ttl")),
        ("thingsboard", "module_boundary", "Explain the dependency boundaries between common/data, dao, rule-engine, and application modules.",
         "dao/src/main/java/org/thingsboard/server/dao/device/DeviceServiceImpl.java", False, "", ("common/data", "dao", "rule-engine")),
        ("thingsboard", "spring_wiring", "How are MQTT, HTTP, and CoAP transport services conditionally enabled in the Spring context?",
         "common/transport/mqtt/src/main/java/org/thingsboard/server/transport/mqtt/MqttTransportService.java", False, "", ("MqttTransportService", "@TbTransportComponent")),
        ("thingsboard", "data_flow", "Trace alarm creation, propagation, and notification dispatch from AlarmServiceImpl to WebSocket subscriptions.",
         "dao/src/main/java/org/thingsboard/server/dao/alarm/BaseAlarmService.java", False, "", ("BaseAlarmService", "AlarmSubscriptionService")),
        ("thingsboard", "dependency_trace", "Trace OTA firmware package upload and device attribute delivery through OtaPackageService.",
         "dao/src/main/java/org/thingsboard/server/dao/ota/BaseOtaPackageService.java", False, "", ("BaseOtaPackageService", "AttributesService")),
        ("thingsboard", "config_resolution", "How do you enable Redis caching for entities and attributes using configuration flags instead of modifying cache beans?",
         "application/src/main/resources/thingsboard.yml", True, "cache.type", ("thingsboard.yml", "cache.type")),
        ("thingsboard", "extension_point", "How does TbRuleChainActor route messages across rule nodes and handle failure/success relations?",
         "application/src/main/java/org/thingsboard/server/actors/ruleChain/RuleChainActorMessageProcessor.java", False, "", ("RuleChainActorMessageProcessor", "RuleNodeCtx")),
        ("thingsboard", "data_flow", "Trace RPC command dispatch from RpcController to DeviceActor and back to the device transport session.",
         "application/src/main/java/org/thingsboard/server/controller/RpcV2Controller.java", False, "", ("RpcV2Controller", "TbCoreDeviceRpcService")),
        # --- OpenMRS Core: 15 questions ---
        ("openmrs-core", "spring_wiring", "How does Context.java bridge static service accessors with Spring ApplicationContext and ServiceContext?",
         "api/src/main/java/org/openmrs/api/context/Context.java", False, "", ("Context", "ServiceContext")),
        ("openmrs-core", "data_flow", "Trace Patient registration and identifier validation from PatientServiceImpl to HibernatePatientDAO.",
         "api/src/main/java/org/openmrs/api/impl/PatientServiceImpl.java", False, "", ("PatientServiceImpl", "HibernatePatientDAO")),
        ("openmrs-core", "security_auth", "How does AuthorizationAdvice enforce @Authorized privilege checks via Spring AOP proxies?",
         "api/src/main/java/org/openmrs/aop/AuthorizationAdvice.java", False, "", ("AuthorizationAdvice", "@Authorized")),
        ("openmrs-core", "extension_point", "How does ModuleFactory discover, classload, and wire Spring application contexts from external OMOD modules?",
         "api/src/main/java/org/openmrs/module/ModuleFactory.java", False, "", ("ModuleFactory", "ModuleClassLoader")),
        ("openmrs-core", "config_resolution", "How are database connection pooling and Lucene/Hibernate Search index paths configured without modifying core Java code?",
         "api/src/main/resources/applicationContext-service.xml", True, "hibernate.search.default.indexBase", ("applicationContext-service.xml", "hibernate.search.default.indexBase")),
        ("openmrs-core", "data_flow", "Trace Encounter saving, Observation grouping, and RequiredDataAdvice auditing hooks.",
         "api/src/main/java/org/openmrs/api/impl/EncounterServiceImpl.java", False, "", ("EncounterServiceImpl", "RequiredDataAdvice")),
        ("openmrs-core", "dependency_trace", "Trace Concept dictionary lookup, ConceptName localization, and HibernateConceptDAO caching.",
         "api/src/main/java/org/openmrs/api/impl/ConceptServiceImpl.java", False, "", ("ConceptServiceImpl", "HibernateConceptDAO")),
        ("openmrs-core", "module_boundary", "Explain the layering and dependency constraints between openmrs-api and openmrs-web modules.",
         "web/src/main/java/org/openmrs/web/Listener.java", False, "", ("openmrs-api", "openmrs-web", "Listener")),
        ("openmrs-core", "data_flow", "Trace Order entry validation, drug order dosing rules, and OrderNumberGenerator invocation in OrderServiceImpl.",
         "api/src/main/java/org/openmrs/api/impl/OrderServiceImpl.java", False, "", ("OrderServiceImpl", "OrderNumberGenerator")),
        ("openmrs-core", "config_resolution", "How can scheduled daemon task intervals or auto-update database liquibase flags be controlled via runtime properties?",
         "api/src/main/java/org/openmrs/util/OpenmrsConstants.java", True, "auto_update_database", ("OpenmrsConstants", "auto_update_database")),
        ("openmrs-core", "spring_wiring", "How are Hibernate interceptors (AuditableInterceptor, ChainingInterceptor) wired into the SessionFactory?",
         "api/src/main/java/org/openmrs/api/db/hibernate/ChainingInterceptor.java", False, "", ("ChainingInterceptor", "AuditableInterceptor")),
        ("openmrs-core", "extension_point", "How does Daemon.runInDaemonThread execute background module tasks with elevated privileges?",
         "api/src/main/java/org/openmrs/api/context/Daemon.java", False, "", ("Daemon", "DaemonThread")),
        ("openmrs-core", "dependency_trace", "Trace Visit lifecycle management and Encounter-to-Visit assignment handlers.",
         "api/src/main/java/org/openmrs/api/impl/VisitServiceImpl.java", False, "", ("VisitServiceImpl", "BaseEncounterVisitHandler")),
        ("openmrs-core", "config_resolution", "How do you toggle global encryption/hashing or password minimum length settings via global properties rather than code edits?",
         "api/src/main/java/org/openmrs/util/Security.java", True, "security.passwordMinimumLength", ("Security", "security.passwordMinimumLength")),
        ("openmrs-core", "data_flow", "Trace Cohort membership queries and ObsService filtering across patient cohorts.",
         "api/src/main/java/org/openmrs/api/impl/ObsServiceImpl.java", False, "", ("ObsServiceImpl", "CohortServiceImpl")),
    ]

    questions: list[ComprehensionQuestion] = []
    for idx, (repo, cat, prompt, anchor_path, cfg_only, cfg_key, anchors) in enumerate(specs, start=1):
        qid = f"T1-Q{idx:02d}"
        if cfg_only:
            items = _mk_three_item_checklist(
                qid,
                (f"Identifies exact configuration key `{cfg_key}` and its declaring location ({anchor_path}).", 4, (cfg_key, anchor_path)),
                ("Explains how the configuration switch controls runtime behavior without invasive code edits (CONFIG_ONLY_RESOLUTION).", 3, (cfg_key,)),
                ("Cites verifiable snapshot file/symbol evidence with accurate line bounds.", 2, anchors),
            )
        else:
            items = _mk_three_item_checklist(
                qid,
                (f"Accurately identifies primary architectural entry point and core collaborating symbols ({', '.join(anchors[:2])}).", 4, anchors[:2]),
                ("Explains cross-layer/module wiring, control/data flow, or Spring/AOP boundary conditions accurately.", 3, anchors),
                ("Provides verified file/symbol provenance and explicitly notes any conditional or partial boundaries.", 2, (anchor_path,)),
            )
        q = ComprehensionQuestion(
            question_id=qid,
            tier="tier1_chinthareddy_45",
            repo=repo,
            language="java",
            category=cat,
            question=prompt,
            checklist_items=items,
            config_only_expected=cfg_only,
            expected_config_key=cfg_key,
            expected_citations=(f"{anchor_path}:1",),
            split="holdout",
        )
        validate_checklist(q)
        questions.append(q)
    return questions


def build_tier2_repoprobe_polyglot_suite() -> list[ComprehensionQuestion]:
    """Constructs the Tier-2 Polyglot RepoProbe paired comprehension suite spanning
    Java/Spring, Python, TypeScript, Go, and Rust with explicit dev/holdout splits
    and anti-Edit-Bias CONFIG_ONLY_RESOLUTION traps.
    """
    raw_specs: list[tuple[str, str, str, str, str, bool, str, str, str]] = [
        # (qid, repo, lang, category, question, config_only, config_key, anchor_path, split)
        ("T2-JAVA-01", "spring-gateway-mesh", "java-spring", "config_resolution",
         "How do we enable HTTP response gzip compression on the API gateway without adding a custom ServletFilter?",
         True, "server.compression.enabled", "src/main/resources/application.yml", "holdout"),
        ("T2-JAVA-02", "spring-gateway-mesh", "java-spring", "spring_wiring",
         "Which bean implements PaymentProcessor under the `prod` profile vs `dev` profile, and what condition guards it?",
         False, "", "src/main/java/com/acme/pay/PaymentAutoConfig.java", "holdout"),
        ("T2-JAVA-03", "spring-gateway-mesh", "java-spring", "config_resolution",
         "How do we activate the batch-reconciliation Spring profile in staging instead of editing @Profile annotations?",
         True, "spring.profiles.active", "src/main/resources/application-staging.yml", "holdout"),
        ("T2-JAVA-04", "spring-gateway-mesh", "java-spring", "dependency_trace",
         "Trace how OrderController invokes InventoryClient and where circuit-breaker fallback routing is declared.",
         False, "", "src/main/java/com/acme/order/OrderController.java", "dev"),
        ("T2-JAVA-05", "spring-gateway-mesh", "java-spring", "config_resolution",
         "How do we increase retry max attempts for billing webhooks using existing @ConfigurationProperties instead of patching RetryClient?",
         True, "billing.webhook.max-attempts", "src/main/java/com/acme/billing/WebhookProperties.java", "holdout"),
        ("T2-JAVA-06", "spring-gateway-mesh", "java-spring", "data_flow",
         "Trace the vertical path from POST /api/v1/invoices through InvoiceService to the SQL invoices table.",
         False, "", "src/main/java/com/acme/billing/InvoiceController.java", "dev"),
        ("T2-PY-01", "fastapi-analytics-svc", "python", "config_resolution",
         "How do we raise the worker concurrency limit via environment configuration instead of editing uvicorn startup code?",
         True, "ANALYTICS_WORKER_CONCURRENCY", "app/config/settings.py", "holdout"),
        ("T2-PY-02", "fastapi-analytics-svc", "python", "data_flow",
         "Trace how ingestion payloads flow from routers/events.py through validator middleware to storage/parquet_writer.py.",
         False, "", "app/routers/events.py", "holdout"),
        ("T2-PY-03", "fastapi-analytics-svc", "python", "config_resolution",
         "How do we disable telemetry sampling in CI using existing env flags instead of patching middleware?",
         True, "TELEMETRY_SAMPLING_ENABLED", "app/observability/tracer.py", "dev"),
        ("T2-PY-04", "fastapi-analytics-svc", "python", "module_boundary",
         "Which modules import domain/metrics_schema.py and how are circular imports prevented between pipelines and API?",
         False, "", "app/domain/metrics_schema.py", "holdout"),
        ("T2-TS-01", "next-commerce-web", "typescript", "config_resolution",
         "How do we toggle experimental checkout streaming via existing env feature flag without changing CheckoutPage.tsx?",
         True, "NEXT_PUBLIC_FEATURE_STREAMING_CHECKOUT", "src/config/flags.ts", "holdout"),
        ("T2-TS-02", "next-commerce-web", "typescript", "data_flow",
         "Trace how CartContext dispatches mutations to api/cartClient.ts and handles optimistic rollback on 409 conflict.",
         False, "", "src/context/CartContext.tsx", "holdout"),
        ("T2-TS-03", "next-commerce-web", "typescript", "module_boundary",
         "How do workspace path aliases in tsconfig.json map @shared/contracts to packages/contracts/src?",
         False, "", "tsconfig.json", "dev"),
        ("T2-TS-04", "next-commerce-web", "typescript", "security_auth",
         "Trace CSRF token propagation from auth/sessionHook.ts into fetchWithAuth request headers.",
         False, "", "src/auth/fetchWithAuth.ts", "dev"),
        ("T2-GO-01", "go-ledger-router", "go", "config_resolution",
         "How do we adjust gRPC keepalive timeout via existing env config instead of editing server.go dial options?",
         True, "GRPC_KEEPALIVE_TIMEOUT_MS", "internal/config/env.go", "holdout"),
        ("T2-GO-02", "go-ledger-router", "go", "data_flow",
         "Trace ledger transfer requests from grpc/handler.go through service/settlement.go to postgres/tx_store.go.",
         False, "", "internal/grpc/handler.go", "holdout"),
        ("T2-GO-03", "go-ledger-router", "go", "extension_point",
         "Which concrete adapters satisfy the LedgerNotifier interface and where are they registered at startup?",
         False, "", "internal/notify/notifier.go", "dev"),
        ("T2-RS-01", "rust-index-engine", "rust", "config_resolution",
         "How do we cap memory-mapped segment cache size using existing config/env settings without editing storage/mmap.rs?",
         True, "INDEX_MMAP_CACHE_MB", "src/config.rs", "holdout"),
        ("T2-RS-02", "rust-index-engine", "rust", "data_flow",
         "Trace query execution from query/planner.rs through index/inverted.rs to scoring/bm25.rs.",
         False, "", "src/query/planner.rs", "holdout"),
        ("T2-RS-03", "rust-index-engine", "rust", "module_boundary",
         "How are unsafe FFI bindings isolated inside crate::ffi and exposed safely to crate::parser?",
         False, "", "src/ffi/mod.rs", "dev"),
    ]

    questions: list[ComprehensionQuestion] = []
    for qid, repo, lang, cat, prompt, cfg_only, cfg_key, anchor_path, split in raw_specs:
        if cfg_only:
            items = (
                ChecklistItem(
                    item_id=f"{qid}_k1",
                    description=f"Identifies exact existing config/env key `{cfg_key}` at `{anchor_path}`.",
                    max_points=4,
                    allowed_scores=(0, 2, 4),
                    required_evidence=(cfg_key, anchor_path),
                ),
                ChecklistItem(
                    item_id=f"{qid}_k2",
                    description="Recommends CONFIG_ONLY_RESOLUTION without proposing invasive source code patches.",
                    max_points=3,
                    allowed_scores=(0, 1, 3),
                    required_evidence=(cfg_key,),
                ),
                ChecklistItem(
                    item_id=f"{qid}_k3",
                    description="Attaches verifiable file:line citation matching captured snapshot bounds.",
                    max_points=2,
                    allowed_scores=(0, 1, 2),
                    required_evidence=(anchor_path,),
                ),
            )
        else:
            items = (
                ChecklistItem(
                    item_id=f"{qid}_k1",
                    description=f"Identifies primary architectural components and control/data flow anchored at `{anchor_path}`.",
                    max_points=4,
                    allowed_scores=(0, 1, 2, 3, 4),
                    required_evidence=(anchor_path,),
                ),
                ChecklistItem(
                    item_id=f"{qid}_k2",
                    description="Explains cross-module/interface dependencies, guards, or error-handling semantics accurately.",
                    max_points=3,
                    allowed_scores=(0, 1, 2, 3),
                    required_evidence=(anchor_path,),
                ),
                ChecklistItem(
                    item_id=f"{qid}_k3",
                    description="Provides verifiable file:line evidence citations without hallucinated paths.",
                    max_points=2,
                    allowed_scores=(0, 1, 2),
                    required_evidence=(anchor_path,),
                ),
            )
        q = ComprehensionQuestion(
            question_id=qid,
            tier="tier2_repoprobe_polyglot",
            repo=repo,
            language=lang,
            category=cat,
            question=prompt,
            checklist_items=items,
            config_only_expected=cfg_only,
            expected_config_key=cfg_key,
            expected_citations=(f"{anchor_path}:12",),
            split=split,
        )
        validate_checklist(q)
        questions.append(q)
    return questions


# ---------------------------------------------------------------------------
# 5. Tier Evaluation & Paired Raw-vs-Evo Comparison
# ---------------------------------------------------------------------------


def evaluate_variant_suite(
    questions: list[ComprehensionQuestion],
    answers_by_qid: dict[str, dict[str, Any]],
    judge_evaluations: list[JudgeEvaluation],
    skg_file_lines: dict[str, int] | None = None,
    repo_root: Path | None = None,
    strict_cross_family: bool = True,
) -> dict[str, Any]:
    """Evaluates a single system variant across a question suite."""
    for q in questions:
        validate_checklist(q)

    agg = aggregate_cross_family_judges(judge_evaluations, strict_cross_family=strict_cross_family)

    q_by_id = {q.question_id: q for q in questions}
    tags_by_qid: dict[str, list[str]] = {}
    for ev in judge_evaluations:
        tags_by_qid.setdefault(ev.question_id, []).extend(ev.validated_response.get("error_tags", []))

    edit_bias_hits = 0
    total_cites = 0
    valid_cites = 0
    config_only_correct = 0
    config_only_total = 0

    for q in questions:
        ans_rec = answers_by_qid.get(q.question_id, {})
        ans_text = str(ans_rec.get("answer", ""))
        gate_decision = None
        if isinstance(ans_rec.get("preEditConfigGate"), dict):
            gate_decision = ans_rec["preEditConfigGate"].get("decision")
        eb = detect_edit_bias(
            ans_text,
            config_only_expected=q.config_only_expected,
            pre_edit_gate_decision=gate_decision,
            judge_error_tags=tags_by_qid.get(q.question_id, []),
        )
        if eb["edit_bias"]:
            edit_bias_hits += 1
        if q.config_only_expected:
            config_only_total += 1
            if not eb["edit_bias"] and (
                gate_decision == "CONFIG_ONLY_RESOLUTION"
                or (q.expected_config_key and q.expected_config_key.lower() in ans_text.lower())
            ):
                config_only_correct += 1

        cit = verify_answer_citations(
            ans_text,
            skg_file_lines=skg_file_lines,
            repo_root=repo_root,
            evo_citation_verification=ans_rec.get("citationVerification"),
        )
        total_cites += cit["total_citations"]
        valid_cites += cit["valid_citations"]

    n_q = max(1, len(questions))
    edit_bias_rate_pct = round((edit_bias_hits / n_q) * 100.0, 2)
    citation_validity_pct = round((valid_cites / total_cites) * 100.0, 2) if total_cites > 0 else 0.0
    dkb_parity_count = sum(1 for qid, row in agg["per_question"].items() if qid in q_by_id and row["mean_pct"] >= 80.0)

    return {
        "question_count": len(questions),
        "overall_pct": agg["overall_pct"],
        "judge_stddev_pct": agg["judge_stddev_pct"],
        "mean_per_question_judge_stddev_pct": agg["mean_per_question_judge_stddev_pct"],
        "meets_stddev_target": agg["meets_stddev_target"],
        "edit_bias_count": edit_bias_hits,
        "edit_bias_rate_pct": edit_bias_rate_pct,
        "config_only_resolved_count": config_only_correct,
        "config_only_total": config_only_total,
        "total_citations": total_cites,
        "valid_citations": valid_cites,
        "citation_validity_pct": citation_validity_pct,
        "passing_questions_ge_80pct": dkb_parity_count,
        "judge_panel": {
            "judges": agg["judges"],
            "model_families": agg["model_families"],
            "judge_overall_pct": agg["judge_overall_pct"],
        },
        "error_taxonomy_rates_pct": agg["error_taxonomy_rates_pct"],
        "per_question": agg["per_question"],
    }


def evaluate_paired_tier2(
    questions: list[ComprehensionQuestion],
    raw_answers: dict[str, dict[str, Any]],
    evo_answers: dict[str, dict[str, Any]],
    raw_evals: list[JudgeEvaluation],
    evo_evals: list[JudgeEvaluation],
    skg_file_lines: dict[str, int] | None = None,
    repo_root: Path | None = None,
    strict_cross_family: bool = True,
) -> dict[str, Any]:
    """Computes paired Raw File/Grep vs Evo MCP metrics on the exact same backbone LLM,
    including Overall delta (pp), relative Edit Bias reduction %, Citation Validity delta,
    per-language breakdown, and isolated holdout-split metrics.
    """
    raw_res = evaluate_variant_suite(
        questions, raw_answers, raw_evals, skg_file_lines, repo_root, strict_cross_family
    )
    evo_res = evaluate_variant_suite(
        questions, evo_answers, evo_evals, skg_file_lines, repo_root, strict_cross_family
    )

    overall_delta_pp = round(evo_res["overall_pct"] - raw_res["overall_pct"], 2)
    citation_delta_pp = round(evo_res["citation_validity_pct"] - raw_res["citation_validity_pct"], 2)
    if raw_res["edit_bias_rate_pct"] > 0:
        edit_bias_rel_reduction_pct = round(
            ((raw_res["edit_bias_rate_pct"] - evo_res["edit_bias_rate_pct"]) / raw_res["edit_bias_rate_pct"]) * 100.0,
            2,
        )
    else:
        edit_bias_rel_reduction_pct = 0.0

    # Per-language paired breakdown
    langs = sorted({q.language for q in questions})
    by_language: dict[str, dict[str, Any]] = {}
    for lang in langs:
        sub_qs = [q for q in questions if q.language == lang]
        sub_qids = {q.question_id for q in sub_qs}
        sub_raw_pct = statistics.fmean(raw_res["per_question"][qid]["mean_pct"] for qid in sub_qids)
        sub_evo_pct = statistics.fmean(evo_res["per_question"][qid]["mean_pct"] for qid in sub_qids)
        by_language[lang] = {
            "questions": len(sub_qs),
            "raw_overall_pct": round(sub_raw_pct, 2),
            "evo_overall_pct": round(sub_evo_pct, 2),
            "delta_pp": round(sub_evo_pct - sub_raw_pct, 2),
        }

    # Holdout-only slice (anti-overfitting guardrail)
    holdout_qs = [q for q in questions if q.split == "holdout"]
    holdout_summary: dict[str, Any] = {}
    if holdout_qs:
        h_ids = {q.question_id for q in holdout_qs}
        h_raw = evaluate_variant_suite(
            holdout_qs,
            {k: v for k, v in raw_answers.items() if k in h_ids},
            [e for e in raw_evals if e.question_id in h_ids],
            skg_file_lines,
            repo_root,
            strict_cross_family,
        )
        h_evo = evaluate_variant_suite(
            holdout_qs,
            {k: v for k, v in evo_answers.items() if k in h_ids},
            [e for e in evo_evals if e.question_id in h_ids],
            skg_file_lines,
            repo_root,
            strict_cross_family,
        )
        holdout_summary = {
            "question_count": len(holdout_qs),
            "raw_overall_pct": h_raw["overall_pct"],
            "evo_overall_pct": h_evo["overall_pct"],
            "overall_delta_pp": round(h_evo["overall_pct"] - h_raw["overall_pct"], 2),
            "raw_edit_bias_rate_pct": h_raw["edit_bias_rate_pct"],
            "evo_edit_bias_rate_pct": h_evo["edit_bias_rate_pct"],
            "evo_citation_validity_pct": h_evo["citation_validity_pct"],
            "evo_judge_stddev_pct": h_evo["judge_stddev_pct"],
        }

    return {
        "raw_file_grep": raw_res,
        "evo_mcp": evo_res,
        "paired_delta": {
            "overall_delta_pp": overall_delta_pp,
            "edit_bias_relative_reduction_pct": edit_bias_rel_reduction_pct,
            "citation_validity_delta_pp": citation_delta_pp,
            "evo_meets_stddev_target": evo_res["meets_stddev_target"],
        },
        "by_language": by_language,
        "holdout_only": holdout_summary,
    }


# ---------------------------------------------------------------------------
# 6. Offline Deterministic --dry-run Mode (Explicitly DRY_RUN_SYNTHETIC)
# ---------------------------------------------------------------------------


def _synthetic_judge_json(
    question: ComprehensionQuestion,
    item_scores: list[int],
    clarity: int,
    error_tags: list[str],
    judge_id: str,
) -> str:
    items_payload = []
    for item, sc in zip(question.checklist_items, item_scores):
        items_payload.append(
            {
                "item_id": item.item_id,
                "rationale": f"[{judge_id}] Verified atomic criterion '{item.item_id}' against captured SKG evidence.",
                "score": sc,
            }
        )
    payload = {
        "items": items_payload,
        "clarity_rationale": f"[{judge_id}] Assessed answer conciseness, structure, and absence of speculative edits.",
        "clarity_score": clarity,
        "error_tags": error_tags,
        "total_score": sum(item_scores) + clarity,
    }
    return json.dumps(payload)


def _nearest_allowed(allowed: tuple[int, ...], target: int) -> int:
    return min(allowed, key=lambda x: (abs(x - target), -x))


def run_dry_run_evaluation() -> dict[str, Any]:
    """Executes a complete 100% deterministic offline dry-run of BOTH Tier 1 (45-Q) and
    Tier 2 (20-Q paired polyglot) with a 3-family synthetic judge panel (OpenAI + Google + Anthropic).
    Explicitly tagged DRY_RUN_SYNTHETIC so it is never confused with live LLM measurements.
    """
    tier1_qs = build_tier1_chinthareddy_45_suite()
    tier2_qs = build_tier2_repoprobe_polyglot_suite()

    # Build deterministic synthetic SKG line-count manifest covering every expected anchor
    skg_manifest: dict[str, int] = {}
    for q in tier1_qs + tier2_qs:
        for cit in q.expected_citations:
            path_part = cit.split(":", 1)[0]
            skg_manifest[path_part] = 240

    judges = [
        ("gpt-5.2-judge", "openai"),
        ("gemini-3-pro-judge", "google"),
        ("claude-opus-4.5-judge", "anthropic"),
    ]

    # --- Tier 1 (45-Q Evo MCP dry-run) ---
    t1_answers: dict[str, dict[str, Any]] = {}
    t1_evals: list[JudgeEvaluation] = []
    for idx, q in enumerate(tier1_qs):
        cite_str = q.expected_citations[0]
        if q.config_only_expected:
            ans = (
                f"CONFIG_ONLY_RESOLUTION: Set `{q.expected_config_key}` directly in {cite_str} [E1] "
                "instead of modifying Java application source code."
            )
            gate = {"decision": "CONFIG_ONLY_RESOLUTION", "blocksCodePatch": True}
        else:
            ans = (
                f"Verified architecture trace anchored at {cite_str} [E1]: "
                f"satisfies {q.category} invariants cleanly across module boundaries."
            )
            gate = {"decision": "ALLOW_CODE_EDIT", "blocksCodePatch": False}
        t1_answers[q.question_id] = {"id": q.question_id, "answer": ans, "preEditConfigGate": gate}

        for j_idx, (jid, fam) in enumerate(judges):
            # Deterministic small inter-judge spread (< 2.4% stddev)
            # Keep 44/45 questions >= 9/10 and 1 boundary question at 7/10 for realistic non-degenerate variance
            if idx == 44:
                base_scores = [_nearest_allowed(it.allowed_scores, max(0, it.max_points - 1)) for it in q.checklist_items]
                clarity = 1 if j_idx != 2 else 0
                tags = ["SHALLOW_EXPLANATION"] if clarity == 0 else []
            else:
                base_scores = [it.max_points for it in q.checklist_items]
                if j_idx == 2 and idx % 5 == 0:
                    last_it = q.checklist_items[-1]
                    base_scores[-1] = _nearest_allowed(last_it.allowed_scores, max(0, last_it.max_points - 1))
                clarity = 1
                tags = []
            raw_j = _synthetic_judge_json(q, base_scores, clarity, tags, jid)
            validated = validate_judge_response(q, raw_j)
            t1_evals.append(JudgeEvaluation(jid, fam, q.question_id, "evo_mcp", validated))

    tier1_report = evaluate_variant_suite(
        tier1_qs, t1_answers, t1_evals, skg_file_lines=skg_manifest, strict_cross_family=True
    )

    # --- Tier 2 (20-Q Paired Raw File/Grep vs Evo MCP dry-run) ---
    t2_raw_answers: dict[str, dict[str, Any]] = {}
    t2_evo_answers: dict[str, dict[str, Any]] = {}
    t2_raw_evals: list[JudgeEvaluation] = []
    t2_evo_evals: list[JudgeEvaluation] = []

    for idx, q in enumerate(tier2_qs):
        cite_str = q.expected_citations[0]
        if q.config_only_expected:
            # Raw baseline exhibits Edit Bias on 6 of 8 config-only questions (proposes code patch + 1 bad line ref)
            if idx % 4 != 3:
                raw_ans = (
                    "Modify the class and add a new method to customize this behavior:\n"
                    f"```java\n// Patch {cite_str}\npublic void overrideBehavior() {{\n"
                    "  enableCustomFlag();\n  applyOverride();\n  reloadState();\n}\n```\n"
                    f"Also check nonexistent/FakeHelper.java:999."
                )
                raw_tags = ["EDIT_BIAS", "HALLUCINATION"]
                raw_scores = [_nearest_allowed(it.allowed_scores, max(0, it.max_points - 2)) for it in q.checklist_items]
            else:
                raw_ans = f"Configure `{q.expected_config_key}` in {cite_str}."
                raw_tags = []
                raw_scores = [_nearest_allowed(it.allowed_scores, max(0, it.max_points - 1)) for it in q.checklist_items]

            evo_ans = (
                f"CONFIG_ONLY_RESOLUTION: Set `{q.expected_config_key}` at {cite_str} [E1] "
                "without editing application source code."
            )
            evo_gate = {"decision": "CONFIG_ONLY_RESOLUTION", "blocksCodePatch": True}
        else:
            raw_ans = (
                f"Observed partial flow near {cite_str}; additional cross-module wiring omitted."
                + ("" if idx % 3 != 0 else " See also src/unknown/Phantom.java:404.")
            )
            raw_tags = ["CONTEXT_MISS"] if idx % 2 == 0 else ["SHALLOW_EXPLANATION"]
            raw_scores = [_nearest_allowed(it.allowed_scores, max(0, it.max_points - 1)) for it in q.checklist_items]

            evo_ans = (
                f"Verified SKG ego-graph and vertical trace anchored at {cite_str} [E1]: "
                "captures full interface consumers, Spring/module boundaries, and exact span bounds."
            )
            evo_gate = {"decision": "ALLOW_CODE_EDIT", "blocksCodePatch": False}

        t2_raw_answers[q.question_id] = {"id": q.question_id, "answer": raw_ans}
        t2_evo_answers[q.question_id] = {"id": q.question_id, "answer": evo_ans, "preEditConfigGate": evo_gate}

        for j_idx, (jid, fam) in enumerate(judges):
            r_scores = list(raw_scores)
            if j_idx == 1 and idx % 4 == 0:
                r_scores[0] = _nearest_allowed(q.checklist_items[0].allowed_scores, max(0, r_scores[0] - 1))
            r_json = _synthetic_judge_json(q, r_scores, 1, raw_tags, jid)
            t2_raw_evals.append(JudgeEvaluation(jid, fam, q.question_id, "raw_grep", validate_judge_response(q, r_json)))

            e_scores = [it.max_points for it in q.checklist_items]
            if j_idx == 2 and idx % 5 == 0:
                e_scores[-1] = _nearest_allowed(q.checklist_items[-1].allowed_scores, max(0, e_scores[-1] - 1))
            e_json = _synthetic_judge_json(q, e_scores, 1, [], jid)
            t2_evo_evals.append(JudgeEvaluation(jid, fam, q.question_id, "evo_mcp", validate_judge_response(q, e_json)))

    tier2_report = evaluate_paired_tier2(
        tier2_qs,
        t2_raw_answers,
        t2_evo_answers,
        t2_raw_evals,
        t2_evo_evals,
        skg_file_lines=skg_manifest,
        strict_cross_family=True,
    )

    return {
        "schema": SCHEMA,
        "execution_mode": "DRY_RUN_SYNTHETIC",
        "epistemic_warning": (
            "Synthetic offline dry-run verifying checklist invariants, rationale-first parsing, "
            "Edit Bias detection, SKG citation verification, and cross-family StdDev aggregation. "
            "NOT a live frontier-LLM empirical measurement."
        ),
        "tier1_chinthareddy_45": tier1_report,
        "tier2_repoprobe_polyglot": tier2_report,
    }


def _load_jsonl_by_id(path: Path) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        s = raw.strip()
        if not s:
            continue
        obj = json.loads(s)
        qid = str(obj.get("id") or obj.get("question_id") or f"q-{line_no}")
        rows[qid] = obj
    return rows


def _load_judge_outputs(
    path: Path,
    questions_by_id: dict[str, ComprehensionQuestion],
) -> tuple[list[JudgeEvaluation], list[JudgeEvaluation], list[JudgeEvaluation]]:
    t1_evals: list[JudgeEvaluation] = []
    t2_raw_evals: list[JudgeEvaluation] = []
    t2_evo_evals: list[JudgeEvaluation] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        s = raw.strip()
        if not s:
            continue
        obj = json.loads(s)
        qid = str(obj["question_id"])
        q = questions_by_id[qid]
        raw_resp = obj["response"] if isinstance(obj["response"], (str, dict)) else json.dumps(obj["response"])
        validated = validate_judge_response(q, raw_resp)
        ev = JudgeEvaluation(
            judge_id=str(obj["judge_id"]),
            model_family=str(obj["model_family"]),
            question_id=qid,
            variant=str(obj.get("variant", "evo_mcp")),
            validated_response=validated,
        )
        if q.tier == "tier1_chinthareddy_45":
            t1_evals.append(ev)
        elif ev.variant == "raw_grep":
            t2_raw_evals.append(ev)
        else:
            t2_evo_evals.append(ev)
    return t1_evals, t2_raw_evals, t2_evo_evals


def render_markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# Two-Tier Architectural Comprehension Checklist Benchmark Report",
        "",
        f"- **Schema:** `{report['schema']}`",
        f"- **Execution Mode:** `{report['execution_mode']}`",
    ]
    if report.get("epistemic_warning"):
        lines.append(f"- **Epistemic Label:** {report['epistemic_warning']}")
    lines.append("")

    t1 = report.get("tier1_chinthareddy_45")
    if t1:
        lines.extend(
            [
                "## Tier 1 — 45-Question Java Enterprise Suite (Chinthareddy Schema + RepoProbe 10-Point Checklist)",
                "",
                "| Metric | Value |",
                "|---|---:|",
                f"| Questions Evaluated | {t1['question_count']} |",
                f"| Overall Score | {t1['overall_pct']:.2f}% |",
                f"| Passing (>= 80% / DKB Parity) | {t1['passing_questions_ge_80pct']}/{t1['question_count']} |",
                f"| Citation Validity | {t1['citation_validity_pct']:.2f}% ({t1['valid_citations']}/{t1['total_citations']}) |",
                f"| Edit Bias Rate | {t1['edit_bias_rate_pct']:.2f}% |",
                f"| Cross-Family Judge StdDev | {t1['judge_stddev_pct']:.2f}% (target <= {JUDGE_STDDEV_TARGET_PCT:.1f}%, met={t1['meets_stddev_target']}) |",
                "",
            ]
        )

    t2 = report.get("tier2_repoprobe_polyglot")
    if t2:
        raw = t2["raw_file_grep"]
        evo = t2["evo_mcp"]
        delta = t2["paired_delta"]
        lines.extend(
            [
                "## Tier 2 — Polyglot RepoProbe Paired Evaluation (`Raw File/Grep` vs `Evo MCP`)",
                "",
                "| Metric | Raw File/Grep | Evo MCP | Paired Delta |",
                "|---|---:|---:|---:|",
                f"| Overall Score (%) | {raw['overall_pct']:.2f}% | {evo['overall_pct']:.2f}% | {delta['overall_delta_pp']:+.2f} pp |",
                f"| Edit Bias Rate (%) | {raw['edit_bias_rate_pct']:.2f}% | {evo['edit_bias_rate_pct']:.2f}% | -{delta['edit_bias_relative_reduction_pct']:.1f}% rel |",
                f"| Citation Validity (%) | {raw['citation_validity_pct']:.2f}% | {evo['citation_validity_pct']:.2f}% | {delta['citation_validity_delta_pp']:+.2f} pp |",
                f"| Judge StdDev (%) | {raw['judge_stddev_pct']:.2f}% | {evo['judge_stddev_pct']:.2f}% | target <= {JUDGE_STDDEV_TARGET_PCT:.1f}% |",
                "",
            ]
        )
        if t2.get("by_language"):
            lines.extend(["### Per-Language Breakdown", "", "| Language | N | Raw % | Evo MCP % | Delta (pp) |", "|---|---:|---:|---:|---:|"])
            for lang, row in sorted(t2["by_language"].items()):
                lines.append(
                    f"| `{lang}` | {row['questions']} | {row['raw_overall_pct']:.2f}% | {row['evo_overall_pct']:.2f}% | {row['delta_pp']:+.2f} |"
                )
            lines.append("")
    return "\n".join(lines)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Two-Tier RepoProbe 10-Point Checklist & Chinthareddy 45-Q Comprehension Benchmark Harness"
    )
    parser.add_argument("--dry-run", action="store_true", help="Run offline deterministic dry-run evaluation")
    parser.add_argument("--export-questions", type=Path, default=None, help="Export Tier 1 + Tier 2 questions as JSONL for `evo ask --batch`")
    parser.add_argument("--tier1-answers", type=Path, default=None, help="JSONL answers from `evo ask` for Tier 1")
    parser.add_argument("--tier2-raw-answers", type=Path, default=None, help="JSONL answers for Tier 2 Raw File/Grep")
    parser.add_argument("--tier2-evo-answers", type=Path, default=None, help="JSONL answers for Tier 2 Evo MCP")
    parser.add_argument("--judge-outputs", type=Path, default=None, help="JSONL cross-family judge outputs")
    parser.add_argument("--skg-manifest", type=Path, default=None, help="JSON map of {rel_path: line_count} from SKG")
    parser.add_argument("--repo-root", type=Path, default=None, help="Optional passive read-only repo root for path:line verification")
    parser.add_argument("--out-dir", type=Path, default=None, help="Output directory for JSON and Markdown reports")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    if args.export_questions:
        all_qs = build_tier1_chinthareddy_45_suite() + build_tier2_repoprobe_polyglot_suite()
        args.export_questions.parent.mkdir(parents=True, exist_ok=True)
        lines = [json.dumps({"id": q.question_id, "question": q.question, "tier": q.tier, "repo": q.repo}) for q in all_qs]
        args.export_questions.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"[comprehension-checklist] exported {len(all_qs)} questions -> {args.export_questions}")
        if not args.dry_run and not args.judge_outputs:
            return 0

    if args.dry_run:
        report = run_dry_run_evaluation()
    else:
        if not args.judge_outputs:
            print("[comprehension-checklist] Specify --dry-run or --judge-outputs <jsonl>", file=sys.stderr)
            return 2
        t1_qs = build_tier1_chinthareddy_45_suite()
        t2_qs = build_tier2_repoprobe_polyglot_suite()
        q_map = {q.question_id: q for q in t1_qs + t2_qs}
        skg_manifest = json.loads(args.skg_manifest.read_text(encoding="utf-8")) if args.skg_manifest else None
        t1_evals, t2_raw_evals, t2_evo_evals = _load_judge_outputs(args.judge_outputs, q_map)

        report = {"schema": SCHEMA, "execution_mode": "LIVE_EVALUATION"}
        if args.tier1_answers and t1_evals:
            t1_ans = _load_jsonl_by_id(args.tier1_answers)
            t1_present = [q for q in t1_qs if q.question_id in t1_ans]
            report["tier1_chinthareddy_45"] = evaluate_variant_suite(
                t1_present, t1_ans, t1_evals, skg_manifest, args.repo_root, strict_cross_family=True
            )
        if args.tier2_raw_answers and args.tier2_evo_answers and t2_raw_evals and t2_evo_evals:
            t2_raw = _load_jsonl_by_id(args.tier2_raw_answers)
            t2_evo = _load_jsonl_by_id(args.tier2_evo_answers)
            t2_present = [q for q in t2_qs if q.question_id in t2_raw and q.question_id in t2_evo]
            report["tier2_repoprobe_polyglot"] = evaluate_paired_tier2(
                t2_present, t2_raw, t2_evo, t2_raw_evals, t2_evo_evals, skg_manifest, args.repo_root, strict_cross_family=True
            )

    md = render_markdown_report(report)
    if args.out_dir:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        (args.out_dir / "comprehension_checklist_report.json").write_text(
            json.dumps(report, indent=2, sort_keys=True), encoding="utf-8"
        )
        (args.out_dir / "comprehension_checklist_report.md").write_text(md, encoding="utf-8")

    t1 = report.get("tier1_chinthareddy_45", {})
    t2 = report.get("tier2_repoprobe_polyglot", {})
    t2_delta = t2.get("paired_delta", {})
    print(
        f"[comprehension-checklist] mode={report['execution_mode']} "
        f"tier1_overall={t1.get('overall_pct', 'n/a')}% "
        f"tier1_cite_valid={t1.get('citation_validity_pct', 'n/a')}% "
        f"tier2_delta={t2_delta.get('overall_delta_pp', 'n/a')}pp "
        f"tier2_edit_bias_rel_red={t2_delta.get('edit_bias_relative_reduction_pct', 'n/a')}%"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
