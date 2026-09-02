# Root Cause Tracing

For a failure far from its originating input:

1. Capture the failing operation, expected contract, actual value/state, and full relevant diagnostic.
2. Trace backward through callers until the first violated contract or unsupported assumption.
3. Compare a passing case with the same environment; isolate the differing input/configuration.
4. Verify the hypothesis with the smallest controlled experiment.
5. Fix the responsible boundary and add a regression case that reaches it through a real consumer.

For this platform, trace source/build inputs -> adapter observation -> canonical fact -> graph -> query/policy. A missing classpath entry, failed attribution and lost provenance are different failures.

For state pollution, enumerate the exact tests, establish a clean isolated fixture, run selected tests in known order and record each exit code and before/after state. Use order reduction only after confirming order dependence. If no tests ran, a runner failed, or pollution already existed, report that limitation rather than a clean result.

Use project test commands from the affected POM/README. Do not run cleanup against the repository's `.git` or another valuable directory. Restrict diagnostics to sanitized fields; do not dump the environment.
