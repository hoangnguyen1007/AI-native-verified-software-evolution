#!/usr/bin/env python3
"""WP2.5 Hardened Multi-Repo Spring Differential Oracle Orchestrator & G1-G3 Evaluator.

Implements:
  1. Zero-Host Target Execution (Invariant I1): NEVER executes mvn/gradle/javac/JUnit
     on untrusted OSS repositories on the host. All slice builds and ApplicationContextRunner
     executions are orchestrated strictly via hardened offline Docker containers
     (--network=none --read-only --cap-drop=ALL --security-opt no-new-privileges
      --pids-limit ... --memory ...), immutable mounted slice inputs, zero ambient
     environment leaks, try/finally container cleanup, and fail-closed reporting when
     Docker is unavailable.
  2. Deterministic Minimal Configuration Slice Synthesizer (Invariant I2):
     Converts COI / d-DNNF witness JSON specifications into deterministic JUnit 5
     ApplicationContextRunner Java sources (symmetric (witness+, witness-) pairs for MAY,
     uniform samples for MUST/NEVER, optional pure socket-free excludes validated to
     NEVER intersect condition-relevant classes).
  3. Closed-Denominator Soundness Verifier & Go/No-Go G1-G3 Gate Evaluator (Invariant I3):
     Tracks totalSlices, compiledSlices, executedWorlds, runtimeInitFailedSlices (with
     typed reason codes), exactMatches, safeUnknown, overApproxMay, falseMust,
     falseNever, and soundnessPct without ever dropping failed or partial slices.
     Evaluates Soundness + G1 + G2 + G3 gates and emits deterministic JSON + Markdown
     reports (e5_multi_repo_oracle_report.json / .md), including --dry-run / --plan-only.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

SUITE_DIR = Path(__file__).resolve().parent
if str(SUITE_DIR) not in sys.path:
    sys.path.insert(0, str(SUITE_DIR))

import env_provenance  # noqa: E402

SCHEMA_REPORT = "e5.multi-repo-spring-differential-oracle:v1"
SCHEMA_SLICE_INPUT = "e5.spring-oracle-slice-input:v1"
DEFAULT_MANIFEST_PATH = SUITE_DIR / "corpus_manifest.json"
DEFAULT_OUTPUT_DIR = SUITE_DIR / "out" / "e5-oracle"
DEFAULT_DOCKER_IMAGE = "evo-spring-oracle-sandbox:latest"
DEFAULT_MEMORY_LIMIT = "2g"
DEFAULT_PIDS_LIMIT = 256
DEFAULT_CPUS_LIMIT = "2.0"
DEFAULT_TIMEOUT_SECONDS = 180
DEFAULT_TMPFS_SIZE = "512m"
DEFAULT_NONROOT_USER = "65532:65532"
DEFAULT_DDNNF_SEED = 20261128

VALID_TRUTHS = frozenset({"MUST", "MAY", "NEVER", "UNKNOWN"})
VALID_BOOT_GENERATIONS = frozenset({"boot2", "boot3", "boot4"})
VALID_WORLD_STATUSES = frozenset({"RESOLVED", "RUNTIME_INIT_FAILED", "COMPILE_FAILED", "TIMEOUT", "ERROR"})

# Strict allowlist of environment variables permitted inside sandboxed containers (I1).
CONTAINER_ALLOWED_ENV_KEYS = frozenset(
    {
        "HOME",
        "TMPDIR",
        "JAVA_TOOL_OPTIONS",
        "EVO_ORACLE_SEED",
        "EVO_SLICE_ID",
        "LANG",
        "LC_ALL",
    }
)

# Minimal host process environment keys forwarded only to invoke the local `docker` client binary.
HOST_DOCKER_CLIENT_ENV_KEYS = frozenset(
    {
        "PATH",
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "PATHEXT",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "HOME",
        "DOCKER_HOST",
        "DOCKER_CONTEXT",
    }
)

# Go/No-Go Thresholds (WP2.5 / SuperPlan v2 §WP2.5).
SOUNDNESS_MIN_EXACT_MATCH_RATE = 0.98
SOUNDNESS_MIN_SLICE_SUCCESS_RATE = 0.80
SOUNDNESS_RECOMMENDED_MIN_WORLDS = 500
G1_MIN_INTERACTIONS_PER_REPO = 10
G1_MIN_CORE_REPOS_PASSING = 3
G1_CORE_REPO_DENOMINATOR = 5
G2_MIN_CONFIRMED_BUGS = 5
G2_MIN_SINGLE_CONFIG_FPS_ELIMINATED = 20
G3_MAX_MEDIAN_ANALYSIS_SECONDS = 10.0  # strict '<'
G3_MAX_SOLVER_LIMIT_RATE = 0.05  # strict '<'

JAVA_FQN_RE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
CONTAINER_NAME_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")
WORLD_RESULT_PREFIX = "EVO_ORACLE_WORLD_RESULT="


@dataclasses.dataclass(frozen=True)
class WitnessWorld:
    world_id: str
    expected_present: bool
    properties: dict[str, str] = dataclasses.field(default_factory=dict)
    active_profiles: tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "WitnessWorld":
        world_id = str(raw.get("worldId", raw.get("world_id", ""))).strip()
        if not world_id:
            raise ValueError("Witness world requires non-empty 'worldId'")
        if "expectedPresent" in raw:
            expected_present = bool(raw["expectedPresent"])
        elif "expected_present" in raw:
            expected_present = bool(raw["expected_present"])
        else:
            raise ValueError(f"Witness '{world_id}' missing 'expectedPresent'")
        raw_props = raw.get("properties") or {}
        if not isinstance(raw_props, dict):
            raise ValueError(f"Witness '{world_id}' properties must be a dict")
        props = {str(k): str(v) for k, v in sorted(raw_props.items(), key=lambda kv: str(kv[0]))}
        raw_profiles = raw.get("activeProfiles", raw.get("active_profiles", [])) or []
        if not isinstance(raw_profiles, (list, tuple)):
            raise ValueError(f"Witness '{world_id}' activeProfiles must be a list")
        profiles = tuple(sorted({str(p).strip() for p in raw_profiles if str(p).strip()}))
        return cls(
            world_id=world_id,
            expected_present=expected_present,
            properties=props,
            active_profiles=profiles,
        )


@dataclasses.dataclass(frozen=True)
class SliceSpec:
    slice_id: str
    repo_id: str
    target_bean: str
    auto_config_classes: tuple[str, ...]
    user_config_classes: tuple[str, ...]
    predicted_truth: str
    may_exposure_ratio: float | None
    witnesses: tuple[WitnessWorld, ...]
    socket_free_excludes: tuple[str, ...] = ()
    condition_referenced_classes: tuple[str, ...] = ()
    seed: int = DEFAULT_DDNNF_SEED

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "SliceSpec":
        repo_id = str(raw.get("repoId", raw.get("repo_id", raw.get("repo", "")))).strip()
        if not repo_id:
            raise ValueError("Slice specification requires non-empty 'repoId'")
        target_bean = str(raw.get("targetBean", raw.get("target_bean", ""))).strip()
        if not target_bean:
            raise ValueError(f"Slice for repo '{repo_id}' requires non-empty 'targetBean'")

        auto_raw = raw.get("autoConfigClasses", raw.get("auto_config_classes", [])) or []
        user_raw = raw.get("userConfigClasses", raw.get("user_config_classes", [])) or []
        excludes_raw = raw.get("socketFreeExcludes", raw.get("socket_free_excludes", [])) or []
        cond_refs_raw = raw.get("conditionReferencedClasses", raw.get("condition_referenced_classes", [])) or []

        auto_classes = tuple(sorted({validate_java_fqn(str(c).strip(), "autoConfigClasses") for c in auto_raw}))
        user_classes = tuple(sorted({validate_java_fqn(str(c).strip(), "userConfigClasses") for c in user_raw}))
        if not auto_classes and not user_classes:
            raise ValueError(f"Slice '{target_bean}' must provide at least one autoConfigClass or userConfigClass")

        socket_excludes = tuple(sorted({validate_java_fqn(str(c).strip(), "socketFreeExcludes") for c in excludes_raw}))
        cond_refs = tuple(sorted({validate_java_fqn(str(c).strip(), "conditionReferencedClasses") for c in cond_refs_raw}))

        predicted_truth = str(raw.get("predictedTruth", raw.get("predicted_truth", ""))).strip().upper()
        if predicted_truth not in VALID_TRUTHS:
            raise ValueError(f"Invalid predictedTruth '{predicted_truth}'; expected one of {sorted(VALID_TRUTHS)}")

        ratio_raw = raw.get("mayExposureRatio", raw.get("may_exposure_ratio"))
        may_ratio: float | None
        if ratio_raw is None:
            may_ratio = None
        else:
            may_ratio = float(ratio_raw)
            if not (0.0 <= may_ratio <= 1.0):
                raise ValueError(f"mayExposureRatio must be in [0.0, 1.0], got {may_ratio}")

        witnesses_raw = raw.get("witnesses") or []
        if not isinstance(witnesses_raw, list) or not witnesses_raw:
            raise ValueError(f"Slice '{target_bean}' must provide at least one witness world")
        witnesses = tuple(
            sorted(
                (WitnessWorld.from_dict(w) for w in witnesses_raw),
                key=lambda w: (w.world_id, not w.expected_present),
            )
        )
        world_ids = [w.world_id for w in witnesses]
        if len(world_ids) != len(set(world_ids)):
            raise ValueError(f"Duplicate worldId detected in slice '{target_bean}': {world_ids}")

        seed = int(raw.get("seed", DEFAULT_DDNNF_SEED))
        explicit_id = str(raw.get("sliceId", raw.get("slice_id", ""))).strip()
        if explicit_id:
            slice_id = explicit_id
        else:
            digest = hashlib.sha256(
                json.dumps(
                    {
                        "repoId": repo_id,
                        "targetBean": target_bean,
                        "autoConfigClasses": list(auto_classes),
                        "userConfigClasses": list(user_classes),
                        "predictedTruth": predicted_truth,
                    },
                    sort_keys=True,
                ).encode("utf-8")
            ).hexdigest()[:12]
            slice_id = f"slice_{digest}"

        validate_socket_free_excludes(
            socket_excludes=socket_excludes,
            auto_classes=auto_classes,
            user_classes=user_classes,
            target_bean=target_bean,
            condition_referenced_classes=cond_refs,
        )

        return cls(
            slice_id=slice_id,
            repo_id=repo_id,
            target_bean=target_bean,
            auto_config_classes=auto_classes,
            user_config_classes=user_classes,
            predicted_truth=predicted_truth,
            may_exposure_ratio=may_ratio,
            witnesses=witnesses,
            socket_free_excludes=socket_excludes,
            condition_referenced_classes=cond_refs,
            seed=seed,
        )

    def has_symmetric_may_pair(self) -> bool:
        if self.predicted_truth != "MAY":
            return False
        has_pos = any(w.expected_present for w in self.witnesses)
        has_neg = any(not w.expected_present for w in self.witnesses)
        return has_pos and has_neg


@dataclasses.dataclass(frozen=True)
class SandboxPolicy:
    image: str = DEFAULT_DOCKER_IMAGE
    memory_limit: str = DEFAULT_MEMORY_LIMIT
    pids_limit: int = DEFAULT_PIDS_LIMIT
    cpus_limit: str = DEFAULT_CPUS_LIMIT
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS
    tmpfs_size: str = DEFAULT_TMPFS_SIZE
    user: str = DEFAULT_NONROOT_USER
    allowed_env: tuple[tuple[str, str], ...] = (
        ("HOME", "/tmp"),
        ("TMPDIR", "/tmp"),
        ("LANG", "C.UTF-8"),
        ("LC_ALL", "C.UTF-8"),
    )


@dataclasses.dataclass(frozen=True)
class SynthesizedSlice:
    slice_id: str
    repo_id: str
    class_name: str
    package_name: str
    java_source: str
    sha256: str
    predicted_truth: str
    has_symmetric_may_pair: bool
    world_count: int


def validate_java_fqn(fqn: str, field_name: str) -> str:
    if not fqn or not JAVA_FQN_RE.match(fqn):
        raise ValueError(f"Invalid Java FQN in '{field_name}': {fqn!r}")
    return fqn


def validate_socket_free_excludes(
    *,
    socket_excludes: tuple[str, ...],
    auto_classes: tuple[str, ...],
    user_classes: tuple[str, ...],
    target_bean: str,
    condition_referenced_classes: tuple[str, ...],
) -> None:
    """Fail-closed semantic guard: socket-free excludes must NEVER alter condition semantics."""
    if not socket_excludes:
        return
    protected = set(auto_classes) | set(user_classes) | set(condition_referenced_classes)
    if target_bean:
        protected.add(target_bean)
    protected_simple = {c.rsplit(".", 1)[-1] for c in protected}
    for ex in socket_excludes:
        ex_simple = ex.rsplit(".", 1)[-1]
        if ex in protected or ex_simple in protected_simple:
            raise ValueError(
                f"Socket-free exclude '{ex}' overlaps condition-relevant slice class; "
                "excluding it would alter Spring condition semantics."
            )


def escape_java_string(value: str) -> str:
    """Deterministically escape an arbitrary string for safe inclusion in a Java string literal."""
    out: list[str] = []
    for ch in value:
        code = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif code < 0x20 or code == 0x7F:
            out.append(f"\\u{code:04x}")
        else:
            out.append(ch)
    return "".join(out)


def sanitize_java_identifier(raw: str, prefix: str = "id") -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]+", "_", raw).strip("_")
    if not cleaned:
        cleaned = prefix
    if cleaned[0].isdigit():
        cleaned = f"{prefix}_{cleaned}"
    return cleaned


def validate_spring_sat_manifest(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    """Validate the spring_sat_20_oss suite inside corpus_manifest.json."""
    if manifest.get("schema") != "e0.corpus-manifest:v1":
        raise ValueError(f"Unexpected manifest schema: {manifest.get('schema')!r}")
    suites = manifest.get("suites")
    if not isinstance(suites, dict) or "spring_sat_20_oss" not in suites:
        raise ValueError("Manifest missing suites.spring_sat_20_oss")
    entries = suites["spring_sat_20_oss"]
    if not isinstance(entries, list) or not (15 <= len(entries) <= 20):
        raise ValueError(
            f"Expected 15-20 OSS Spring Boot repos in spring_sat_20_oss, "
            f"got {len(entries) if isinstance(entries, list) else type(entries).__name__}"
        )

    banned = set(manifest.get("banned_duplicates", []))
    seen_ids: set[str] = set()
    seen_repos: set[str] = set()
    required_keys = ("id", "repo", "boot_generation", "category", "blind_oracle", "offline_sandbox_required")
    for idx, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise ValueError(f"spring_sat_20_oss[{idx}] must be an object")
        for key in required_keys:
            if key not in entry:
                raise ValueError(f"spring_sat_20_oss[{idx}] missing required metadata key '{key}'")
        entry_id = str(entry["id"]).strip()
        repo = str(entry["repo"]).strip()
        if not entry_id or entry_id in seen_ids:
            raise ValueError(f"Duplicate or empty spring_sat_20_oss id: {entry_id!r}")
        if not repo or "/" not in repo or repo in seen_repos:
            raise ValueError(f"Invalid or duplicate spring_sat_20_oss repo: {repo!r}")
        if entry_id in banned or repo in banned:
            raise ValueError(f"Banned duplicate repository in spring_sat_20_oss: {repo!r}")
        boot_gen = str(entry["boot_generation"]).strip().lower()
        if boot_gen not in VALID_BOOT_GENERATIONS:
            raise ValueError(f"Invalid boot_generation {boot_gen!r} for {repo}")
        if not str(entry["category"]).strip():
            raise ValueError(f"Empty category for {repo}")
        if entry["blind_oracle"] is not True:
            raise ValueError(f"spring_sat_20_oss entry '{repo}' must enforce blind_oracle=true")
        if entry["offline_sandbox_required"] is not True:
            raise ValueError(f"spring_sat_20_oss entry '{repo}' must enforce offline_sandbox_required=true")
        seen_ids.add(entry_id)
        seen_repos.add(repo)
    return entries


def synthesize_application_context_runner_test(
    spec: SliceSpec,
    *,
    package_name: str = "com.evolution.oracle.generated",
) -> SynthesizedSlice:
    """Deterministically synthesize a JUnit 5 ApplicationContextRunner test for a COI slice."""
    validate_java_fqn(package_name, "package_name")
    class_suffix = sanitize_java_identifier(spec.slice_id, prefix="Slice")
    class_name = f"SpringSliceOracle_{class_suffix}Test"

    has_sym = spec.has_symmetric_may_pair()
    if spec.predicted_truth == "MAY" and not has_sym:
        raise ValueError(
            f"MAY slice '{spec.slice_id}' requires a symmetric (witness+, witness-) pair "
            "with both expectedPresent=true and expectedPresent=false worlds."
        )

    auto_refs = ", ".join(f"{cls}.class" for cls in spec.auto_config_classes)
    user_refs = ", ".join(f"{cls}.class" for cls in spec.user_config_classes)
    ratio_str = "null" if spec.may_exposure_ratio is None else f"{spec.may_exposure_ratio:.6f}"
    escaped_slice_id = escape_java_string(spec.slice_id)
    escaped_repo_id = escape_java_string(spec.repo_id)
    escaped_target = escape_java_string(spec.target_bean)
    escaped_truth = escape_java_string(spec.predicted_truth)

    lines: list[str] = [
        f"package {package_name};",
        "",
        "import org.junit.jupiter.api.Test;",
        "import org.springframework.boot.autoconfigure.AutoConfigurations;",
        "import org.springframework.boot.test.context.runner.ApplicationContextRunner;",
        "import org.springframework.context.ConfigurableApplicationContext;",
        "",
        "import static org.junit.jupiter.api.Assertions.assertEquals;",
        "",
        "/**",
        f" * Auto-synthesized WP2.5 Differential Oracle Slice (I1 offline sandbox, I2 seed={spec.seed}).",
        f" * repoId={spec.repo_id} | sliceId={spec.slice_id} | targetBean={spec.target_bean}",
        f" * predictedTruth={spec.predicted_truth} | mayExposureRatio={ratio_str}",
        f" * symmetricMayPair={str(has_sym).lower()} | worlds={len(spec.witnesses)}",
        " */",
        f"public final class {class_name} {{",
        "",
        f'    private static final String SLICE_ID = "{escaped_slice_id}";',
        f'    private static final String REPO_ID = "{escaped_repo_id}";',
        f'    private static final String TARGET_BEAN = "{escaped_target}";',
        f'    private static final String PREDICTED_TRUTH = "{escaped_truth}";',
        "",
        "    private ApplicationContextRunner baseRunner() {",
        "        ApplicationContextRunner runner = new ApplicationContextRunner();",
    ]
    if auto_refs:
        lines.append(f"        runner = runner.withConfiguration(AutoConfigurations.of({auto_refs}));")
    if user_refs:
        lines.append(f"        runner = runner.withUserConfiguration({user_refs});")
    if spec.socket_free_excludes:
        joined_excludes = ",".join(spec.socket_free_excludes)
        lines.append(
            f'        runner = runner.withPropertyValues("spring.autoconfigure.exclude={escape_java_string(joined_excludes)}");'
        )
    lines.extend(
        [
            "        return runner;",
            "    }",
            "",
            "    private static boolean isTargetBeanPresent(ConfigurableApplicationContext ctx, String target) {",
            "        if (ctx.containsBeanDefinition(target) || ctx.containsBean(target)) {",
            "            return true;",
            "        }",
            "        try {",
            "            Class<?> targetType = Class.forName(target, false, ctx.getClassLoader());",
            "            return ctx.getBeanNamesForType(targetType, true, false).length > 0;",
            "        } catch (ClassNotFoundException | LinkageError ignored) {",
            "            return false;",
            "        }",
            "    }",
            "",
            "    private static String jsonEscape(String value) {",
            '        if (value == null) {',
            '            return "null";',
            "        }",
            '        StringBuilder sb = new StringBuilder(value.length() + 16);',
            "        sb.append('\"');",
            "        for (int i = 0; i < value.length(); i++) {",
            "            char c = value.charAt(i);",
            "            if (c == '\\\\') {",
            '                sb.append("\\\\\\\\");',
            "            } else if (c == '\"') {",
            '                sb.append("\\\\\\"");',
            "            } else if (c == '\\n' || c == '\\r' || c == '\\t') {",
            "                sb.append(' ');",
            "            } else if (c >= 0x20) {",
            "                sb.append(c);",
            "            }",
            "        }",
            "        sb.append('\"');",
            "        return sb.toString();",
            "    }",
            "",
            "    private static void emitWorldResult(String worldId, boolean expectedPresent, Boolean actualPresent, String status, String reason) {",
            '        String actualJson = actualPresent == null ? "null" : String.valueOf(actualPresent);',
            f'        System.out.println("{WORLD_RESULT_PREFIX}" + "{{\\"sliceId\\":" + jsonEscape(SLICE_ID) + ",\\"repoId\\":" + jsonEscape(REPO_ID)'
            ' + ",\\"worldId\\":" + jsonEscape(worldId) + ",\\"predictedTruth\\":" + jsonEscape(PREDICTED_TRUTH)'
            ' + ",\\"expectedPresent\\":" + expectedPresent + ",\\"actualPresent\\":" + actualJson'
            ' + ",\\"status\\":" + jsonEscape(status) + ",\\"reason\\":" + jsonEscape(reason) + "}");',
            "    }",
            "",
        ]
    )

    for idx, world in enumerate(spec.witnesses, start=1):
        method_name = f"witness_{idx:03d}_{sanitize_java_identifier(world.world_id, prefix='w')}"
        escaped_world_id = escape_java_string(world.world_id)
        expected_literal = "true" if world.expected_present else "false"
        prop_entries: list[str] = []
        merged_props = dict(world.properties)
        if world.active_profiles and "spring.profiles.active" not in merged_props:
            merged_props["spring.profiles.active"] = ",".join(world.active_profiles)
        for key in sorted(merged_props.keys()):
            val = merged_props[key]
            prop_entries.append(f'"{escape_java_string(key)}={escape_java_string(val)}"')

        lines.append("    @Test")
        lines.append(f"    void {method_name}() {{")
        lines.append("        ApplicationContextRunner runner = baseRunner();")
        if prop_entries:
            lines.append(f"        runner = runner.withPropertyValues({', '.join(prop_entries)});")
        lines.extend(
            [
                "        runner.run(context -> {",
                "            Throwable failure = context.getStartupFailure();",
                "            if (failure != null) {",
                f'                emitWorldResult("{escaped_world_id}", {expected_literal}, null, "RUNTIME_INIT_FAILED", failure.getClass().getName() + ": " + failure.getMessage());',
                "                return;",
                "            }",
                "            boolean actualPresent = isTargetBeanPresent(context, TARGET_BEAN);",
                f'            emitWorldResult("{escaped_world_id}", {expected_literal}, actualPresent, "RESOLVED", null);',
                *(
                    [
                        f'            assertEquals({expected_literal}, actualPresent, "Slice " + SLICE_ID + " world {escaped_world_id} mismatch for " + TARGET_BEAN);'
                    ]
                    if spec.predicted_truth != "UNKNOWN"
                    else []
                ),
                "        });",
                "    }",
                "",
            ]
        )

    lines.append("}")
    java_source = "\n".join(lines) + "\n"
    digest = hashlib.sha256(java_source.encode("utf-8")).hexdigest()
    return SynthesizedSlice(
        slice_id=spec.slice_id,
        repo_id=spec.repo_id,
        class_name=class_name,
        package_name=package_name,
        java_source=java_source,
        sha256=f"sha256:{digest}",
        predicted_truth=spec.predicted_truth,
        has_symmetric_may_pair=has_sym,
        world_count=len(spec.witnesses),
    )


def build_sanitized_host_env(source_env: dict[str, str] | None = None) -> dict[str, str]:
    """Return a minimal environment for invoking the local docker CLI with zero ambient secret leaks."""
    env = os.environ if source_env is None else source_env
    sanitized: dict[str, str] = {}
    for k, v in env.items():
        if k.upper() in HOST_DOCKER_CLIENT_ENV_KEYS:
            sanitized[k] = v
    return sanitized


def build_hardened_docker_command(
    *,
    container_name: str,
    slice_input_dir: Path,
    output_dir: Path,
    policy: SandboxPolicy = SandboxPolicy(),
    offline_m2_dir: Path | None = None,
    extra_env: dict[str, str] | None = None,
    entrypoint_args: tuple[str, ...] = ("/opt/evo-oracle/run-slice.sh", "/input", "/output"),
) -> list[str]:
    """Construct an invariant-I1 hardened offline Docker CLI invocation.

    Enforces:
      - --rm, --network=none, --read-only, --cap-drop=ALL
      - --security-opt no-new-privileges:true
      - --pids-limit, --memory, --memory-swap, --cpus, non-root --user
      - Immutable :ro input mount (and optional :ro offline dependency cache)
      - Strict env allowlist with zero ambient host env forwarding
    """
    if not CONTAINER_NAME_RE.match(container_name):
        raise ValueError(f"Invalid Docker container name: {container_name!r}")
    if policy.pids_limit <= 0:
        raise ValueError(f"pids_limit must be > 0, got {policy.pids_limit}")
    if not policy.image.strip():
        raise ValueError("Docker image must be non-empty")

    merged_env: dict[str, str] = dict(policy.allowed_env)
    if extra_env:
        for k, v in extra_env.items():
            if k not in CONTAINER_ALLOWED_ENV_KEYS:
                raise ValueError(
                    f"Refusing to pass non-allowlisted environment variable '{k}' into sandbox container (I1)."
                )
            merged_env[k] = str(v)

    cmd: list[str] = [
        "docker",
        "run",
        "--rm",
        "--name",
        container_name,
        "--network=none",
        "--read-only",
        "--cap-drop=ALL",
        "--security-opt",
        "no-new-privileges:true",
        "--pids-limit",
        str(policy.pids_limit),
        "--memory",
        policy.memory_limit,
        "--memory-swap",
        policy.memory_limit,
        "--cpus",
        str(policy.cpus_limit),
        "--user",
        policy.user,
        "--tmpfs",
        f"/tmp:rw,noexec,nosuid,nodev,size={policy.tmpfs_size}",
        "--tmpfs",
        f"/work:rw,nosuid,nodev,size={policy.tmpfs_size}",
        "-v",
        f"{slice_input_dir.resolve()}:/input:ro",
        "-v",
        f"{output_dir.resolve()}:/output:rw",
    ]
    if offline_m2_dir is not None:
        cmd.extend(["-v", f"{offline_m2_dir.resolve()}:/m2-cache:ro"])

    for k in sorted(merged_env.keys()):
        cmd.extend(["-e", f"{k}={merged_env[k]}"])

    cmd.append(policy.image)
    cmd.extend(entrypoint_args)
    return cmd


def check_docker_daemon_available(
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    timeout_s: int = 10,
) -> tuple[bool, str]:
    """Check whether Docker daemon is reachable without falling back to host execution."""
    try:
        proc = runner(
            ["docker", "info", "--format", "{{.ServerVersion}}"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_s,
            check=False,
            env=build_sanitized_host_env(),
        )
    except FileNotFoundError:
        return False, "DOCKER_CLI_NOT_FOUND"
    except subprocess.TimeoutExpired:
        return False, "DOCKER_DAEMON_TIMEOUT"
    except OSError as exc:
        return False, f"DOCKER_OS_ERROR:{exc.__class__.__name__}"

    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip().splitlines()
        msg = detail[0][:160] if detail else f"exit={proc.returncode}"
        return False, f"DOCKER_DAEMON_UNAVAILABLE:{msg}"
    version = (proc.stdout or "").strip()
    return True, version or "available"


def parse_world_results_from_output(stdout_text: str, results_jsonl_path: Path | None = None) -> list[dict[str, Any]]:
    """Parse structured world results from JSONL output file and/or container stdout markers."""
    records: list[dict[str, Any]] = []
    if results_jsonl_path is not None and results_jsonl_path.is_file():
        for raw_line in results_jsonl_path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            records.append(json.loads(line))
    if not records and stdout_text:
        for raw_line in stdout_text.splitlines():
            idx = raw_line.find(WORLD_RESULT_PREFIX)
            if idx != -1:
                payload = raw_line[idx + len(WORLD_RESULT_PREFIX) :].strip()
                records.append(json.loads(payload))
    return records


def classify_runtime_failure_reason(raw_reason: str) -> str:
    """Normalize runtime initialization error messages into typed reason codes (I3)."""
    text = (raw_reason or "").strip()
    low = text.lower()
    if not text:
        return "RUNTIME_INIT_UNKNOWN"
    if text.isupper() and re.fullmatch(r"[A-Z0-9_]+", text):
        return text
    if "unsatisfiedlinkerror" in low or "jni" in low or "native library" in low:
        return "JNI_OR_NATIVE_LIB_UNAVAILABLE"
    if "socket" in low or "connectexception" in low or "unknownhost" in low or "network" in low:
        return "NETWORK_OR_SOCKET_BLOCKED_IN_SANDBOX"
    if "datasource" in low or "jdbc" in low or "hikari" in low or "entitymanager" in low:
        return "DATASOURCE_OR_DB_INIT_FAILED"
    if "classnotfound" in low or "noclassdeffound" in low or "linkageerror" in low:
        return "CLASSPATH_OR_LINKAGE_MISSING"
    if "beancreationexception" in low or "unsatisfieddependency" in low:
        return "BEAN_CREATION_FAILED"
    if "timeout" in low:
        return "SANDBOX_TIMEOUT"
    if "docker" in low:
        return "DOCKER_DAEMON_UNAVAILABLE"
    return "RUNTIME_INIT_OTHER"


def execute_slice_in_sandbox(
    spec: SliceSpec,
    *,
    policy: SandboxPolicy = SandboxPolicy(),
    offline_m2_dir: Path | None = None,
    dry_run: bool = False,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, Any]:
    """Synthesize a slice and execute it strictly inside a hardened offline Docker container.

    Never falls back to host execution if Docker is unavailable (I1).
    Always invokes `docker rm -f <container>` in `finally` to prevent leaked containers.
    """
    synthesized = synthesize_application_context_runner_test(spec)
    safe_slice = sanitize_java_identifier(spec.slice_id, prefix="slice").lower()[:40]
    digest_short = synthesized.sha256.split(":", 1)[-1][:10]
    container_name = f"evo-oracle-{safe_slice}-{digest_short}"

    if dry_run:
        virtual_in = Path(f"/sandbox-plan/{spec.slice_id}/input")
        virtual_out = Path(f"/sandbox-plan/{spec.slice_id}/output")
        docker_cmd = build_hardened_docker_command(
            container_name=container_name,
            slice_input_dir=virtual_in,
            output_dir=virtual_out,
            policy=policy,
            offline_m2_dir=offline_m2_dir,
            extra_env={"EVO_ORACLE_SEED": str(spec.seed), "EVO_SLICE_ID": spec.slice_id},
        )
        return {
            "sliceId": spec.slice_id,
            "repoId": spec.repo_id,
            "targetBean": spec.target_bean,
            "predictedTruth": spec.predicted_truth,
            "mayExposureRatio": spec.may_exposure_ratio,
            "className": synthesized.class_name,
            "sourceSha256": synthesized.sha256,
            "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
            "status": "PLANNED_DRY_RUN",
            "compiled": False,
            "failureReason": None,
            "dockerCommand": docker_cmd,
            "worlds": [],
            "plannedWorldCount": synthesized.world_count,
            "javaSource": synthesized.java_source,
        }

    docker_ok, docker_detail = check_docker_daemon_available(runner=runner)
    if not docker_ok:
        return {
            "sliceId": spec.slice_id,
            "repoId": spec.repo_id,
            "targetBean": spec.target_bean,
            "predictedTruth": spec.predicted_truth,
            "mayExposureRatio": spec.may_exposure_ratio,
            "className": synthesized.class_name,
            "sourceSha256": synthesized.sha256,
            "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
            "status": "RUNTIME_INIT_FAILED",
            "compiled": False,
            "failureReason": "DOCKER_DAEMON_UNAVAILABLE",
            "failureDetail": docker_detail,
            "worlds": [],
        }

    sanitized_env = build_sanitized_host_env()
    with tempfile.TemporaryDirectory(prefix=f"evo_oracle_{safe_slice}_") as tmp_dir:
        tmp_root = Path(tmp_dir)
        in_dir = tmp_root / "input"
        out_dir = tmp_root / "output"
        in_dir.mkdir(parents=True, exist_ok=True)
        out_dir.mkdir(parents=True, exist_ok=True)

        pkg_rel = Path(*synthesized.package_name.split("."))
        java_path = in_dir / "src" / "test" / "java" / pkg_rel / f"{synthesized.class_name}.java"
        java_path.parent.mkdir(parents=True, exist_ok=True)
        java_path.write_text(synthesized.java_source, encoding="utf-8")
        (in_dir / "slice_spec.json").write_text(
            json.dumps(
                {
                    "schema": SCHEMA_SLICE_INPUT,
                    "sliceId": spec.slice_id,
                    "repoId": spec.repo_id,
                    "targetBean": spec.target_bean,
                    "predictedTruth": spec.predicted_truth,
                    "sourceSha256": synthesized.sha256,
                    "seed": spec.seed,
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        docker_cmd = build_hardened_docker_command(
            container_name=container_name,
            slice_input_dir=in_dir,
            output_dir=out_dir,
            policy=policy,
            offline_m2_dir=offline_m2_dir,
            extra_env={"EVO_ORACLE_SEED": str(spec.seed), "EVO_SLICE_ID": spec.slice_id},
        )

        try:
            proc = runner(
                docker_cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=policy.timeout_seconds,
                check=False,
                env=sanitized_env,
            )
            world_records = parse_world_results_from_output(
                proc.stdout or "", out_dir / "results.jsonl"
            )
            compile_marker = out_dir / "compile_failed.txt"
            if compile_marker.is_file() or (proc.returncode != 0 and not world_records and "compilation" in (proc.stderr or "").lower()):
                return {
                    "sliceId": spec.slice_id,
                    "repoId": spec.repo_id,
                    "targetBean": spec.target_bean,
                    "predictedTruth": spec.predicted_truth,
                    "mayExposureRatio": spec.may_exposure_ratio,
                    "className": synthesized.class_name,
                    "sourceSha256": synthesized.sha256,
                    "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
                    "status": "COMPILE_FAILED",
                    "compiled": False,
                    "failureReason": "COMPILE_FAILED",
                    "failureDetail": (compile_marker.read_text(encoding="utf-8", errors="replace").strip() if compile_marker.is_file() else (proc.stderr or "")[:300]),
                    "worlds": [],
                }
            if not world_records:
                reason = classify_runtime_failure_reason(proc.stderr or proc.stdout or f"exit={proc.returncode}")
                return {
                    "sliceId": spec.slice_id,
                    "repoId": spec.repo_id,
                    "targetBean": spec.target_bean,
                    "predictedTruth": spec.predicted_truth,
                    "mayExposureRatio": spec.may_exposure_ratio,
                    "className": synthesized.class_name,
                    "sourceSha256": synthesized.sha256,
                    "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
                    "status": "RUNTIME_INIT_FAILED",
                    "compiled": True,
                    "failureReason": reason,
                    "failureDetail": ((proc.stderr or proc.stdout or "")[:300]),
                    "worlds": [],
                }
            return {
                "sliceId": spec.slice_id,
                "repoId": spec.repo_id,
                "targetBean": spec.target_bean,
                "predictedTruth": spec.predicted_truth,
                "mayExposureRatio": spec.may_exposure_ratio,
                "className": synthesized.class_name,
                "sourceSha256": synthesized.sha256,
                "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
                "status": "EXECUTED",
                "compiled": True,
                "failureReason": None,
                "worlds": world_records,
            }
        except subprocess.TimeoutExpired:
            return {
                "sliceId": spec.slice_id,
                "repoId": spec.repo_id,
                "targetBean": spec.target_bean,
                "predictedTruth": spec.predicted_truth,
                "mayExposureRatio": spec.may_exposure_ratio,
                "className": synthesized.class_name,
                "sourceSha256": synthesized.sha256,
                "hasSymmetricMayPair": synthesized.has_symmetric_may_pair,
                "status": "RUNTIME_INIT_FAILED",
                "compiled": False,
                "failureReason": "SANDBOX_TIMEOUT",
                "failureDetail": f"Timed out after {policy.timeout_seconds}s",
                "worlds": [],
            }
        finally:
            try:
                runner(
                    ["docker", "rm", "-f", container_name],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=15,
                    check=False,
                    env=sanitized_env,
                )
            except (OSError, subprocess.SubprocessError):
                pass


def verify_closed_denominator_results(slice_results: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute exact closed-denominator soundness and runtime accounting (Invariant I3).

    Every slice is accounted for in `totalSlices`; runtime initialization failures are
    preserved with typed reason codes and NEVER silently dropped.
    """
    total_slices = len(slice_results)
    planned_slices = 0
    compiled_slices = 0
    compile_failed_slices = 0
    runtime_init_failed_slices = 0
    successful_slices = 0
    runtime_failure_reasons: dict[str, int] = {}

    executed_worlds = 0
    resolved_worlds = 0
    world_runtime_failures = 0
    exact_matches = 0
    safe_unknown = 0
    over_approx_may = 0
    false_must = 0
    false_never = 0
    unsound_witness_findings: list[dict[str, Any]] = []

    for sl in slice_results:
        slice_id = str(sl.get("sliceId", ""))
        repo_id = str(sl.get("repoId", ""))
        predicted = str(sl.get("predictedTruth", "")).strip().upper()
        if predicted not in VALID_TRUTHS:
            raise ValueError(f"Slice '{slice_id}' has invalid predictedTruth: {predicted!r}")

        status = str(sl.get("status", "EXECUTED")).strip().upper()
        compiled = bool(sl.get("compiled", status not in ("COMPILE_FAILED", "PLANNED_DRY_RUN")))
        if compiled:
            compiled_slices += 1

        if status == "PLANNED_DRY_RUN":
            planned_slices += 1
            continue

        if status == "COMPILE_FAILED":
            compile_failed_slices += 1
            continue

        if status in ("RUNTIME_INIT_FAILED", "SANDBOX_UNAVAILABLE", "TIMEOUT"):
            runtime_init_failed_slices += 1
            code = classify_runtime_failure_reason(str(sl.get("failureReason") or sl.get("failureDetail") or status))
            runtime_failure_reasons[code] = runtime_failure_reasons.get(code, 0) + 1
            continue

        worlds = sl.get("worlds") or []
        slice_had_resolved = False
        slice_had_init_failure = False
        slice_failure_codes: list[str] = []

        for w in worlds:
            executed_worlds += 1
            w_status = str(w.get("status", "RESOLVED")).strip().upper()
            w_id = str(w.get("worldId", ""))
            if w_status != "RESOLVED" or w.get("actualPresent") is None:
                world_runtime_failures += 1
                slice_had_init_failure = True
                slice_failure_codes.append(
                    classify_runtime_failure_reason(str(w.get("reason") or w_status))
                )
                continue

            resolved_worlds += 1
            slice_had_resolved = True
            actual_present = bool(w["actualPresent"])
            expected_present = bool(w.get("expectedPresent", actual_present))

            if predicted == "MUST":
                if actual_present:
                    exact_matches += 1
                else:
                    false_must += 1
                    unsound_witness_findings.append(
                        {
                            "kind": "FALSE_MUST",
                            "repoId": repo_id,
                            "sliceId": slice_id,
                            "worldId": w_id,
                            "predictedTruth": predicted,
                            "expectedPresent": expected_present,
                            "actualPresent": actual_present,
                        }
                    )
            elif predicted == "NEVER":
                if not actual_present:
                    exact_matches += 1
                else:
                    false_never += 1
                    unsound_witness_findings.append(
                        {
                            "kind": "FALSE_NEVER",
                            "repoId": repo_id,
                            "sliceId": slice_id,
                            "worldId": w_id,
                            "predictedTruth": predicted,
                            "expectedPresent": expected_present,
                            "actualPresent": actual_present,
                        }
                    )
            elif predicted == "UNKNOWN":
                safe_unknown += 1
            elif predicted == "MAY":
                if actual_present == expected_present:
                    exact_matches += 1
                else:
                    over_approx_may += 1

        if slice_had_init_failure:
            runtime_init_failed_slices += 1
            primary_code = slice_failure_codes[0] if slice_failure_codes else "RUNTIME_INIT_UNKNOWN"
            runtime_failure_reasons[primary_code] = runtime_failure_reasons.get(primary_code, 0) + 1
        elif slice_had_resolved:
            successful_slices += 1
        else:
            runtime_init_failed_slices += 1
            runtime_failure_reasons["ZERO_WORLDS_EXECUTED"] = (
                runtime_failure_reasons.get("ZERO_WORLDS_EXECUTED", 0) + 1
            )

    unsound_worlds = false_must + false_never
    sound_worlds = resolved_worlds - unsound_worlds
    soundness_pct = round((100.0 * sound_worlds / resolved_worlds), 4) if resolved_worlds > 0 else 0.0
    exact_match_rate = round((exact_matches / resolved_worlds), 6) if resolved_worlds > 0 else 0.0
    slice_run_success_rate = round((successful_slices / total_slices), 6) if total_slices > 0 else 0.0

    # Verify strict closed-denominator invariants over both slices and resolved worlds (I3).
    assert total_slices == (planned_slices + successful_slices + compile_failed_slices + runtime_init_failed_slices)
    assert resolved_worlds == (exact_matches + safe_unknown + over_approx_may + false_must + false_never)

    return {
        "totalSlices": total_slices,
        "plannedSlices": planned_slices,
        "compiledSlices": compiled_slices,
        "compileFailedSlices": compile_failed_slices,
        "successfulSlices": successful_slices,
        "runtimeInitFailedSlices": runtime_init_failed_slices,
        "runtimeInitFailureReasons": dict(sorted(runtime_failure_reasons.items())),
        "executedWorlds": executed_worlds,
        "resolvedWorlds": resolved_worlds,
        "worldRuntimeFailures": world_runtime_failures,
        "exactMatches": exact_matches,
        "safeUnknown": safe_unknown,
        "overApproxMay": over_approx_may,
        "falseMust": false_must,
        "falseNever": false_never,
        "soundnessPct": soundness_pct,
        "exactMatchRate": exact_match_rate,
        "sliceRunSuccessRate": slice_run_success_rate,
        "recommendedWorldsTargetMet": resolved_worlds >= SOUNDNESS_RECOMMENDED_MIN_WORLDS,
        "unsoundWitnessFindings": unsound_witness_findings,
    }


