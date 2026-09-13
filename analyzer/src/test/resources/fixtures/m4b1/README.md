# M4B.1 compact inputs

`r0-kleene.tsv` is a deterministic projection of the 21 `and-*`, `or-*`, and `not-*`
rows in `benchmarks/m4-r0/cases.json`. The upstream file's SHA-256 at projection is
`38d1b794f206010d713e799d3c1a27752ec1538dd37d1bb25ec480439d7fee44`.
Columns preserve the upstream case ID, operator, operand values and independently
registered expected result. `-` denotes the absent second operand of NOT.

This is evidence for the three-valued Boolean algebra only. It does not port or
re-run R0 property, registration, binding, region, feasibility or runtime tests.
M4B.1 tests author the remaining input contracts directly in compact immutable
fixtures, including the four-line Java source in `ConditionTestInputs`.

The expression-identity golden in `ConditionExpressionTest` uses a literal R0
preimage and an independently computed .NET SHA-256 digest. The fixture framework
digest is labeled synthetic and makes no Spring artifact acquisition claim.
