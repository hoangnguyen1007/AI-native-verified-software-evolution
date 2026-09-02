# Evidence Rule — Always On

Apply the relevant parts to each task; detailed semantic/benchmark procedures live in [semantic evaluation](../skills/se-project-engineering/semantic-evaluation.md).

- Distinguish human approval, verified observation, provisional inference and missing evidence using the labels in AGENTS.md.
- Trace conclusions through source/build evidence -> semantic fact -> graph -> rule -> violation.
- Preserve category, source/caller, target/candidates, origin, file/full span, status, derivation, uncertainty, diagnostics, repository/snapshot/configuration identity and provenance as applicable.
- Missing, ambiguous, conditional, unsupported or failed analysis stays explicit. Numeric confidence needs a defined and validated meaning.
- A semantic target can be resolved while provenance is missing. Never invent coordinates or discard the provenance failure to satisfy a contract.
- A violation identifies rule/version, symbols, supporting relationships, minimal path, evidence and limitations. Prohibited uncertainty cannot support a certain violation.
- Report coverage separately from correctness. Include omitted/error/unsupported cases and every registered category in denominators; a restricted slice does not establish a full gate.
- Preserve exact corpus/analyzer/parser/rule/schema versions, hashes, ordered classpath, configuration, environment, commands, failures, exclusions and raw results.
- Derived summaries must be reproducible from identified raw data. Fix a generator and produce separately identified results; never hand-improve evidence.
