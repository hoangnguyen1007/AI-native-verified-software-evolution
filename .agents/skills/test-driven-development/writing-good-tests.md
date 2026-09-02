# Writing Good Tests

Use when writing assertions, fixtures, mocks, or test utilities.

- Name the realistic defect the test catches and derive the expectation independently of the implementation/helper under test.
- Prefer observable behavior, boundary validation, and invariants. Avoid testing private structure or copying production calculations into expected values.
- Golden identities, canonical bytes, schemas, and precise spans are deliberate compatibility contracts here. Version intentional changes; do not dismiss their tests as mere change detectors.
- Cover normal, boundary, malformed and negative paths proportional to risk. For semantic/rule changes include missing/ambiguous inputs and false-positive controls.
- Use real components where practical. A test double must preserve the behavior relevant to the test; assert interactions only when calls, arguments, ordering or absence of side effects are part of the contract.
- Keep test-only utilities in test sources. Resource cleanup belongs to the component that owns the resource, even if a test also calls it.
- Diagnose environment/setup failures separately from expected regression failures. Zero tests or all-skipped tests do not verify behavior.
- For scripts, exercise inputs, outputs, side effects and exit codes. For skills, evaluate actual agent behavior on representative tasks. Link/metadata checks detect broken structure, not instruction quality.
- Mentally change a branch, target, origin, span, denominator or diagnostic: the relevant test should reject the incorrect output.
- Preserve raw experimental output. Fix generators and rerun into separately identified results; do not modify evidence to make an assertion pass.