def evaluate_go_no_go_gates(
    *,
    oracle_summary: dict[str, Any],
    repo_metrics: list[dict[str, Any]],
    core_repo_ids: set[str] | None = None,
) -> dict[str, Any]:
    """Evaluate Soundness + Go/No-Go G1, G2, G3 gates with strict fail-closed semantics."""
    resolved_worlds = int(oracle_summary.get("resolvedWorlds", 0))
    total_slices = int(oracle_summary.get("totalSlices", 0))
    false_must = int(oracle_summary.get("falseMust", 0))
    false_never = int(oracle_summary.get("falseNever", 0))
    exact_match_rate = float(oracle_summary.get("exactMatchRate", 0.0))
    slice_success_rate = float(oracle_summary.get("sliceRunSuccessRate", 0.0))

    soundness_passed = (
        total_slices > 0
        and resolved_worlds > 0
        and false_must == 0
        and false_never == 0
        and exact_match_rate >= SOUNDNESS_MIN_EXACT_MATCH_RATE
        and slice_success_rate >= SOUNDNESS_MIN_SLICE_SUCCESS_RATE
    )
    soundness_gate = {
        "passed": soundness_passed,
        "criteria": {
            "falseMustZero": false_must == 0,
            "falseNeverZero": false_never == 0,
            "exactMatchRateMet": resolved_worlds > 0 and exact_match_rate >= SOUNDNESS_MIN_EXACT_MATCH_RATE,
            "sliceRunSuccessRateMet": total_slices > 0 and slice_success_rate >= SOUNDNESS_MIN_SLICE_SUCCESS_RATE,
        },
        "observed": {
            "falseMust": false_must,
            "falseNever": false_never,
            "soundnessPct": oracle_summary.get("soundnessPct", 0.0),
            "exactMatchRate": exact_match_rate,
            "sliceRunSuccessRate": slice_success_rate,
            "resolvedWorlds": resolved_worlds,
        },
        "thresholds": {
            "maxFalseMust": 0,
            "maxFalseNever": 0,
            "minExactMatchRate": SOUNDNESS_MIN_EXACT_MATCH_RATE,
            "minSliceRunSuccessRate": SOUNDNESS_MIN_SLICE_SUCCESS_RATE,
        },
    }

    # G1: >= 10 user<->starter interactions/repo on >= 3/5 core repos.
    core_rows: list[dict[str, Any]] = []
    for row in repo_metrics:
        rid = str(row.get("repoId", row.get("id", ""))).strip()
        repo_name = str(row.get("repo", "")).strip()
        is_core = bool(row.get("coreG1Repo", row.get("core_g1_repo", False)))
        if not is_core and core_repo_ids:
            is_core = (rid in core_repo_ids) or (repo_name in core_repo_ids)
        if is_core:
            core_rows.append(row)

    core_passing = [
        r for r in core_rows
        if int(r.get("userStarterInteractions", r.get("g1UserStarterInteractions", 0))) >= G1_MIN_INTERACTIONS_PER_REPO
    ]
    g1_passed = len(core_passing) >= G1_MIN_CORE_REPOS_PASSING
    g1_gate = {
        "passed": g1_passed,
        "coreReposEvaluated": len(core_rows),
        "coreReposMeetingThreshold": len(core_passing),
        "coreRepoDenominator": G1_CORE_REPO_DENOMINATOR,
        "passingCoreRepos": sorted(str(r.get("repoId", r.get("repo", ""))) for r in core_passing),
        "thresholds": {
            "minInteractionsPerRepo": G1_MIN_INTERACTIONS_PER_REPO,
            "minCoreReposPassing": G1_MIN_CORE_REPOS_PASSING,
            "coreRepoDenominator": G1_CORE_REPO_DENOMINATOR,
        },
    }

    # G2: >= 5 JUnit-confirmed real conditional/AOP/metadata bugs OR >= 20 single-config FPs eliminated.
    confirmed_bugs = sum(
        int(r.get("junitConfirmedBugs", r.get("g2ConfirmedBugs", 0)))
        for r in repo_metrics
    )
    fps_eliminated = sum(
        int(r.get("singleConfigFpsEliminated", r.get("g2SingleConfigFpsEliminated", 0)))
        for r in repo_metrics
    )
    bugs_met = confirmed_bugs >= G2_MIN_CONFIRMED_BUGS
    fps_met = fps_eliminated >= G2_MIN_SINGLE_CONFIG_FPS_ELIMINATED
    g2_gate = {
        "passed": bugs_met or fps_met,
        "junitConfirmedBugs": confirmed_bugs,
        "singleConfigFpsEliminated": fps_eliminated,
        "bugsCriterionMet": bugs_met,
        "fpEliminationCriterionMet": fps_met,
        "thresholds": {
            "minJunitConfirmedBugs": G2_MIN_CONFIRMED_BUGS,
            "minSingleConfigFpsEliminated": G2_MIN_SINGLE_CONFIG_FPS_ELIMINATED,
        },
    }

    # G3: median analysis < 10.0 s/repo AND solverLimitRate < 0.05.
    analysis_secs = [
        float(r["analysisSeconds"])
        for r in repo_metrics
        if r.get("analysisSeconds") is not None
    ]
    total_queries = sum(int(r.get("solverQueries", 0)) for r in repo_metrics)
    total_limits = sum(int(r.get("solverLimitHits", 0)) for r in repo_metrics)
    if analysis_secs:
        median_analysis_s = round(float(statistics.median(analysis_secs)), 4)
    else:
        median_analysis_s = None

    if total_queries > 0:
        solver_limit_rate: float | None = round(total_limits / total_queries, 6)
    elif repo_metrics and all(r.get("solverLimitRate") is not None for r in repo_metrics):
        solver_limit_rate = round(
            float(statistics.fmean(float(r["solverLimitRate"]) for r in repo_metrics)),
            6,
        )
    else:
        solver_limit_rate = None

    g3_median_met = (median_analysis_s is not None) and (median_analysis_s < G3_MAX_MEDIAN_ANALYSIS_SECONDS)
    g3_solver_met = (solver_limit_rate is not None) and (solver_limit_rate < G3_MAX_SOLVER_LIMIT_RATE)
    g3_gate = {
        "passed": g3_median_met and g3_solver_met,
        "reposTimed": len(analysis_secs),
        "medianAnalysisSeconds": median_analysis_s,
        "totalSolverQueries": total_queries,
        "totalSolverLimitHits": total_limits,
        "solverLimitRate": solver_limit_rate,
        "criteria": {
            "medianAnalysisUnderLimit": g3_median_met,
            "solverLimitRateUnderLimit": g3_solver_met,
        },
        "thresholds": {
            "maxMedianAnalysisSecondsExclusive": G3_MAX_MEDIAN_ANALYSIS_SECONDS,
            "maxSolverLimitRateExclusive": G3_MAX_SOLVER_LIMIT_RATE,
        },
    }

    overall_passed = bool(
        soundness_gate["passed"] and g1_gate["passed"] and g2_gate["passed"] and g3_gate["passed"]
    )
    return {
        "soundness": soundness_gate,
        "G1": g1_gate,
        "G2": g2_gate,
        "G3": g3_gate,
        "overallPassed": overall_passed,
        "decision": "GO" if overall_passed else "NO_GO",
    }


