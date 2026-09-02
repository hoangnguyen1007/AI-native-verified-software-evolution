# Validation at Trust Boundaries

Use after identifying invalid data crossing a real boundary. Do not duplicate every check at every layer.

- Acquisition/build adapters validate untrusted paths, archives and external models.
- Domain constructors enforce canonical invariants independently of adapters.
- Application services enforce scope, limits and permissions.
- Consumers preserve error/uncertainty evidence rather than silently normalizing it away.

Validate where responsibility changes or a new caller can bypass earlier validation. Keep ownership clear and derive downstream behavior from validated domain values.

For filesystem containment, compare resolved path components using the platform's path semantics. String-prefix matching is insufficient (`temp-other` is not inside `temp`); consider symlinks/junctions and require a verified workspace/scratch boundary before destructive operations.

Test the actual bypass or invalid-input path. State which case the checks prevent; passing tests do not prove a bug impossible.
