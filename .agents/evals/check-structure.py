"""Check local Markdown links and measure instruction size; not a behavior eval."""

import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def main():
    documents = [ROOT / "AGENTS.md", *sorted((ROOT / ".agents").rglob("*.md"))]
    documents += [
        ROOT / path for path in (
            "docs/project-context.md", "docs/current-state.md", "docs/roadmap.md",
            "docs/architecture/m1-contracts.md",
            "docs/decisions/ADR-001-parser-technology.md",
            "docs/research/semantic-frontend-comparison.md",
        )
    ]
    failures = []
    checked = 0
    for document in documents:
        text = document.read_text(encoding="utf-8")
        for link in re.findall(r"(?<!!)\[[^\]]*\]\(([^)]+)\)", text):
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            checked += 1
            target = document.parent / unquote(parsed.path)
            if not target.exists():
                failures.append(f"{document.relative_to(ROOT)} -> {link}")

    # Compare normalized UTF-8 text bytes, not tokens or model latency.
    old_paths = git("ls-tree", "-r", "--name-only", "HEAD", "--", "AGENTS.md", ".agents").decode().splitlines()
    old_documents = [p for p in old_paths if p.endswith(".md")]
    before = sum(len(git("show", f"HEAD:{p}").replace(b"\r\n", b"\n")) for p in old_documents)
    new_documents = [ROOT / "AGENTS.md", *sorted((ROOT / ".agents").rglob("*.md"))]
    after = sum(len(p.read_bytes().replace(b"\r\n", b"\n")) for p in new_documents)
    print(json.dumps({
        "documents_checked": len(documents), "local_links_checked": checked,
        "broken_links": failures, "instruction_markdown_bytes": {
            "baseline_commit": git("rev-parse", "HEAD").decode().strip(),
            "before": before, "after": after,
            "reduction_percent": round(100 * (before - after) / before, 2) if before else None,
        },
        "limits": "Checks local link targets, not anchors, remote URLs, routing behavior or token use.",
    }, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