def render_markdown_report(report: dict[str, Any]) -> str:
    """Render deterministic Markdown report for e5_multi_repo_oracle_report.md."""
    oracle = report["oracle"]
    gates = report["gates"]
    decision = report["decision"]
    mode = report.get("mode", "LIVE_SANDBOX")
    lines: list[str] = [
        "# E5 Multi-Repo Spring Differential Oracle & Go/No-Go (`G1–G3`) Report",
        "",
        f"- **Schema:** `{report.get('schema', SCHEMA_REPORT)}`",
        f"- **Mode:** `{mode}` | **Decision:** **`{decision}`** | **Timestamp (UTC):** `{report.get('timestampUtc', '')}`",
        f"- **Cohort:** `spring_sat_20_oss` (`{report.get('cohortCount', 0)}` OSS Spring Boot repositories) | **Seed (`I2`):** `{report.get('seed', DEFAULT_DDNNF_SEED)}`",
        "",
    ]

    env = report.get("environment")
    if isinstance(env, dict):
        lines.extend(env_provenance.format_provenance_markdown(env))

    lines.extend(
        [
            "## Closed-Denominator Soundness Summary (`I3`)",
            "",
            "| Metric | Value |",
            "| :--- | ---: |",
            f"| `totalSlices` | {oracle['totalSlices']} |",
            f"| `compiledSlices` | {oracle['compiledSlices']} |",
            f"| `successfulSlices` | {oracle['successfulSlices']} |",
            f"| `compileFailedSlices` | {oracle['compileFailedSlices']} |",
            f"| `runtimeInitFailedSlices` | {oracle['runtimeInitFailedSlices']} |",
            f"| `sliceRunSuccessRate` | {oracle['sliceRunSuccessRate']:.4f} |",
            f"| `executedWorlds` | {oracle['executedWorlds']} |",
            f"| `resolvedWorlds` | {oracle['resolvedWorlds']} |",
            f"| `exactMatches` | {oracle['exactMatches']} |",
            f"| `safeUnknown` | {oracle['safeUnknown']} |",
            f"| `overApproxMay` | {oracle['overApproxMay']} |",
            f"| `falseMust` | {oracle['falseMust']} |",
            f"| `falseNever` | {oracle['falseNever']} |",
            f"| `exactMatchRate` | {oracle['exactMatchRate']:.4f} |",
            f"| `soundnessPct` | {oracle['soundnessPct']:.2f}% |",
            "",
        ]
    )

    reasons = oracle.get("runtimeInitFailureReasons") or {}
    if reasons:
        lines.extend(
            [
                "### Runtime Init Failure Breakdown (Typed `I3` Denominator)",
                "",
                "| Reason Code | Slices |",
                "| :--- | ---: |",
            ]
        )
        for code, cnt in sorted(reasons.items()):
            lines.append(f"| `{code}` | {cnt} |")
        lines.append("")

    s_g = gates["soundness"]
    g1 = gates["G1"]
    g2 = gates["G2"]
    g3 = gates["G3"]
    med_str = "N/A" if g3["medianAnalysisSeconds"] is None else f"{g3['medianAnalysisSeconds']:.3f}s"
    slr_str = "N/A" if g3["solverLimitRate"] is None else f"{g3['solverLimitRate']:.4f}"

    lines.extend(
        [
            "## Go/No-Go Gate Evaluation (`Soundness`, `G1–G3`)",
            "",
            "| Gate | Criterion | Observed | Status |",
            "| :--- | :--- | :--- | :---: |",
            f"| `Soundness` | `falseMust=0`, `falseNever=0`, `exactMatchRate>=0.98`, `sliceSuccess>=0.80` | `falseMust={s_g['observed']['falseMust']}`, `falseNever={s_g['observed']['falseNever']}`, `exact={s_g['observed']['exactMatchRate']:.4f}`, `slice={s_g['observed']['sliceRunSuccessRate']:.4f}` | `{'PASS' if s_g['passed'] else 'FAIL'}` |",
            f"| `G1` | `>=10` user↔starter interactions on `>=3/5` core repos | `{g1['coreReposMeetingThreshold']}/{g1['coreRepoDenominator']}` core repos passing (evaluated=`{g1['coreReposEvaluated']}`) | `{'PASS' if g1['passed'] else 'FAIL'}` |",
            f"| `G2` | `>=5` JUnit-confirmed bugs OR `>=20` single-config FPs eliminated | `bugs={g2['junitConfirmedBugs']}`, `fpsEliminated={g2['singleConfigFpsEliminated']}` | `{'PASS' if g2['passed'] else 'FAIL'}` |",
            f"| `G3` | median analysis `<10.0s`/repo AND `solverLimitRate<0.05` | `median={med_str}`, `solverLimitRate={slr_str}` | `{'PASS' if g3['passed'] else 'FAIL'}` |",
            "",
        ]
    )

    cohort_rows = report.get("cohort") or []
    if cohort_rows:
        lines.extend(
            [
                "## `spring_sat_20_oss` Cohort Overview",
                "",
                "| ID | Repository | Boot | Category | Core G1 | Offline Sandbox |",
                "| :--- | :--- | :---: | :--- | :---: | :---: |",
            ]
        )
        for item in cohort_rows:
            lines.append(
                f"| `{item['id']}` | `{item['repo']}` | `{item['boot_generation']}` | `{item['category']}` | "
                f"{'YES' if item.get('core_g1_repo') else 'no'} | {'YES' if item.get('offline_sandbox_required') else 'NO'} |"
            )
        lines.append("")

    return "\n".join(lines) + "\n"


