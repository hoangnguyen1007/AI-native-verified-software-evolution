# Condition-Based Waiting

Use for flaky tests or asynchronous operations when waiting for observable state.

- Prefer a completion signal, future, latch or event over guessed sleeps.
- If polling is necessary, read fresh state, use a monotonic elapsed-time source, a bounded timeout and an interval justified by the operation.
- Include cancellation and cleanup where the operation supports them. Report the unmet condition and sanitized diagnostics on timeout.
- Preserve exceptions from the operation; do not convert a crash into a retry-until-timeout.
- When testing timing itself (debounce, scheduling, timeout), use a controllable clock where practical and document the timing contract.

In Java, choose existing project concurrency/test facilities; do not add a library or arbitrary delay just to make a test green. Verify a failing case and repeated relevant runs when there is concrete evidence of flakiness.
