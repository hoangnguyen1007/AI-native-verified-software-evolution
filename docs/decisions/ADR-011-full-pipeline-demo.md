# ADR-011 — A single full-pipeline demo

**CONFIRMED — human decision, 2026-11-15.** Close M11 and G7 on the delivered scope and prioritize one complete, local demo. SE121, SE122 and KLTN are no longer delivery boundaries. Track A and Track B name the existing foundations; the demo extends them with AI and verified change delivery in the same product flow.

## M11 and G7 disposition

**M11: CLOSED / HUMAN ACCEPTED. G7: CLOSED BY HUMAN DECISION / NOT CERTIFIED PASSED.** The [M11.6 execution record](../tasks/m11/06-execution.md) remains the evidence of record. It documents the bounded historical, authored, scale and consumer results, the independent `CHANGES REQUIRED` verdict, two unresolved M4E completeness assertions and one Spring metric partition-scope assertion. These results and any `PARTIAL`, unsupported, unqualified or unavailable outcomes keep their original meaning. Track A's [M10 release reservations](../tasks/m10/01-release-sign-off.md) still apply.

The remaining G7 adjudication, repeat campaigns and formal closure package are withdrawn as delivery requirements. The three assertions are known limitations, not a mandatory follow-up campaign. Investigate one if it affects the demo or a claim made about it. M12 integration and local reproduction are folded into the demo; there is no separate G8 sign-off or academic-phase handoff.

## Demo and verification boundary

Keeping the old gate sequence would delay integration and rerun controls without changing demo behavior. Shipping an analysis-only demo would leave out the AI-to-verification flow. The chosen alternative is the [full-pipeline demo](../roadmap.md#full-pipeline-demo): complete the journey on one pinned fixture first, while retaining the comprehensive-coverage mandate and disclosing the actual coverage of that run.

AI diagnosis uses bounded graph and evidence retrieval and cites exact inputs. Plans, reviewable patches and an applicable OpenRewrite refactoring route remain proposals until deterministic checks run. Model output has no authority to alter canonical facts, choose execution policy, execute commands, or publish changes. Use a bounded, validated sandbox for target code and candidate changes. Local verified-change and CI/PR output must carry the candidate identity, actual checks and their outcomes. External model use with repository content, remote PR creation and deployment retain their separate permission boundaries.

Use the [lean verification policy](../testing.md#lean-verification-for-the-demo): reuse applicable evidence, run the smallest check that can distinguish success from failure, and do one end-to-end check for meaningful demo integration changes. Add broader runs only for a specific risk or claim that narrower evidence cannot support. Revisit production qualification when the project actually needs to make a production claim; this decision does not grant one.