def _load_slice_specs(slices_path: Path | None) -> list[SliceSpec]:
    if slices_path is None:
        return []
    raw_items: list[dict[str, Any]] = []
    if slices_path.is_dir():
        for fp in sorted(slices_path.glob("*.json")):
            data = json.loads(fp.read_text(encoding="utf-8"))
            if isinstance(data, list):
                raw_items.extend(data)
            elif isinstance(data, dict) and isinstance(data.get("slices"), list):
                raw_items.extend(data["slices"])
            elif isinstance(data, dict):
                raw_items.append(data)
    elif slices_path.is_file():
        data = json.loads(slices_path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            raw_items.extend(data)
        elif isinstance(data, dict) and isinstance(data.get("slices"), list):
            raw_items.extend(data["slices"])
        elif isinstance(data, dict):
            raw_items.append(data)
    else:
        raise FileNotFoundError(f"Slices input path not found: {slices_path}")
    return [SliceSpec.from_dict(item) for item in raw_items]


def run_orchestration(
    *,
    manifest_path: Path = DEFAULT_MANIFEST_PATH,
    slices: list[SliceSpec] | None = None,
    pre_recorded_results: list[dict[str, Any]] | None = None,
    repo_metrics: list[dict[str, Any]] | None = None,
    policy: SandboxPolicy = SandboxPolicy(),
    offline_m2_dir: Path | None = None,
    dry_run: bool = False,
    repo_filter: set[str] | None = None,
    seed: int = DEFAULT_DDNNF_SEED,
    output_dir: Path | None = None,
    environment: dict[str, Any] | None = None,
    skip_env_probe: bool = False,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, Any]:
    """End-to-end WP2.5 orchestration: manifest validation -> slice synthesis/sandbox -> G1-G3 report."""
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cohort = validate_spring_sat_manifest(manifest)
    if repo_filter:
        cohort = [
            c for c in cohort
            if c["id"] in repo_filter or c["repo"] in repo_filter
        ]

    core_repo_ids: set[str] = set()
    for c in cohort:
        if c.get("core_g1_repo"):
            core_repo_ids.add(c["id"])
            core_repo_ids.add(c["repo"])

    slice_results: list[dict[str, Any]] = []
    if pre_recorded_results is not None:
        slice_results = list(pre_recorded_results)
        mode = "OFFLINE_RESULTS_VERIFICATION"
    else:
        mode = "PLAN_ONLY_DRY_RUN" if dry_run else "HARDENED_DOCKER_SANDBOX"
        for spec in slices or []:
            slice_results.append(
                execute_slice_in_sandbox(
                    spec,
                    policy=policy,
                    offline_m2_dir=offline_m2_dir,
                    dry_run=dry_run,
                    runner=runner,
                )
            )

    oracle_summary = verify_closed_denominator_results(slice_results)
    gates = evaluate_go_no_go_gates(
        oracle_summary=oracle_summary,
        repo_metrics=repo_metrics or [],
        core_repo_ids=core_repo_ids,
    )

    if mode == "PLAN_ONLY_DRY_RUN":
        decision = "PLAN_ONLY_DRY_RUN"
    else:
        decision = gates["decision"]

    env_meta = environment
    if env_meta is None and not skip_env_probe:
        env_meta = env_provenance.collect_environment_provenance(
            execution_target="docker",
            include_docker=not dry_run,
        )

    # Strip large raw javaSource from main JSON report if present; keep SHA-256 & dockerCommand.
    compact_slices = [
        {k: v for k, v in sr.items() if k != "javaSource"}
        for sr in slice_results
    ]

    report: dict[str, Any] = {
        "schema": SCHEMA_REPORT,
        "timestampUtc": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "mode": mode,
        "decision": decision,
        "seed": seed,
        "cohortName": "spring_sat_20_oss",
        "cohortCount": len(cohort),
        "cohort": cohort,
        "sandboxPolicy": {
            "image": policy.image,
            "network": "none",
            "readOnlyRootfs": True,
            "capDrop": ["ALL"],
            "securityOpt": ["no-new-privileges:true"],
            "memoryLimit": policy.memory_limit,
            "pidsLimit": policy.pids_limit,
            "cpusLimit": policy.cpus_limit,
            "timeoutSeconds": policy.timeout_seconds,
            "user": policy.user,
            "hostExecutionForbidden": True,
        },
        "oracle": oracle_summary,
        "gates": gates,
        "slices": compact_slices,
    }
    if env_meta is not None:
        report["environment"] = env_meta

    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)
        if dry_run:
            gen_dir = output_dir / "synthesized_slices"
            gen_dir.mkdir(parents=True, exist_ok=True)
            for sr in slice_results:
                if "javaSource" in sr and "className" in sr:
                    (gen_dir / f"{sr['className']}.java").write_text(sr["javaSource"], encoding="utf-8")
        json_path = output_dir / "e5_multi_repo_oracle_report.json"
        md_path = output_dir / "e5_multi_repo_oracle_report.md"
        json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        md_path.write_text(render_markdown_report(report), encoding="utf-8")

    return report


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="WP2.5 Hardened Multi-Repo Spring ApplicationContextRunner Oracle Orchestrator & G1-G3 Evaluator."
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST_PATH)
    parser.add_argument("--slices-json", type=Path, default=None, help="JSON file or directory of COI/d-DNNF witness slice specs.")
    parser.add_argument("--results-json", type=Path, default=None, help="Pre-recorded sandbox slice execution results JSON for offline G1-G3 evaluation.")
    parser.add_argument("--repo-metrics-json", type=Path, default=None, help="Per-repo G1/G2/G3 telemetry JSON.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--image", default=DEFAULT_DOCKER_IMAGE)
    parser.add_argument("--memory", default=DEFAULT_MEMORY_LIMIT)
    parser.add_argument("--pids-limit", type=int, default=DEFAULT_PIDS_LIMIT)
    parser.add_argument("--cpus", default=DEFAULT_CPUS_LIMIT)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--offline-m2-dir", type=Path, default=None)
    parser.add_argument("--seed", type=int, default=DEFAULT_DDNNF_SEED)
    parser.add_argument("--repo-filter", default="", help="Optional comma-separated cohort IDs or owner/repo coordinates.")
    parser.add_argument(
        "--dry-run",
        "--plan-only",
        dest="dry_run",
        action="store_true",
        help="Synthesize slices, validate manifest & Docker commands, and emit reports without running Docker.",
    )
    parser.add_argument("--skip-env-probe", action="store_true", help="Skip host/Docker environment provenance probe.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    policy = SandboxPolicy(
        image=args.image,
        memory_limit=args.memory,
        pids_limit=args.pids_limit,
        cpus_limit=args.cpus,
        timeout_seconds=args.timeout,
    )
    slices = _load_slice_specs(args.slices_json) if args.slices_json else []
    pre_recorded: list[dict[str, Any]] | None = None
    repo_metrics: list[dict[str, Any]] = []

    if args.results_json:
        raw_res = json.loads(args.results_json.read_text(encoding="utf-8"))
        if isinstance(raw_res, dict):
            pre_recorded = list(raw_res.get("slices", []))
            if not args.repo_metrics_json and isinstance(raw_res.get("repoMetrics"), list):
                repo_metrics = list(raw_res["repoMetrics"])
        elif isinstance(raw_res, list):
            pre_recorded = list(raw_res)

    if args.repo_metrics_json:
        raw_rm = json.loads(args.repo_metrics_json.read_text(encoding="utf-8"))
        repo_metrics = list(raw_rm.get("repos", raw_rm)) if isinstance(raw_rm, dict) else list(raw_rm)

    repo_filter = {tok.strip() for tok in args.repo_filter.split(",") if tok.strip()} or None

    report = run_orchestration(
        manifest_path=args.manifest,
        slices=slices,
        pre_recorded_results=pre_recorded,
        repo_metrics=repo_metrics,
        policy=policy,
        offline_m2_dir=args.offline_m2_dir,
        dry_run=args.dry_run,
        repo_filter=repo_filter,
        seed=args.seed,
        output_dir=args.output_dir,
        skip_env_probe=args.skip_env_probe,
    )
    print(
        f"[e5-spring-oracle] mode={report['mode']} decision={report['decision']} "
        f"cohort={report['cohortCount']} slices={report['oracle']['totalSlices']} "
        f"worlds={report['oracle']['resolvedWorlds']}/{report['oracle']['executedWorlds']} "
        f"soundness={report['oracle']['soundnessPct']:.2f}% "
        f"falseMust={report['oracle']['falseMust']} falseNever={report['oracle']['falseNever']}"
    )
    if args.dry_run:
        return 0
    return 0 if report["gates"]["overallPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
