'use strict';
// Presentation helpers shared by the browser and bounded Node regression checks.
(function (root) {
  const terminal = job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.state);
  const label = row => row.kind==='PACKAGE'&&row.qualifiedName===''?'(default package)':row.qualifiedName || row.spelling || row.canonicalName || row.label || row.kind?.toLowerCase().replaceAll('_',' ') || row.identity;
  const pageInfo = result => {
    const shown = result.items?.length || 0;
    const total = result.exactTotal ?? result.total ?? result.returnedTotal;
    const status = result.status || result.evaluationStatus;
    const incomplete = status && status !== 'COMPLETE' || result.bounds?.truncated || result.operation && result.operation !== 'COMPLETED';
    return `${shown}${total == null ? ' shown' : ` of ${total}${result.returnedTotal == null ? '' : ' observed'}`} · ${incomplete ? 'Incomplete evidence' : 'Available results'}${result.continuation ? ' · More available' : ''}`;
  };
  const request = context => ({context, scope: 'TYPE', projection: 'BOUNDED_UNION', direction: 'OUTGOING', relationshipKinds: ['graph.depends-on']});
  const assessment = value => value?.score == null ? 'Unavailable' : `${value.score} / 100`;
  const summaryCards = summary => {
    const counts = summary.items?.[0]?.nodeCounts;
    return [
      ['Types & references', 'TYPE', 'structure'], ['Modules', 'MODULE', 'structure'],
      ['Source documents', 'SOURCE_DOCUMENT', 'structure'], ['Recorded gaps', 'CAPABILITY_GAP', 'gaps']
    ].map(([label, kind, tab]) => ({label, kind, tab, count: counts ? counts[kind] ?? 0 : undefined,
      observed: summary.status !== 'COMPLETE'}));
  };
  const scoreEvidence = (assessment, dimension, findings = []) => {
    const spec = assessment.model?.dimensions.find(entry => entry.id === dimension.id)?.metric;
    const metric = spec && assessment.structuralInputs.find(input => input.metric.id === spec.metric.id);
    const references = new Set((dimension.contributions || []).flatMap(contribution => contribution.inputs));
    return {metric, findings: findings.filter(item => references.has(item.identity))};
  };
  const realizationRequest = selection => {
    if (!selection?.realization) throw new Error('Choose a recorded configuration first.');
    return {projection: 'REALIZED', realization: selection.realization};
  };
  const completePage = result => result.status === 'COMPLETE' && !result.continuation && !result.bounds?.truncated && (!result.operation || result.operation === 'COMPLETED');
  // Presentation of two canonical bounded results, never a new semantic diff or policy verdict.
  const compareEdges = (a, b) => {
    const left = new Map(a.items.map(edge => [edge.identity, edge])), right = new Map(b.items.map(edge => [edge.identity, edge]));
    return [...new Set([...left.keys(), ...right.keys()])].sort().map(id => ({edge: left.get(id) || right.get(id),
      a: left.has(id) ? 'Present' : completePage(a) ? 'Absent in this view' : 'Not established',
      b: right.has(id) ? 'Present' : completePage(b) ? 'Absent in this view' : 'Not established'}));
  };
  const apiError = code => ({
    INVALID_REQUEST: 'Check the input. Use an existing local folder or a public HTTPS repository URL allowed by the endpoint policy.',
    CONNECTIONS_UNAVAILABLE: 'This server does not enable repository connections.',
    JOBS_UNAVAILABLE: 'Analysis is unavailable on this read-only server.',
    JOB_HISTORY_FULL: 'The server has reached its retained analysis limit. Restart it to begin a new session.',
    JOB_QUEUE_FULL: 'The analysis queue is full. Wait for a running analysis to finish.',
    BUSY: 'The server is busy. Try again shortly.',
    RESPONSE_TOO_LARGE: 'The result is too large. Narrow the search or select a smaller graph scope.',
    REPORT_ARTIFACT_UNAVAILABLE: 'This analysis has no published artifact for this view.',
    GRAPH_STORE_UNAVAILABLE: 'The verified analysis snapshot could not be read.',
    REMOTE_FETCH_FAILED: 'The public repository could not be downloaded. Check the URL, network and Git installation. Private repositories require a local checkout.',
    REMOTE_TIMEOUT: 'Repository download exceeded two minutes. Use a local checkout.',
    REMOTE_SIZE_LIMIT: 'Repository download reached the current acquisition limit. Connect a full local checkout; if it also reaches a limit, inspect input diagnostics for the exact blocker.'
  })[code] || `The request could not be completed (${code || 'connection unavailable'}).`;
  const shortDigest = value => typeof value === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(value) ? '#' + value.slice(7, 15) : String(value ?? '');
  const reasonCatalog = {
    SEMANTIC_UNRESOLVED: ['Unresolved symbol or call', 'A method call, type reference or field access could not be bound to a verified declaration in the available classpath.', 'Prepare dependencies (--prepare-dependencies) or supply missing library artifacts.'],
    OPAQUE_CONDITION: ['Opaque Spring condition', 'A custom or complex @Conditional predicate cannot be statically evaluated without runtime context.', 'Inspect the condition class and compare modeled configuration views.'],
    POTENTIAL_CONDITION_UNCLASSIFIED: ['Unclassified conditional rule', 'A conditional annotation or bean guard uses parameters outside the static condition vocabulary.', 'Review the bean declaration and configuration properties.'],
    UNSUPPORTED_PREDICATE: ['Unsupported condition predicate', 'The condition expression uses dynamic SpEL, environment lookups or custom matchers.', 'Check the referenced expression in the Spring tab.'],
    ANNOTATION_GRAPH_INCOMPLETE: ['Incomplete annotation metadata', 'One or more meta-annotations on a class or method belong to unacquired external jars.', 'Populate the Maven/Gradle artifact cache so meta-annotations can be resolved.'],
    ANNOTATION_EVIDENCE_MISSING: ['Missing annotation bytecode', 'An annotation referenced in source has no corresponding class file in the captured classpath.', 'Verify dependency versions and local artifact cache completeness.'],
    MISSING_POM: ['Missing Maven POM', 'A parent or dependency POM was not found in the local cache during offline analysis.', 'Run dependency preparation online or point --maven-cache to a populated repository.'],
    MISSING_PARENT_POM: ['Missing parent POM', 'The module declares a Maven parent POM that is unavailable in the local cache.', 'Acquire the parent POM so inherited versions and dependency management can be resolved.'],
    ARTIFACT_NOT_FOUND: ['Dependency jar unavailable', 'A declared dependency JAR is absent from the selected artifact cache.', 'Run dependency preparation or provide the artifact in the local cache.'],
    BUILD_MODEL_INCOMPLETE: ['Incomplete build model', 'The Maven or Gradle build descriptor has unresolved properties, imports or parent links.', 'Inspect input diagnostics for missing POMs or Gradle version catalog entries.'],
    GRADLE_EFFECTIVE_CONTEXT_REQUIRED: ['Gradle effective classpath pending', 'Passive Gradle source recovery succeeded, but exact variant and plugin resolution requires an isolated Gradle worker.', 'Use a registered isolated build profile for exact Gradle variant closure.'],
    GRADLE_VERSION_CONFLICT: ['Gradle version conflict', 'Multiple versions of the same dependency were discovered across declaration paths without an executed resolution lock.', 'Provide a lockfile or run isolated Gradle dependency resolution.'],
    GENERATED_SOURCES_NOT_ACQUIRED: ['Generated sources not captured', 'Annotation processors or code generators (Lombok, MapStruct, Protobuf) have not emitted generated source files.', 'Supply captured generated sources or run the isolated build worker.'],
    UNSUPPORTED_PROFILE_ACTIVATION: ['Dynamic profile activation', 'A Spring profile activation rule depends on runtime environment variables or active profile overrides.', 'Compare MUST and MAY views in the Configurations tab.'],
    HTTP_URI_UNSUPPORTED: ['Dynamic HTTP URI template', 'An HTTP client or controller route uses non-constant expressions or unsupported regex syntax.', 'Inspect the endpoint declaration in source.']
  };
  const humanReason = code => {
    const raw = String(code || 'UNKNOWN_GAP').toUpperCase();
    const entry = reasonCatalog[raw];
    if (entry) return {code: raw, title: entry[0], explanation: entry[1], action: entry[2]};
    const fallbackTitle = raw.toLowerCase().replaceAll('_', ' ').replace(/^./, ch => ch.toUpperCase());
    return {
      code: raw,
      title: fallbackTitle,
      explanation: 'This capability boundary or missing input is explicitly recorded so partial evidence is never mistaken for absence.',
      action: 'Open the gap record to inspect the exact source coordinates, mechanism category and diagnostic metadata.'
    };
  };
  const summarizeGaps = items => {
    const counts = new Map();
    for (const item of items || []) {
      const code = item.reasonCode || item.kind || 'RECORDED_GAP';
      const current = counts.get(code) || {code, category: item.mechanismCategory || '', count: 0, info: humanReason(code)};
      current.count++;
      counts.set(code, current);
    }
    return [...counts.values()].sort((a, b) => b.count - a.count || a.code.localeCompare(b.code));
  };
  const policyTemplates = [
    {
      id: 'cycles',
      label: 'No class dependency cycles',
      description: 'Detects circular dependencies (SCCs) between project classes.',
      yaml: 'schema: architecture-policy:v1\nid: project.cycles\nversion: "1"\nrules:\n  - id: project.no-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: TYPE\n    relationships: [graph.depends-on]\n    within: {origins: [PROJECT]}\n'
    },
    {
      id: 'package-cycles',
      label: 'No package-level cycles',
      description: 'Ensures packages form a clean directed acyclic graph (DAG) with no circular coupling.',
      yaml: 'schema: architecture-policy:v1\nid: package.cycles\nversion: "1"\nrules:\n  - id: package.no-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: PACKAGE\n    relationships: [graph.depends-on]\n    within: {origins: [PROJECT]}\n'
    },
    {
      id: 'spring-cycles',
      label: 'Acyclic Spring bean wiring',
      description: 'Verifies that Spring components have no circular dependency injection paths.',
      yaml: 'schema: architecture-policy:v1\nid: spring.cycles\nversion: "1"\nrules:\n  - id: spring.no-bean-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: SPRING_COMPONENT\n    relationships: [spring.selected-binding]\n    within: {origins: [PROJECT]}\n'
    }
  ];
  const model = {terminal, label, pageInfo, request, assessment, summaryCards, scoreEvidence, apiError, realizationRequest, compareEdges, completePage, shortDigest, humanReason, summarizeGaps, policyTemplates};
  if (typeof module !== 'undefined') module.exports = model;
  else root.EvoModel = model;
})(globalThis);
