'use strict';
// Presentation helpers shared by the browser and bounded Node regression checks.
(function (root) {
  const terminal = job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.state);
  const label = row => row.kind==='PACKAGE'&&row.qualifiedName===''?'(default package)':row.qualifiedName || row.spelling || row.canonicalName || row.label || row.kind?.toLowerCase().replaceAll('_',' ') || row.identity;
  const pageInfo = result => {
    const shown = result.items?.length || 0;
    const total = result.exactTotal ?? result.total;
    const count = total != null ? shown + ' of ' + total
      : result.returnedTotal != null ? shown + ' of ' + result.returnedTotal + ' returned' : shown + ' shown';
    return count + (result.continuation ? ' · More available' : '');
  };
  const request = context => ({context, scope: 'TYPE', projection: 'BOUNDED_UNION', direction: 'OUTGOING', relationshipKinds: ['graph.depends-on']});
  const assessment = value => value?.score == null ? 'Unavailable' : `${value.score} / 100`;
  const summaryCards = summary => {
    const counts = summary.items?.[0]?.nodeCounts;
    return [
      ['Types & references', 'TYPE', 'structure'], ['Modules', 'MODULE', 'structure'],
      ['Source documents', 'SOURCE_DOCUMENT', 'structure']
    ].map(([label, kind, tab]) => ({label, kind, tab, count: counts?.[kind] ?? (counts && summary.status === 'COMPLETE' ? 0 : undefined),
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
    INSIGHTS_RESOURCE_LIMIT: 'This scope exceeded the query budget. Try Packages or Modules.',
    REPORT_ARTIFACT_UNAVAILABLE: 'This analysis has no published artifact for this view.',
    DIAGNOSIS_SCOPE_MISMATCH: 'The selected context or evidence no longer matches this finding. Reopen it from the current report.',
    DIAGNOSIS_INPUT_MISMATCH: 'The diagnosis input changed. Reopen the finding and run diagnosis again.',
    REPAIR_PROPOSAL_MISMATCH: 'The proposal could not be reproduced. Reopen the diagnosis and review the current proposal.',
    GRAPH_STORE_UNAVAILABLE: 'The verified analysis snapshot could not be read.',
    REMOTE_FETCH_FAILED: 'The public repository could not be downloaded. Check the URL, network and Git installation. Private repositories require a local checkout.',
    REMOTE_TIMEOUT: 'Repository download exceeded two minutes. Use a local checkout.',
    REMOTE_SIZE_LIMIT: 'Repository download reached the current acquisition limit. Connect a full local checkout; if it also reaches a limit, inspect input diagnostics for the exact blocker.',
    CONNECTION_NOT_FOUND: 'The repository is no longer connected.',
    SETUP_CAPACITY: 'The maximum repository limit (32) has been reached. Disconnect unused repositories to connect more.',
    JOB_RUNNING: 'An analysis is currently running for this repository. Wait for it to complete or cancel it before disconnecting.'
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
    HTTP_URI_UNSUPPORTED: ['Dynamic HTTP URI template', 'An HTTP client or controller route uses non-constant expressions or unsupported regex syntax.', 'Inspect the endpoint declaration in source.'],
    NATIVE_CONTEXT_REQUIRED: ['Isolated native build context needed', 'Full semantic resolution requires an isolated Maven or Gradle build context.', 'Provision an isolated native build worker to resolve generated sources and plugin classpaths.'],
    PROTOBUF_GENERATOR_REQUIRED: ['Protobuf code generation required', 'Compiled gRPC/Protobuf Java stubs are missing because protoc generation has not run.', 'Enable isolated Protobuf code generation or supply generated stub sources.'],
    PROTOBUF_SCHEMA_TYPE_UNRESOLVED: ['Protobuf schema type unresolved', 'A .proto message, enum, or service reference could not be matched across captured proto imports.', 'Ensure imported .proto descriptors or well-known Google proto definitions are included.'],
    GRADLE_ACQUISITION_WORKER_REQUIRED: ['Gradle acquisition worker required', 'Gradle build model and effective classpath require an isolated acquisition worker.', 'Run acquisition with an authorized isolated Gradle worker to capture resolved modules.'],
    GRADLE_DEPENDENCY_WORKER_REQUIRED: ['Gradle dependency worker required', 'Gradle dependency resolution requires an isolated worker rather than offline parsing alone.', 'Enable Gradle dependency acquisition or prepare cached artifacts.'],
    GRADLE_BINARY_UNAVAILABLE: ['Gradle binary unavailable', 'Neither a Gradle wrapper script nor a configured Gradle distribution was available.', 'Verify gradle-wrapper.properties or provide an approved local Gradle toolchain.'],
    GRADLE_PLUGIN_BOM_EVIDENCE_REQUIRED: ['Gradle plugin/BOM evidence required', 'Platform BOM or convention plugins need resolved Gradle metadata to materialize dependency versions.', 'Capture effective Gradle build context to expand plugin/BOM coordinates.'],
    MISSING_IMPORT_BOM: ['Missing imported BOM or parent POM', 'A Maven BOM or parent POM coordinate could not be resolved from captured artifacts or approved registries.', 'Allow artifact preparation from an approved registry or supply the parent/BOM artifact.'],
    SYNTAX_ERROR: ['Source syntax error', 'The parser encountered invalid syntax and fell back to partial structure extraction.', 'Fix the syntax error in source before relying on semantic edges for that file.'],
    HTTP_ROUTING_EVIDENCE_MISSING: ['HTTP routing evidence missing', 'An HTTP call site or route lacked sufficient static URI or controller metadata to link.', 'Use string constants or inspect the endpoint declaration in captured source.'],
    HTTP_URI_NO_CANDIDATE: ['No matching HTTP endpoint found', 'An outbound HTTP call target had no matching controller route within this snapshot.', 'Check if the target service lives in another module/repository or uses an external API.'],
    GRPC_ROUTING_EVIDENCE_MISSING: ['gRPC routing evidence missing', 'A gRPC client stub or server binding could not be matched to a service descriptor.', 'Verify generated gRPC service bases and client channel bindings are captured.'],
    JAVA_COMPATIBILITY_WORKER_REQUIRED: ['Java compatibility worker required', 'Target JDK toolchain version differs from the host analyzer runtime.', 'Run with a compatible isolated JDK toolchain worker.'],
    DYNAMIC_BUILD_LOGIC: ['Dynamic build script logic', 'Imperative Groovy/Kotlin DSL loops or closures in the build script could not be statically evaluated.', 'Capture the effective build model via an isolated worker for exact source roots and dependencies.']
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
      id: 'layering',
      label: 'Layering: Web cannot call Repository',
      description: 'Enforces clean 3-tier layering by forbidding direct dependencies from Web/Controller to Repository/Data.',
      yaml: 'schema: architecture-policy:v1\nid: layering.boundary\nversion: "1"\nrules:\n  - id: web.no-repository\n    version: "1"\n    kind: FORBIDDEN_DEPENDENCY\n    severity: ERROR\n    scope: TYPE\n    relationships: [graph.depends-on]\n    from: {packages: [controller, web, api]}\n    to: {packages: [repository, dao, data]}\n'
    },
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
      id: 'spring-boundary',
      label: 'Spring component isolation',
      description: 'Forbids client components from directly referencing internal store components.',
      yaml: 'schema: architecture-policy:v1\nid: spring.boundary\nversion: "1"\nrules:\n  - id: client.no-store\n    version: "1"\n    kind: FORBIDDEN_DEPENDENCY\n    severity: ERROR\n    scope: SPRING_COMPONENT\n    relationships: [spring.selected-binding]\n    from: {names: [client]}\n    to: {names: [store]}\n'
    },
    {
      id: 'spring-cycles',
      label: 'Acyclic Spring bean wiring',
      description: 'Verifies that Spring components have no circular dependency injection paths.',
      yaml: 'schema: architecture-policy:v1\nid: spring.cycles\nversion: "1"\nrules:\n  - id: spring.no-bean-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: SPRING_COMPONENT\n    relationships: [spring.selected-binding]\n    within: {origins: [PROJECT]}\n'
    }
  ];
  const parseCitations = text => {
    const input = String(text ?? '');
    const pattern = /\[(E\d+(?:\s*(?:,|–|-)\s*E?\d+)*)\]/g;
    const segments = [];
    let cursor = 0;
    for (const match of input.matchAll(pattern)) {
      if (match.index > cursor) segments.push({type: 'text', value: input.slice(cursor, match.index)});
      const ids = [];
      for (const part of match[1].split(',')) {
        const trimmed = part.trim();
        const range = trimmed.match(/^E(\d+)\s*[–-]\s*E?(\d+)$/i);
        if (range) {
          const start = Number(range[1]), end = Number(range[2]);
          if (start <= end && end - start <= 25) {
            for (let n = start; n <= end; n++) ids.push('E' + n);
          } else {
            ids.push('E' + start, 'E' + end);
          }
        } else {
          const single = trimmed.match(/^E?(\d+)$/i);
          if (single) ids.push('E' + Number(single[1]));
        }
      }
      if (ids.length) segments.push({type: 'citations', raw: match[0], ids: [...new Set(ids)]});
      else segments.push({type: 'text', value: match[0]});
      cursor = match.index + match[0].length;
    }
    if (cursor < input.length) segments.push({type: 'text', value: input.slice(cursor)});
    return segments;
  };
  const classifySseToolEvent = parsed => {
    if (!parsed || typeof parsed !== 'object') return 'complete';
    const phase = String(parsed.phase || '').toLowerCase();
    if (phase === 'start' || phase === 'started' || phase === 'running') return 'start';
    if (phase === 'complete' || phase === 'completed' || phase === 'done' || phase === 'error') return 'complete';
    return String(parsed.status || '').toLowerCase() === 'running' ? 'start' : 'complete';
  };
  const buildImpactQuery = (context, identity, overrides = {}) => ({
    request: {...request(context), ...overrides, direction: 'INCOMING'},
    arguments: {identity}
  });
  const rankApplicableRecipes = (recipes = [], applicableRecipes = []) => {
    const applicable = new Set(Array.isArray(applicableRecipes) ? applicableRecipes : []);
    return [...(recipes || [])]
      .map(recipe => ({
        ...recipe,
        applicable: applicable.has(recipe.id),
        displaySummary: recipe.summary || recipe.title || recipe.id
      }))
      .sort((a, b) => Number(b.applicable) - Number(a.applicable) || String(a.id).localeCompare(String(b.id)));
  };
  const classifyGapObligation = code => {
    const raw = String(code || '').toUpperCase();
    if (/^(MISSING_POM|MISSING_PARENT_POM|MISSING_IMPORT_BOM|ARTIFACT_NOT_FOUND|GRADLE_BINARY|GRADLE_EFFECTIVE|GRADLE_ACQUISITION|GRADLE_DEPENDENCY|GRADLE_PLUGIN|NATIVE_CONTEXT|PROTOBUF_GENERATOR|GENERATED_SOURCES|JAVA_COMPATIBILITY)/.test(raw))
      return {bucket: 'environment', label: 'Environment & Build'};
    if (/^(AUTHORITY|POLICY|DISCLOSURE|PERMISSION|REMOTE_|AUTH)/.test(raw))
      return {bucket: 'authority', label: 'Authority & Policy'};
    if (/^(SYNTAX_ERROR|DYNAMIC_|OPAQUE_|UNSUPPORTED_|HTTP_URI|HTTP_ROUTING|GRPC_ROUTING|POTENTIAL_CONDITION|PROTOBUF_SCHEMA)/.test(raw))
      return {bucket: 'source', label: 'Source & Dynamic'};
    return {bucket: 'boundary', label: 'Analyzer Boundary'};
  };
  const selectDefaultContext = (contexts = [], snapshot = '', current = '') => {
    if (current && contexts.some(c => c.identity === current)) return current;
    if (snapshot && contexts.some(c => c.identity === snapshot)) return snapshot;
    return contexts[0]?.identity ?? '';
  };
  const findPreferredSpringContext = (contexts = [], current = '', kinds = null) => {
    const isSpring = id => id && (id.startsWith('spring-context:') || kinds?.get?.(id) === 'spring.context');
    if (isSpring(current)) return current;
    return contexts.find(c => isSpring(c.identity))?.identity ?? current;
  };
  const model = {terminal, label, pageInfo, request, assessment, summaryCards, scoreEvidence, apiError, realizationRequest, compareEdges, completePage, shortDigest, humanReason, summarizeGaps, policyTemplates, parseCitations, classifySseToolEvent, buildImpactQuery, rankApplicableRecipes, classifyGapObligation, selectDefaultContext, findPreferredSpringContext};
  if (typeof module !== 'undefined') module.exports = model;
  else root.EvoModel = model;
})(globalThis);
