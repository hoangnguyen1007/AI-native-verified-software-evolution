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
    JOB_RUNNING: 'An analysis is currently running for this repository. Wait for it to complete or cancel it before disconnecting.',
    PREVIEW_SUPERSEDED: 'A newer analysis snapshot is ready. Refreshing to the updated view.',
    PRELIMINARY_GRAPH_PENDING: 'Deep semantic analysis is still running. Policy evaluation and automated repair unlock once Stage 3 completes.',
    REPAIR_VERIFICATION_REJECTED: 'Verification rejected this candidate patch. Inspect the failed checks before editing or retrying.',
    APPROVAL_TOKEN_EXPIRED: 'This apply confirmation preview expired. Refresh the preview to re-verify file hashes before confirming.',
    APPROVAL_TOKEN_INVALID: 'Confirmation token is invalid or already consumed. Refresh the preview first.',
    WORKSPACE_DRIFT: 'Target files changed on disk since the snapshot was captured. Re-analyze the repository before applying.',
    GITHUB_AUTH_REQUIRED: 'GitHub authentication is required for this repository or pull request action. Add a session token or sign in via gh.',
    GITHUB_RATE_LIMITED: 'GitHub API rate limit reached. Add a session token to raise the limit or retry shortly.',
    GITHUB_REPO_NOT_FOUND: 'Repository not found on GitHub, or authentication is required to access a private repository.',
    PR_PUBLISH_UNCONFIRMED: 'Explicit user confirmation is required before pushing a branch or opening a GitHub Pull Request.',
    AUTH_REQUIRED: 'Sign in to access private repositories and multi-user actions.',
    CSRF_TOKEN_INVALID: 'Your browser session token changed. Refreshing your session — please retry the action.',
    APPLY_ACCESS_DENIED: 'This approval or apply record belongs to a different signed-in user.',
    GITHUB_APP_TOKEN_REQUIRED: 'GitHub Check Runs require a GitHub App installation token. Use Commit Status or configure a GitHub App.',
    WEBHOOK_SECRET_NOT_CONFIGURED: 'GitHub webhook verification is disabled until a Webhook Secret is configured in Workspace Settings.',
    REPAIR_UNVERIFIED_REQUIRES_OPT_IN: 'Explicitly opt in to prepare a Draft PR for an unverified candidate patch.',
    INVALID_CREDENTIALS: 'Incorrect email, username, or password.',
    ACCOUNT_EXISTS: 'An account with that email or username already exists.',
    ACCOUNT_ALREADY_EXISTS: 'An account with that email or username already exists.',
    ACCOUNT_DISABLED: 'This workspace account has been disabled by an administrator.',
    ADMIN_REQUIRED: 'Workspace administrator privileges are required for this action.',
    LAST_ADMIN_PROTECTED: 'At least one active workspace administrator must remain enabled.',
    PASSWORD_REQUIRED_BEFORE_UNLINK: 'Set a local workspace password in Profile & Security before unlinking GitHub so you do not lose account access.',
    WEAK_PASSWORD: 'Password must include at least 8 characters, a number, and a symbol.',
    GITHUB_SETUP_REQUIRED: 'Connect a GitHub App or save OAuth credentials in Workspace Settings first.',
    LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER: 'Local host filesystem paths are disabled in multi-user mode. Connect a GitHub repository instead.',
    INTERRUPTED_ON_RESTART: 'The server restarted while this repair verification job was in flight. Inspect persisted rounds/events and re-run verification.',
    SERVER_RESTARTED_IN_FLIGHT: 'The server restarted while this repair verification job was in flight. Inspect persisted rounds/events and re-run verification.'
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
  const languageCatalog = {
    java: {name: 'Java', badge: 'JAVA'},
    typescript: {name: 'TypeScript', badge: 'TS'},
    ts: {name: 'TypeScript', badge: 'TS'},
    javascript: {name: 'JavaScript', badge: 'JS'},
    js: {name: 'JavaScript', badge: 'JS'},
    python: {name: 'Python', badge: 'PY'},
    py: {name: 'Python', badge: 'PY'},
    go: {name: 'Go', badge: 'GO'},
    golang: {name: 'Go', badge: 'GO'},
    rust: {name: 'Rust', badge: 'RS'},
    rs: {name: 'Rust', badge: 'RS'},
    kotlin: {name: 'Kotlin', badge: 'KT'},
    kt: {name: 'Kotlin', badge: 'KT'},
    csharp: {name: 'C#', badge: 'C#'},
    'c#': {name: 'C#', badge: 'C#'},
    cpp: {name: 'C++', badge: 'C++'},
    'c++': {name: 'C++', badge: 'C++'},
    c: {name: 'C', badge: 'C'},
    ruby: {name: 'Ruby', badge: 'RB'},
    rb: {name: 'Ruby', badge: 'RB'},
    php: {name: 'PHP', badge: 'PHP'},
    swift: {name: 'Swift', badge: 'SWIFT'},
    scala: {name: 'Scala', badge: 'SCALA'},
    sql: {name: 'SQL', badge: 'SQL'},
    docker: {name: 'Docker', badge: 'DOCKER'},
    dockerfile: {name: 'Docker', badge: 'DOCKER'},
    protobuf: {name: 'Protobuf', badge: 'PROTO'},
    proto: {name: 'Protobuf', badge: 'PROTO'},
    yaml: {name: 'YAML', badge: 'YAML'},
    yml: {name: 'YAML', badge: 'YAML'},
    json: {name: 'JSON', badge: 'JSON'},
    shell: {name: 'Shell', badge: 'SH'},
    bash: {name: 'Shell', badge: 'SH'},
    sh: {name: 'Shell', badge: 'SH'},
    html: {name: 'HTML', badge: 'HTML'},
    css: {name: 'CSS', badge: 'CSS'}
  };
  const shortContextToken = identity => {
    const raw = String(identity || '').trim();
    if (!raw) return '';
    const sha = raw.match(/sha256:([0-9a-f]{8})/i);
    if (sha) return sha[1];
    const colon = raw.lastIndexOf(':');
    const tail = colon >= 0 ? raw.slice(colon + 1) : raw;
    return tail.length > 12 ? tail.slice(0, 12) : tail;
  };
  const formatSourceSetName = value => {
    const raw = String(value || '').trim();
    if (!raw) return '';
    const upper = raw.toUpperCase();
    if (upper === 'MAIN') return 'main';
    if (upper === 'TEST') return 'test';
    if (upper === 'GENERATED_MAIN') return 'generated main';
    if (upper === 'GENERATED_TEST') return 'generated test';
    return raw.toLowerCase().replaceAll('_', ' ');
  };
  const formatModuleName = value => {
    const raw = String(value || '').trim();
    if (!raw) return '';
    return raw === '.' ? 'Root module' : raw;
  };
  const languageDisplay = value => {
    const raw = String(value || '').trim();
    if (!raw) return null;
    const key = raw.toLowerCase();
    if (languageCatalog[key]) return languageCatalog[key];
    return {
      name: raw.charAt(0).toUpperCase() + raw.slice(1),
      badge: raw.toUpperCase().replace(/[^A-Z0-9#+]/g, '').slice(0, 6) || 'CODE'
    };
  };
  const humanizeContextFamily = family => {
    const raw = String(family || '').trim();
    if (!raw) return '';
    if (raw === 'repository.snapshot' || raw === 'repository.inventory-snapshot') return 'Source snapshot';
    if (raw === 'analysis.manifest') return 'Java analysis';
    if (raw === 'spring.context') return 'Spring configuration';
    if (raw === 'polyglot.context') return 'Polyglot context';
    const cleaned = raw.replaceAll('.', ' ').replaceAll('_', ' ').replaceAll('-', ' ').trim();
    return cleaned ? cleaned.charAt(0).toUpperCase() + cleaned.slice(1) : '';
  };
  const formatContextOption = (context, snapshot = '', provenance = null) => {
    const ctx = typeof context === 'string' ? {identity: context} : (context && typeof context === 'object' ? context : {});
    const identity = String(ctx.identity || '');
    const token = shortContextToken(identity);
    const meta = provenance?.metadata && typeof provenance.metadata === 'object' ? provenance.metadata : {};
    const family = String(provenance?.family || ctx.family || '');
    const buildMss = meta?.build?.moduleSourceSet && typeof meta.build.moduleSourceSet === 'object' ? meta.build.moduleSourceSet : null;
    const buildModule = buildMss?.module && !String(buildMss.module).includes('sha256:') ? String(buildMss.module) : '';
    const modulePath = String(meta.modulePath || ctx.modulePath || buildModule || meta.request?.module || meta.request?.root || '').trim();
    const sourceSet = formatSourceSetName(buildMss?.sourceSet || meta.sourceSet || ctx.sourceSet || meta.request?.sourceSet || '');
    const language = String(meta.language || meta.request?.language || ctx.language || '').trim();
    const rawKind = String(ctx.kind || '').trim().toLowerCase();
    const mod = formatModuleName(modulePath);

    if ((snapshot && identity === snapshot) || rawKind === 'repository' || family === 'repository.snapshot' || family === 'repository.inventory-snapshot') {
      return {label: 'Whole repository', badge: 'REPO', subtitle: 'All captured files & graphs', kind: 'repository'};
    }

    if (rawKind === 'spring' || family === 'spring.context' || identity.startsWith('spring-context:')) {
      let label;
      if (mod) {
        label = sourceSet && sourceSet !== 'main' ? `${mod} / Spring / ${sourceSet}` : `${mod} / Spring`;
      } else if (ctx.label) {
        label = String(ctx.label);
      } else {
        label = family === 'spring.context' ? 'Spring configuration' : (token ? `Spring configuration · ${token}` : 'Spring configuration');
      }
      const subtitle = mod
        ? ['Spring configuration', sourceSet || null].filter(Boolean).join(' · ')
        : (token ? `Spring beans & config · ${token}` : 'Spring beans & configuration');
      return {label, badge: 'SPRING', subtitle, kind: 'spring'};
    }

    const langLower = language.toLowerCase();
    if (rawKind === 'polyglot' || family === 'polyglot.context' || (langLower && langLower !== 'java')) {
      const lang = languageDisplay(language);
      let label;
      if (mod && lang) {
        label = mod === 'Root module' ? lang.name : `${mod} · ${lang.name}`;
      } else if (lang) {
        label = lang.name;
      } else if (ctx.label) {
        label = String(ctx.label);
      } else if (mod) {
        label = mod;
      } else {
        label = token ? `Polyglot context · ${token}` : 'Polyglot context';
      }
      const subtitle = [
        mod && mod !== label ? mod : null,
        sourceSet || null,
        lang ? `${lang.name} semantic context` : 'Native semantic context'
      ].filter(Boolean).join(' · ');
      return {label, badge: lang ? lang.badge : 'CODE', subtitle, kind: 'polyglot'};
    }

    if (rawKind === 'java' || family === 'analysis.manifest' || identity.startsWith('analysis:') || langLower === 'java') {
      let label;
      if (mod && sourceSet) {
        label = `${mod} / ${sourceSet}`;
      } else if (mod) {
        label = mod;
      } else if (ctx.label) {
        label = String(ctx.label);
      } else if (sourceSet) {
        label = `Java / ${sourceSet}`;
      } else {
        label = token ? `Java module · ${token}` : 'Java analysis';
      }
      const subtitle = mod && sourceSet
        ? `Java ${sourceSet} source set`
        : mod
          ? 'Java module'
          : (token ? `Captured Java analysis · ${token}` : 'Captured Java analysis');
      return {label, badge: 'JAVA', subtitle, kind: 'java'};
    }

    const prefix = ctx.label
      ? String(ctx.label)
      : (humanizeContextFamily(family) || (identity.startsWith('snapshot:') ? 'Source snapshot' : 'Build context'));
    const label = ctx.label || !token ? prefix : `${prefix} · ${token}`;
    const badge = identity.startsWith('snapshot:') ? 'SNAP' : 'CTX';
    const subtitle = family || (token ? `Captured context · ${token}` : 'Captured context');
    return {label, badge, subtitle, kind: 'context'};
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
  const shouldAutoExpandRunLog = (job, userInitiated = false) => {
    if (userInitiated) return true;
    if (!job || typeof job !== 'object') return false;
    return !terminal(job) || job.state === 'FAILED' || job.state === 'TIMED_OUT';
  };
  const stageProgressRatios = {
    QUEUED: 0,
    ACQUISITION: 0.16,
    PLATFORM: 0.34,
    ANALYSIS: 0.58,
    GRAPH: 0.76,
    POLICY: 0.88,
    PUBLICATION: 0.95,
    REUSED: 0.92,
    FINISHED: 1
  };
  const jobProgress = (job, submitting = false, liveProgress = null) => {
    if (submitting && !job) {
      return {determinate: false, ratio: 0, tone: 'accent', statusLabel: 'Starting', done: false};
    }
    if (!job || typeof job !== 'object') {
      return {determinate: false, ratio: 0, tone: 'accent', statusLabel: 'Idle', done: false};
    }
    const stageRatio = stageProgressRatios[job.stage] ?? 0.25;
    const liveRatio = liveProgress && liveProgress.overallRatio != null && Number.isFinite(Number(liveProgress.overallRatio))
      ? Math.max(0, Math.min(1, Number(liveProgress.overallRatio)))
      : null;
    if (job.state === 'QUEUED' || (!terminal(job) && job.stage === 'QUEUED')) {
      return {determinate: false, ratio: 0, tone: 'accent', statusLabel: 'Queued', done: false};
    }
    if (job.state === 'CANCELLING') {
      return {determinate: true, ratio: Math.max(0.15, liveRatio ?? stageRatio), tone: 'warn', statusLabel: 'Cancelling', done: false};
    }
    if (!terminal(job)) {
      const ratio = liveRatio != null ? Math.max(0.04, Math.min(0.995, liveRatio)) : Math.max(0.12, stageRatio);
      return {determinate: true, ratio, tone: 'accent', statusLabel: 'Running', done: false};
    }
    if (job.state === 'FAILED' || job.state === 'TIMED_OUT') {
      return {determinate: true, ratio: Math.max(0.2, stageRatio), tone: 'danger', statusLabel: job.state === 'TIMED_OUT' ? 'Timed out' : 'Failed', done: true};
    }
    if (job.state === 'CANCELLED') {
      return {determinate: true, ratio: Math.max(0.15, stageRatio), tone: 'warn', statusLabel: 'Cancelled', done: true};
    }
    return {determinate: true, ratio: 1, tone: 'mint', statusLabel: job.reuse ? 'Reused' : 'Done', done: true};
  };

  /* ── Architecture Assessment v2 presentation helpers ───────────────────── */
  const formatScore1 = value => {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    return n.toFixed(1);
  };
  const formatHealthRange = v2 => {
    if (!v2 || typeof v2 !== 'object') return 'Unavailable';
    if (v2.status === 'WITHHELD' || v2.score == null) return 'Withheld';
    const scoreNum = Number(v2.score);
    if (!Number.isFinite(scoreNum)) return 'Withheld';
    const delta = Number(v2.uncertaintyDelta ?? 0);
    const lower = v2.conservativeLowerBound != null ? Number(v2.conservativeLowerBound) : null;
    if (delta > 0 && lower != null && Number.isFinite(lower) && lower < scoreNum - 0.05) {
      return `${formatScore1(lower)}–${formatScore1(scoreNum)} / 100`;
    }
    return `${formatScore1(scoreNum)} / 100`;
  };
  const confidenceBadge = confidence => {
    const tier = String(confidence?.tier || 'INSUFFICIENT').toUpperCase();
    const score = confidence?.confidenceScore != null && Number.isFinite(Number(confidence.confidenceScore))
      ? Math.max(0, Math.min(100, Math.round(Number(confidence.confidenceScore))))
      : null;
    const pctText = score != null ? `${score}%` : '—';
    const map = {
      HIGH: {tier: 'HIGH', label: 'High confidence', icon: '●', tone: 'good'},
      MODERATE: {tier: 'MODERATE', label: 'Moderate confidence', icon: '◐', tone: 'warn'},
      LOW: {tier: 'LOW', label: 'Low confidence', icon: '◔', tone: 'warn'},
      INSUFFICIENT: {tier: 'INSUFFICIENT', label: 'Insufficient evidence', icon: '○', tone: 'danger'}
    };
    const meta = map[tier] || map.INSUFFICIENT;
    return {...meta, score, pctText, evidenceTier: confidence?.evidenceTier || ''};
  };
  const v2DimensionTone = (dim, confidenceTier) => {
    const tier = String(confidenceTier || '').toUpperCase();
    if (tier === 'INSUFFICIENT') return 'muted';
    if (!dim || dim.score == null || dim.status === 'WITHHELD' || dim.status === 'NOT_APPLICABLE') return 'muted';
    const s = Number(dim.score);
    if (!Number.isFinite(s)) return 'muted';
    const base = s >= 70 ? 'good' : s >= 50 ? 'warn' : 'danger';
    if (tier === 'LOW' && base === 'good') return 'warn';
    return base;
  };
  const v2OverallTone = v2 => v2DimensionTone(
    v2 ? {score: v2.score, status: v2.status} : null,
    v2?.confidence?.tier
  );
  const subMetricPosition = (rawValue, curve) => {
    if (rawValue == null || !Number.isFinite(Number(rawValue)) || !curve || typeof curve !== 'object') {
      return {percent: 0, zone: 'unknown', zoneLabel: 'Unavailable'};
    }
    const v = Number(rawValue);
    const ideal = Number(curve.ideal);
    const acceptable = Number(curve.acceptable);
    const poor = Number(curve.poor);
    const critical = Number(curve.critical);
    if (![ideal, acceptable, poor, critical].every(Number.isFinite)) {
      return {percent: 0, zone: 'unknown', zoneLabel: 'Unavailable'};
    }
    const higher = String(curve.direction || 'LOWER_IS_BETTER').toUpperCase() === 'HIGHER_IS_BETTER';
    const seg = (a, b, p0, p1) => {
      if (a === b) return p1;
      const t = (v - a) / (b - a);
      return p0 + Math.max(0, Math.min(1, t)) * (p1 - p0);
    };
    if (!higher) {
      if (v <= ideal) return {percent: 4, zone: 'ideal', zoneLabel: 'Ideal'};
      if (v <= acceptable) return {percent: Math.round(seg(ideal, acceptable, 4, 33)), zone: 'ideal', zoneLabel: 'Ideal–Acceptable'};
      if (v <= poor) return {percent: Math.round(seg(acceptable, poor, 33, 67)), zone: 'acceptable', zoneLabel: 'Acceptable–Poor'};
      if (v < critical) return {percent: Math.round(seg(poor, critical, 67, 96)), zone: 'poor', zoneLabel: 'Poor–Critical'};
      return {percent: 98, zone: 'critical', zoneLabel: 'Critical'};
    }
    if (v >= ideal) return {percent: 4, zone: 'ideal', zoneLabel: 'Ideal'};
    if (v >= acceptable) return {percent: Math.round(seg(ideal, acceptable, 4, 33)), zone: 'ideal', zoneLabel: 'Ideal–Acceptable'};
    if (v >= poor) return {percent: Math.round(seg(acceptable, poor, 33, 67)), zone: 'acceptable', zoneLabel: 'Acceptable–Poor'};
    if (v > critical) return {percent: Math.round(seg(poor, critical, 67, 96)), zone: 'poor', zoneLabel: 'Poor–Critical'};
    return {percent: 98, zone: 'critical', zoneLabel: 'Critical'};
  };

  /* ── Progressive staged pipeline & incremental delta helpers ───────────── */
  const isPreliminaryTier = tier => tier === 'PRELIMINARY_STRUCTURE' || tier === 'PARTIAL_CONTEXTS';
  const truthTierInfo = (tier, meta = {}) => {
    const raw = String(tier || 'NONE').toUpperCase();
    const completed = Number(meta?.completedContexts ?? 0);
    const total = Number(meta?.totalContexts ?? 0);
    if (raw === 'PRELIMINARY_STRUCTURE') {
      return {
        tier: raw,
        preliminary: true,
        authoritative: false,
        badgeTone: 'warn',
        label: 'PRELIMINARY STRUCTURE',
        shortLabel: 'Preliminary',
        detail: 'Fast structural skeleton preview from lexical scan — deep semantic resolution, Spring SAT, policy checks, and Architecture Health v2 are still computing.'
      };
    }
    if (raw === 'PARTIAL_CONTEXTS') {
      const countText = total > 0 ? `${completed}/${total} MODULES` : `${completed} MODULES`;
      return {
        tier: raw,
        preliminary: true,
        authoritative: false,
        badgeTone: 'warn',
        label: `PARTIAL · ${countText}`,
        shortLabel: `Partial (${countText})`,
        detail: `Verified contexts published for ${completed}${total > 0 ? ' of ' + total : ''} modules. Remaining modules and whole-repository Architecture Health v2 are still computing.`
      };
    }
    if (raw === 'DEEP_SEMANTIC_COMPLETE') {
      return {
        tier: raw,
        preliminary: false,
        authoritative: true,
        badgeTone: 'good',
        label: 'DEEP SEMANTIC COMPLETE',
        shortLabel: 'Verified',
        detail: 'Full semantic resolution, Spring configuration reasoning, policy evaluation, and Architecture Health v2 complete.'
      };
    }
    return {tier: 'NONE', preliminary: false, authoritative: false, badgeTone: 'muted', label: '', shortLabel: '', detail: ''};
  };
  const formatEtaDuration = seconds => {
    const s = Math.max(0, Math.round(Number(seconds)));
    if (!Number.isFinite(s)) return '';
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    const rem = s % 60;
    if (m < 60) return rem ? `${m}m ${rem}s` : `${m}m`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  };
  const formatThroughput = progress => {
    if (!progress || typeof progress !== 'object') return {rateText: '', etaText: '', stageCountText: ''};
    const rate = progress.filesPerSecond != null && Number.isFinite(Number(progress.filesPerSecond))
      ? `${Number(progress.filesPerSecond).toFixed(1)} files/s`
      : '';
    let etaText = '';
    if (progress.etaStatus === 'AVAILABLE' && progress.etaSeconds != null) {
      etaText = 'ETA ~' + formatEtaDuration(progress.etaSeconds);
    } else if (progress.etaStatus === 'ESTIMATING') {
      etaText = 'Estimating ETA…';
    } else if (progress.etaStatus === 'COMPLETE') {
      etaText = 'Complete';
    }
    const done = Number(progress.stageDone ?? 0);
    const total = Number(progress.stageTotal ?? 0);
    const stageCountText = total > 0 ? `${done.toLocaleString()} / ${total.toLocaleString()} files` : '';
    return {rateText: rate, etaText, stageCountText};
  };
  const summarizeIncremental = inc => {
    if (!inc || typeof inc !== 'object') return null;
    const addedFiles = Number(inc.addedFiles ?? 0);
    const modifiedFiles = Number(inc.modifiedFiles ?? 0);
    const deletedFiles = Number(inc.deletedFiles ?? 0);
    const reusedFiles = Number(inc.reusedFiles ?? inc.unchangedFiles ?? 0);
    const abiEarlyCutoffCount = Number(inc.abiEarlyCutoffCount ?? 0);
    const impactedDependentsCount = Number(inc.impactedDependentsCount ?? 0);
    const reusedModules = Number(inc.reusedModules ?? 0);
    const recomputedModules = Number(inc.recomputedModules ?? 0);
    const timeSavedEstimateMs = Number(inc.timeSavedEstimateMs ?? 0);
    const changedFiles = addedFiles + modifiedFiles + deletedFiles;
    const totalFiles = reusedFiles + addedFiles + modifiedFiles;
    const reusePct = totalFiles > 0 ? Math.round((reusedFiles / totalFiles) * 100) : 0;
    const formattedTimeSaved = timeSavedEstimateMs >= 1000
      ? '~' + (timeSavedEstimateMs >= 60_000
          ? formatEtaDuration(timeSavedEstimateMs / 1000)
          : (timeSavedEstimateMs / 1000).toFixed(1) + 's')
      : (timeSavedEstimateMs > 0 ? `~${timeSavedEstimateMs} ms` : '');
    const mode = String(inc.mode || (reusedFiles > 0 || reusedModules > 0 ? 'INCREMENTAL' : 'COLD'));
    return {
      mode,
      fallbackReason: String(inc.fallbackReason || ''),
      baseSnapshotId: inc.baseSnapshotId || null,
      baseGraphId: inc.baseGraphId || null,
      baseJobId: inc.baseJobId || null,
      addedFiles,
      modifiedFiles,
      deletedFiles,
      changedFiles,
      reusedFiles,
      totalFiles,
      reusePct,
      abiEarlyCutoffCount,
      impactedDependentsCount,
      reusedModules,
      recomputedModules,
      timeSavedEstimateMs,
      formattedTimeSaved,
      hasSavings: reusedFiles > 0 || reusedModules > 0 || abiEarlyCutoffCount > 0 || timeSavedEstimateMs > 0
    };
  };
  const buildCompareHash = ({left, right, repo, autorun = true} = {}) => {
    const params = new URLSearchParams();
    if (left) params.set('left', String(left));
    if (right) params.set('right', String(right));
    if (repo) params.set('repo', String(repo));
    if (autorun) params.set('autorun', '1');
    const qs = params.toString();
    return qs ? `#compare?${qs}` : '#compare';
  };
  const parseToolHash = rawHash => {
    const str = String(rawHash || '');
    const qIdx = str.indexOf('?');
    const baseHash = qIdx >= 0 ? str.slice(0, qIdx) : str;
    const params = {};
    if (qIdx >= 0) {
      for (const [k, v] of new URLSearchParams(str.slice(qIdx + 1)).entries()) {
        params[k] = v;
      }
    }
    return {baseHash, params};
  };

  /* ── GitHub Smart Intake, Multi-File Diff, Repair Gate & Plain-Language Help ── */
  const parseRepositoryInput = raw => {
    const trimmed = String(raw || '').trim();
    if (!trimmed) {
      return {kind: 'EMPTY', owner: null, repo: null, pullNumber: null, ref: null, repoUrl: null, normalizedSource: '', displayLabel: ''};
    }
    const prMatch = trimmed.match(/^(?:https?:\/\/)?(?:www\.)?github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?\/pull\/(\d+)(?:\/.*)?$/i);
    if (prMatch) {
      const owner = prMatch[1], repo = prMatch[2].replace(/\.git$/i, ''), pullNumber = Number(prMatch[3]);
      const repoUrl = `https://github.com/${owner}/${repo}`;
      return {kind: 'GITHUB_PR', owner, repo, pullNumber, ref: `pull/${pullNumber}/head`, repoUrl, normalizedSource: repoUrl, displayLabel: `${owner}/${repo} · PR #${pullNumber}`};
    }
    const ghRepoMatch = trimmed.match(/^(?:https?:\/\/)?(?:www\.)?github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?(?:\/(?:tree|blob|commit)\/([^?#]+))?(?:\/)?(?:[?#].*)?$/i);
    if (ghRepoMatch) {
      const owner = ghRepoMatch[1], repo = ghRepoMatch[2].replace(/\.git$/i, '');
      const ref = ghRepoMatch[3] ? decodeURIComponent(ghRepoMatch[3]).replace(/\/+$/, '') : null;
      const repoUrl = `https://github.com/${owner}/${repo}`;
      return {kind: 'GITHUB_REPO', owner, repo, pullNumber: null, ref, repoUrl, normalizedSource: repoUrl, displayLabel: ref ? `${owner}/${repo}@${ref}` : `${owner}/${repo}`};
    }
    if (/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(trimmed) && !trimmed.startsWith('.')) {
      const [owner, rawRepo] = trimmed.split('/');
      const repo = rawRepo.replace(/\.git$/i, '');
      const repoUrl = `https://github.com/${owner}/${repo}`;
      return {kind: 'GITHUB_REPO', owner, repo, pullNumber: null, ref: null, repoUrl, normalizedSource: repoUrl, displayLabel: `${owner}/${repo}`};
    }
    if (/^https?:\/\//i.test(trimmed)) {
      const label = trimmed.replace(/^https?:\/\//i, '').replace(/\.git$/i, '').replace(/\/+$/, '');
      return {kind: 'GIT_HTTPS', owner: null, repo: null, pullNumber: null, ref: null, repoUrl: trimmed, normalizedSource: trimmed, displayLabel: label};
    }
    return {kind: 'LOCAL_PATH', owner: null, repo: null, pullNumber: null, ref: null, repoUrl: null, normalizedSource: trimmed, displayLabel: trimmed};
  };

  const cleanDiffPath = raw => {
    const cleaned = String(raw || '').trim().replace(/^"(.*)"$/, '$1').split('\t')[0].trim();
    if (!cleaned || cleaned === '/dev/null') return '/dev/null';
    return cleaned.replace(/^[ab]\//, '');
  };

  const buildSideBySideRows = hunks => {
    const rows = [];
    for (const hunk of hunks || []) {
      if (hunk.header) {
        rows.push({kind: 'hunk', header: hunk.header});
      }
      const lines = hunk.lines || [];
      let i = 0;
      while (i < lines.length) {
        const cur = lines[i];
        if (cur.type === 'ctx' || cur.type === 'meta') {
          rows.push({
            kind: cur.type,
            leftNum: cur.oldNum, leftText: cur.text, leftType: cur.type,
            rightNum: cur.newNum, rightText: cur.text, rightType: cur.type
          });
          i++;
          continue;
        }
        const dels = [], adds = [];
        while (i < lines.length && lines[i].type === 'del') { dels.push(lines[i]); i++; }
        while (i < lines.length && lines[i].type === 'add') { adds.push(lines[i]); i++; }
        const count = Math.max(dels.length, adds.length);
        for (let k = 0; k < count; k++) {
          const d = dels[k] || null, a = adds[k] || null;
          rows.push({
            kind: 'change',
            leftNum: d ? d.oldNum : null, leftText: d ? d.text : '', leftType: d ? 'del' : 'empty',
            rightNum: a ? a.newNum : null, rightText: a ? a.text : '', rightType: a ? 'add' : 'empty'
          });
        }
      }
    }
    return rows;
  };

  const parseUnifiedDiffFiles = (patchText, fallbackPath = 'patch') => {
    const raw = String(patchText ?? '').replace(/\r\n?/g, '\n');
    if (!raw.trim()) return [];
    const lines = raw.split('\n');
    const files = [];
    let current = null;
    let currentHunk = null;
    let oldLine = 0, newLine = 0;

    const finalizeFile = file => {
      if (!file) return;
      if (file.oldPath === '/dev/null') file.changeType = 'CREATE';
      else if (file.newPath === '/dev/null') file.changeType = 'DELETE';
      else file.changeType = 'MODIFY';
      file.path = (file.newPath && file.newPath !== '/dev/null' ? file.newPath : file.oldPath) || fallbackPath;
      file.rows = buildSideBySideRows(file.hunks);
      files.push(file);
    };

    const ensureFile = (oldPath = null, newPath = null) => {
      if (!current) {
        current = {path: fallbackPath, oldPath, newPath, changeType: 'MODIFY', additions: 0, deletions: 0, hunks: [], rows: []};
      }
      return current;
    };

    const ensureHunk = (header = '') => {
      const f = ensureFile();
      if (!currentHunk) {
        currentHunk = {header, lines: []};
        f.hunks.push(currentHunk);
      }
      return currentHunk;
    };

    for (let idx = 0; idx < lines.length; idx++) {
      const line = lines[idx];
      if (idx === lines.length - 1 && line === '' && files.length + (current ? 1 : 0) > 0) continue;
      if (line.startsWith('diff --git ')) {
        if (current) finalizeFile(current);
        const match = line.match(/^diff --git a\/(.+?) b\/(.+)$/);
        current = {
          path: match ? match[2] : fallbackPath,
          oldPath: match ? match[1] : null,
          newPath: match ? match[2] : null,
          changeType: 'MODIFY', additions: 0, deletions: 0, hunks: [], rows: []
        };
        currentHunk = null;
        oldLine = 0; newLine = 0;
        continue;
      }
      if (/^(?:new file mode|deleted file mode|old mode|new mode|index |similarity index|rename from|rename to)/.test(line)) {
        const f = ensureFile();
        if (line.startsWith('new file mode')) f.oldPath = '/dev/null';
        else if (line.startsWith('deleted file mode')) f.newPath = '/dev/null';
        continue;
      }
      if (line.startsWith('--- ')) {
        if (current && current.hunks.length > 0) {
          finalizeFile(current);
          current = null;
          currentHunk = null;
        }
        const f = ensureFile();
        f.oldPath = cleanDiffPath(line.slice(4));
        continue;
      }
      if (line.startsWith('+++ ')) {
        const f = ensureFile();
        f.newPath = cleanDiffPath(line.slice(4));
        continue;
      }
      const hunk = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/);
      if (hunk) {
        const f = ensureFile();
        oldLine = Number(hunk[1]);
        newLine = Number(hunk[2]);
        currentHunk = {header: line, lines: []};
        f.hunks.push(currentHunk);
        continue;
      }
      if (line.startsWith('\\ No newline at end of file')) {
        ensureHunk().lines.push({type: 'meta', oldNum: null, newNum: null, text: line});
        continue;
      }
      if (line.startsWith('+')) {
        const f = ensureFile();
        f.additions++;
        ensureHunk().lines.push({type: 'add', oldNum: null, newNum: newLine ? newLine++ : null, text: line.slice(1)});
        continue;
      }
      if (line.startsWith('-')) {
        const f = ensureFile();
        f.deletions++;
        ensureHunk().lines.push({type: 'del', oldNum: oldLine ? oldLine++ : null, newNum: null, text: line.slice(1)});
        continue;
      }
      if (current || currentHunk) {
        const text = line.startsWith(' ') ? line.slice(1) : line;
        ensureHunk().lines.push({
          type: 'ctx',
          oldNum: oldLine ? oldLine++ : null,
          newNum: newLine ? newLine++ : null,
          text
        });
      }
    }
    if (current) finalizeFile(current);
    return files;
  };

  const summarizePatch = (patchText, fallbackPath = 'patch') => {
    const files = parseUnifiedDiffFiles(patchText, fallbackPath);
    let totalAdditions = 0, totalDeletions = 0;
    for (const f of files) {
      totalAdditions += f.additions;
      totalDeletions += f.deletions;
    }
    return {totalFiles: files.length, totalAdditions, totalDeletions, files};
  };

  const formatScoreV2Delta = raw => {
    if (!raw || typeof raw !== 'object') {
      return {available: false, beforeText: '—', afterText: '—', deltaValue: null, deltaText: 'Unavailable', tone: 'neutral', beforeBand: '', afterBand: '', dimensions: []};
    }
    const before = raw.before ?? raw.beforeScore ?? null;
    const after = raw.after ?? raw.afterScore ?? null;
    const beforeNum = before != null && Number.isFinite(Number(before)) ? Number(before) : null;
    const afterNum = after != null && Number.isFinite(Number(after)) ? Number(after) : null;
    const rawDelta = raw.delta ?? raw.deltaScore ?? (beforeNum != null && afterNum != null ? afterNum - beforeNum : null);
    const deltaNum = rawDelta != null && Number.isFinite(Number(rawDelta)) ? Number(rawDelta) : null;
    const tone = deltaNum == null ? 'neutral' : deltaNum > 0.05 ? 'good' : deltaNum < -0.05 ? 'bad' : 'neutral';
    const sign = deltaNum == null ? '' : deltaNum > 0 ? '+' : '';
    const dimsRaw = Array.isArray(raw.dimensions) ? raw.dimensions : Array.isArray(raw.dimensionDeltas) ? raw.dimensionDeltas : [];
    const dimensions = dimsRaw.map(d => {
      const b = d.before != null && Number.isFinite(Number(d.before)) ? Number(d.before) : null;
      const a = d.after != null && Number.isFinite(Number(d.after)) ? Number(d.after) : null;
      const dv = d.delta != null && Number.isFinite(Number(d.delta)) ? Number(d.delta) : (b != null && a != null ? a - b : null);
      const dTone = dv == null ? 'neutral' : dv > 0.05 ? 'good' : dv < -0.05 ? 'bad' : 'neutral';
      return {
        id: d.id || '',
        displayName: d.displayName || d.name || d.id || 'Dimension',
        before: b,
        after: a,
        delta: dv,
        deltaText: dv == null ? '—' : `${dv > 0 ? '+' : ''}${dv.toFixed(1)}`,
        tone: dTone
      };
    });
    return {
      available: beforeNum != null || afterNum != null || deltaNum != null,
      beforeText: beforeNum != null ? formatScore1(beforeNum) : '—',
      afterText: afterNum != null ? formatScore1(afterNum) : '—',
      deltaValue: deltaNum,
      deltaText: deltaNum != null ? `${sign}${deltaNum.toFixed(1)}` : 'Unavailable',
      tone,
      beforeBand: String(raw.beforeBand || ''),
      afterBand: String(raw.afterBand || ''),
      dimensions
    };
  };

  const repairTrustMeta = (verdict, trustLabel) => {
    const v = String(verdict || 'UNVERIFIED').toUpperCase();
    const t = String(trustLabel || '').toUpperCase();
    const verdictMap = {
      VERIFIED: {label: 'Verified in isolated sandbox', shortLabel: 'VERIFIED', tone: 'good'},
      VERIFIED_DEMO: {label: 'Verified in demo VM', shortLabel: 'VERIFIED_DEMO', tone: 'good'},
      VERIFIED_LOCAL_BUILD: {label: 'Verified in sandbox build', shortLabel: 'VERIFIED', tone: 'good'},
      UNVERIFIED: {label: 'Unverified candidate', shortLabel: 'UNVERIFIED', tone: 'warn'},
      REJECTED: {label: 'Rejected by verification', shortLabel: 'REJECTED', tone: 'bad'}
    };
    const trustMap = {
      VERIFIED_ISOLATED_SANDBOX: {label: 'Isolated Sandbox Verified', tone: 'good', summary: 'Compiled, tested, and re-analyzed inside an isolated sandbox container/VM with zero host execution.'},
      ISOLATED_VM_VERIFIED: {label: 'Isolated VM Verified', tone: 'good', summary: 'Compiled, tested, and re-analyzed inside a networkless QEMU/Hyper-V isolated guest VM with zero host execution.'},
      ISOLATED_CONTAINER_VERIFIED: {label: 'Hardened Container Verified', tone: 'good', summary: 'Compiled, tested, and re-analyzed inside a hardened --network=none --read-only container with zero host execution.'},
      ISOLATED_VM_REJECTED: {label: 'Isolated VM Rejected', tone: 'bad', summary: 'Isolated guest VM verification failed build, test, or policy regression checks. Patch cannot be applied.'},
      ISOLATED_CONTAINER_REJECTED: {label: 'Hardened Container Rejected', tone: 'bad', summary: 'Hardened container verification failed build, test, or policy regression checks. Patch cannot be applied.'},
      ISOLATED_VM_UNVERIFIED: {label: 'Isolated VM Unverified', tone: 'warn', summary: 'Isolated guest VM ran without full test or policy verification (e.g., tests skipped). Explicit acknowledgement required before apply.'},
      ISOLATED_CONTAINER_UNVERIFIED: {label: 'Hardened Container Unverified', tone: 'warn', summary: 'Hardened container ran without full test or policy verification (e.g., tests skipped). Explicit acknowledgement required before apply.'},
      VERIFIED_DEMO_VM: {label: 'Demo VM Verified', tone: 'good', summary: 'Verified inside the registered Hyper-V/QEMU demo worker against canonical baseline and negative control.'},
      PASSIVE_REANALYSIS_ONLY: {label: 'Passive Reanalysis Only', tone: 'warn', summary: 'Static reanalysis and policy checks ran without an isolated build/test sandbox. Configure Sandbox Runner for compile/test proof.'},
      SYNTAX_ONLY: {label: 'Syntax Check Only', tone: 'warn', summary: 'Passed compiler/parser syntax gate only; isolated build, test suite, and reanalysis have not run.'},
      COMPILER_SYNTAX_ONLY: {label: 'Compiler Syntax Check Only', tone: 'warn', summary: 'Passed syntax compilation only; full isolated sandbox build, test suite, and reanalysis have not run.'},
      UNVERIFIED_CANDIDATE: {label: 'Unverified Candidate Patch', tone: 'warn', summary: 'Proposed diff has not completed isolated build/test verification. Explicit acknowledgement required before apply.'},
      REJECTED_VERIFICATION: {label: 'Verification Failed / Rejected', tone: 'bad', summary: 'One or more build, test, or policy regression checks failed. Patch cannot be applied.'}
    };
    const vm = verdictMap[v] || verdictMap.UNVERIFIED;
    let effectiveT = t;
    if (v === 'REJECTED') {
      if (effectiveT === 'ISOLATED_CONTAINER_VERIFIED') effectiveT = 'ISOLATED_CONTAINER_REJECTED';
      else if (effectiveT === 'ISOLATED_VM_VERIFIED') effectiveT = 'ISOLATED_VM_REJECTED';
    } else if (v === 'UNVERIFIED') {
      if (effectiveT === 'ISOLATED_CONTAINER_VERIFIED') effectiveT = 'ISOLATED_CONTAINER_UNVERIFIED';
      else if (effectiveT === 'ISOLATED_VM_VERIFIED') effectiveT = 'ISOLATED_VM_UNVERIFIED';
    }
    const tm = trustMap[effectiveT] || (v === 'REJECTED' ? trustMap.REJECTED_VERIFICATION
      : (v === 'VERIFIED' || v === 'VERIFIED_DEMO' ? trustMap.VERIFIED_ISOLATED_SANDBOX : trustMap.UNVERIFIED_CANDIDATE));
    return {
      verdict: v,
      trustLabel: effectiveT || (v === 'REJECTED' ? 'REJECTED_VERIFICATION' : v === 'UNVERIFIED' ? 'UNVERIFIED_CANDIDATE' : 'VERIFIED_ISOLATED_SANDBOX'),
      verdictLabel: vm.label,
      verdictShort: vm.shortLabel,
      verdictTone: vm.tone,
      trustTitle: tm.label,
      trustTone: tm.tone,
      trustSummary: tm.summary,
      isVerified: vm.tone === 'good',
      isRejected: vm.tone === 'bad'
    };
  };

  const behavioralVerdictMeta = (verdict, intent = '') => {
    const v = String(verdict || 'UNKNOWN_UNTESTED').toUpperCase();
    const intentNorm = String(intent || '').toUpperCase();
    if (v === 'EQUIVALENT_ON_BOUNDED_CHECKS') {
      return {
        verdict: v,
        label: 'Equivalent on Bounded Checks',
        shortLabel: 'EQUIVALENT',
        tone: 'good',
        summary: 'Baseline and candidate snapshots matched on all executed isolated tests and characterization probes.'
      };
    }
    if (v === 'BEHAVIOR_CHANGED') {
      const intended = intentNorm === 'INTENDED_FIX';
      return {
        verdict: v,
        label: intended ? 'Behavior Changed (Intended Fix)' : 'Behavior Changed (Witness Captured)',
        shortLabel: 'BEHAVIOR_CHANGED',
        tone: intended ? 'warn' : 'bad',
        summary: intended
          ? 'Candidate changed runtime test behavior from failing baseline to passing candidate; review witness below.'
          : 'Reproducible behavioral delta detected between baseline and candidate inside isolated sandbox.'
      };
    }
    return {
      verdict: 'UNKNOWN_UNTESTED',
      label: 'Unknown (Untested)',
      shortLabel: 'UNKNOWN_UNTESTED',
      tone: 'warn',
      summary: 'No dual baseline/candidate tests or characterization probes executed; behavioral equivalence is NOT claimed.'
    };
  };

  const springPreservationMeta = status => {
    const s = String(status || 'NOT_APPLICABLE').toUpperCase();
    if (s === 'PRESERVED') {
      return {
        status: s,
        label: 'Spring Truth Regions Preserved',
        shortLabel: 'PRESERVED',
        tone: 'good',
        summary: 'All baseline Spring MUST bean bindings, truth regions, and endpoints remained intact in the candidate graph.'
      };
    }
    if (s === 'DRIFT_DETECTED') {
      return {
        status: s,
        label: 'Spring Truth-Region Drift Detected',
        shortLabel: 'DRIFT_DETECTED',
        tone: 'bad',
        summary: 'One or more Spring MUST truth regions flipped (MUST -> MAY/NEVER/UNKNOWN) or endpoints were removed.'
      };
    }
    return {
      status: 'NOT_APPLICABLE',
      label: 'Spring Check Not Applicable',
      shortLabel: 'NOT_APPLICABLE',
      tone: 'muted',
      summary: 'No Spring bean contexts or SAT truth regions were present in the baseline graph.'
    };
  };

  const evaluateConfirmationGate = (preview, options = {}) => {
    const mode = String(options.mode || 'NEW_BRANCH_WORKTREE').toUpperCase();
    const blockers = [];
    const warnings = [...(preview?.warnings || [])];
    const verdict = String(preview?.verdict || options.verdict || 'UNVERIFIED').toUpperCase();
    const isRejected = verdict === 'REJECTED';
    const isVerified = verdict === 'VERIFIED' || verdict === 'VERIFIED_DEMO' || verdict === 'VERIFIED_LOCAL_BUILD';
    const requiresUnverifiedAck = !isVerified && !isRejected;

    if (isRejected) {
      blockers.push('This patch was REJECTED by verification and is hard-blocked from being applied or published.');
    }
    if (preview && preview.beforeHashesValid === false) {
      blockers.push('Source files drifted since snapshot capture (before-hash mismatch). Re-analyze before applying.');
    }
    if (Array.isArray(preview?.conflicts) && preview.conflicts.length > 0) {
      blockers.push(`Patch conflict detected in ${preview.conflicts.length} file(s): ${preview.conflicts.slice(0, 3).join(', ')}.`);
    }
    if (mode === 'NEW_BRANCH_WORKTREE') {
      const branch = String(options.branchName || '').trim();
      if (!branch) blockers.push('Enter a target Git branch name.');
      else if (!/^[A-Za-z0-9._/-]+$/.test(branch) || branch.includes('..') || branch.startsWith('-') || branch.endsWith('.lock') || branch.endsWith('/')) {
        blockers.push('Branch name contains invalid Git ref characters.');
      }
    }
    if (mode === 'GITHUB_PR') {
      if (!String(options.prTitle || '').trim()) blockers.push('Enter a Pull Request title before publishing.');
      if (!options.remoteConfirmed) blockers.push('Explicitly confirm pushing this branch and opening a GitHub Pull Request.');
    }
    if (requiresUnverifiedAck && !options.acknowledgeUnverified) {
      blockers.push('Acknowledge that this patch is unverified before proceeding.');
    }
    if (!options.userConfirmed) {
      blockers.push('Confirm the safety checklist before executing this write action.');
    }
    return {
      allowed: blockers.length === 0,
      hardBlocked: isRejected || preview?.beforeHashesValid === false || Boolean(preview?.conflicts?.length),
      requiresUnverifiedAck,
      isVerified,
      blockers,
      warnings
    };
  };

  const metricPlainHelp = Object.freeze({
    NCCD: {title: 'Normalized Cumulative Component Dependency (NCCD)', plain: 'Measures transitive coupling compared to a balanced binary tree. Values <= 1.0 are ideal; values > 1.5 mean small edits ripple across many classes.'},
    TurboMQ: {title: 'Modularization Quality (TurboMQ)', plain: 'Measures how tightly classes group inside their own package versus reaching across packages. Higher is better (> 0.65 indicates cohesive modules).'},
    SDP: {title: 'Stable Dependencies Principle (SDP)', plain: 'Stable packages (depended on by many) should not depend on volatile packages. Each violation marks an inverted dependency that destabilizes callers.'},
    A: {title: 'Abstractness (A)', plain: 'Ratio of interfaces and abstract classes to total types in a package (0 = all concrete, 1 = all abstract).'},
    I: {title: 'Instability (I)', plain: 'Ce / (Ca + Ce) — fraction of outgoing dependencies (0 = maximally stable/depended-on, 1 = maximally unstable/leaf).'},
    D: {title: 'Distance from Main Sequence (D)', plain: '|A + I - 1| — how far a package sits from the ideal stability/abstraction balance. Near 0 is balanced; high D signals Zone of Pain (rigid concrete) or Uselessness.'},
    MUST: {title: 'Required in Every Configuration (MUST)', plain: 'Guaranteed to exist under every modeled Spring profile and property combination.'},
    MAY: {title: 'Conditional Dependency (MAY)', plain: 'Exists only under specific Spring profiles or conditional beans (witness configuration recorded).'},
    UNKNOWN_OVERLAY: {title: 'Uncertain Dependency (UNKNOWN)', plain: 'Opaque condition or missing dependency prevented proving whether the link is active; kept explicit rather than assumed absent.'},
    BOUNDED_UNION: {title: 'All Observed Dependencies (UNION)', plain: 'Superposition of every dependency observed across any modeled configuration — alternatives may never coexist at runtime.'},
    PPR: {title: 'Personalized PageRank (PPR)', plain: 'Ranks files by structural centrality in the dependency graph so the most influential entrypoints and core contracts surface first.'}
  });

  const explainMetric = key => metricPlainHelp[String(key || '').trim()] || null;

  const gapRemediationActions = (topReasons = []) => {
    const out = [];
    const seen = new Set();
    for (const row of topReasons || []) {
      const code = String(row.reason || row.code || '').toUpperCase();
      if (!code || seen.has(code)) continue;
      seen.add(code);
      const info = humanReason(code);
      const obligation = classifyGapObligation(code);
      out.push({
        code,
        count: Number(row.count || 0),
        title: info.title,
        explanation: info.explanation,
        action: info.action,
        bucket: obligation.bucket,
        bucketLabel: obligation.label
      });
    }
    return out;
  };

  const pipelineStageSummary = (job, liveProgress = null) => {
    const stage = String(liveProgress?.stage || job?.stage || 'QUEUED').toUpperCase();
    const stateVal = String(liveProgress?.state || job?.state || 'QUEUED').toUpperCase();
    const tier = String(liveProgress?.truthTier || job?.publicationStage || 'NONE').toUpperCase();
    const failed = stateVal === 'FAILED' || stateVal === 'TIMED_OUT';
    const cancelled = stateVal === 'CANCELLED';
    const done = terminal({state: stateVal}) && !failed && !cancelled;

    let activeIndex = 0;
    if (done || tier === 'DEEP_SEMANTIC_COMPLETE' || stage === 'FINISHED' || stage === 'REUSED') activeIndex = 4;
    else if (stage === 'POLICY' || stage === 'PUBLICATION') activeIndex = 3;
    else if (stage === 'ANALYSIS' || stage === 'GRAPH' || tier === 'PARTIAL_CONTEXTS') activeIndex = 2;
    else if (stage === 'PLATFORM' || tier === 'PRELIMINARY_STRUCTURE') activeIndex = 1;
    else activeIndex = 0;

    const defs = [
      {id: 'capture', label: '1. Capture'},
      {id: 'preliminary', label: '2. Preliminary Map'},
      {id: 'semantic', label: '3. Deep Semantic'},
      {id: 'policy', label: '4. Score & Policy'}
    ];
    const steps = defs.map((def, idx) => {
      let status = 'pending';
      if (done) status = 'done';
      else if (idx < activeIndex) status = 'done';
      else if (idx === activeIndex) status = failed ? 'failed' : cancelled ? 'warn' : 'active';
      return {...def, status};
    });
    return {steps, activeIndex: Math.min(3, activeIndex), done, failed, cancelled};
  };

  /* ── Multi-User GitHub Auth, Permalink, CODEOWNERS & Quality Gate Helpers ── */
  const isSafeHttpUrl = raw => {
    const str = String(raw || '').trim();
    if (!/^https?:\/\//i.test(str)) return false;
    try {
      const parsed = new URL(str);
      return (parsed.protocol === 'https:' || parsed.protocol === 'http:') && Boolean(parsed.hostname);
    } catch (_) {
      return false;
    }
  };

  const buildGitHubPermalink = ({host = 'github.com', owner, repo, commitSha, ref, path, startLine, endLine} = {}) => {
    const cleanHost = String(host || 'github.com').trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '');
    const cleanOwner = String(owner || '').trim();
    const cleanRepo = String(repo || '').trim().replace(/\.git$/i, '');
    const rev = String(commitSha || ref || '').trim();
    const cleanPath = String(path || '').trim().replace(/^\/+/, '');
    if (!cleanHost || !cleanOwner || !cleanRepo || !rev || !cleanPath) return null;
    if (!/^[A-Za-z0-9.-]+(?::\d+)?$/.test(cleanHost)) return null;
    if (!/^[A-Za-z0-9._-]+$/.test(cleanOwner) || !/^[A-Za-z0-9._-]+$/.test(cleanRepo)) return null;
    const encodedPath = cleanPath.split('/').filter(Boolean).map(seg => encodeURIComponent(seg)).join('/');
    const base = `https://${cleanHost}/${encodeURIComponent(cleanOwner)}/${encodeURIComponent(cleanRepo)}/blob/${encodeURIComponent(rev)}/${encodedPath}`;
    const s = Number(startLine);
    const e = Number(endLine);
    if (Number.isFinite(s) && s >= 1) {
      if (Number.isFinite(e) && e > s) return `${base}#L${Math.floor(s)}-L${Math.floor(e)}`;
      return `${base}#L${Math.floor(s)}`;
    }
    return base;
  };

  const matchCodeownersPattern = (pattern, filePath) => {
    const raw = String(pattern || '').trim();
    const target = String(filePath || '').trim().replace(/^\/+/, '');
    if (!raw || !target || raw.startsWith('#')) return false;
    if (raw === '*') return true;
    const anchored = raw.startsWith('/');
    const dirOnly = raw.endsWith('/');
    let p = raw.replace(/^\/+/, '');
    if (dirOnly) p = p.slice(0, -1);
    const regexBody = p
      .replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\*\*/g, '___DOUBLESTAR___')
      .replace(/\*/g, '[^/]*')
      .replace(/\?/g, '[^/]')
      .replace(/___DOUBLESTAR___/g, '.*');
    const prefix = anchored || p.includes('/') ? '^' : '(?:^|/)';
    const suffix = dirOnly ? '(?:/|$)' : '(?:$|/)';
    try {
      return new RegExp(prefix + regexBody + suffix).test(target);
    } catch (_) {
      return false;
    }
  };

  const matchCodeownersForPath = (rulesOrMap, filePath) => {
    const target = String(filePath || '').trim().replace(/^\/+/, '');
    if (!target) return [];
    const rules = Array.isArray(rulesOrMap) ? rulesOrMap : (Array.isArray(rulesOrMap?.rules) ? rulesOrMap.rules : []);
    let matched = [];
    for (const rule of rules) {
      if (!rule || !rule.pattern) continue;
      if (matchCodeownersPattern(rule.pattern, target)) {
        matched = Array.isArray(rule.owners) ? rule.owners.filter(Boolean).map(String) : [];
      }
    }
    return [...new Set(matched)];
  };

  const formatQualityGateBadge = gate => {
    const raw = typeof gate === 'string' ? gate : (gate?.verdict || gate?.status || 'NOT_RUN');
    const norm = String(raw || 'NOT_RUN').toUpperCase();
    if (norm === 'PASS' || norm === 'PASSED' || norm === 'SUCCESS') {
      return {verdict: 'PASSED', label: 'Quality Gate: PASSED', shortLabel: 'PASS', tone: 'good'};
    }
    if (norm === 'WARN' || norm === 'WARNING') {
      return {verdict: 'WARN', label: 'Quality Gate: WARN', shortLabel: 'WARN', tone: 'warn'};
    }
    if (norm === 'FAIL' || norm === 'FAILED' || norm === 'ERROR') {
      return {verdict: 'FAILED', label: 'Quality Gate: FAILED', shortLabel: 'FAIL', tone: 'bad'};
    }
    return {verdict: 'NOT_RUN', label: 'Quality Gate: Not Run', shortLabel: 'N/A', tone: 'muted'};
  };

  const formatRateLimit = rate => {
    if (!rate || typeof rate !== 'object' || rate.limit == null || rate.remaining == null) {
      return {available: false, percent: 0, tone: 'muted', text: 'Rate limit unavailable', resetText: ''};
    }
    const limit = Math.max(1, Number(rate.limit));
    const remaining = Math.max(0, Math.min(limit, Number(rate.remaining)));
    const percent = Math.round((remaining / limit) * 100);
    const tone = percent <= 10 ? 'bad' : percent <= 30 ? 'warn' : 'good';
    let resetText = '';
    if (rate.resetAtEpochSec != null || rate.resetEpochSeconds != null || rate.reset != null) {
      const epochMs = Number(rate.resetAtEpochSec ?? rate.resetEpochSeconds ?? rate.reset) * 1000;
      const diffSec = Math.max(0, Math.round((epochMs - Date.now()) / 1000));
      if (Number.isFinite(diffSec)) resetText = diffSec < 60 ? 'resets <1m' : `resets in ${formatEtaDuration(diffSec)}`;
    }
    return {available: true, remaining, limit, percent, tone, text: `${remaining.toLocaleString()} / ${limit.toLocaleString()} req`, resetText};
  };

  const formatRelativeTime = (isoOrMs, nowMs = Date.now()) => {
    if (isoOrMs == null || isoOrMs === '') return '';
    const ts = typeof isoOrMs === 'number' ? isoOrMs : Date.parse(String(isoOrMs));
    if (!Number.isFinite(ts)) return '';
    const diffSec = Math.max(0, Math.round((nowMs - ts) / 1000));
    if (diffSec < 60) return 'just now';
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
    const days = Math.floor(diffSec / 86400);
    if (days < 30) return `${days}d ago`;
    if (days < 365) return `${Math.floor(days / 30)}mo ago`;
    return `${Math.floor(days / 365)}y ago`;
  };

  const sanitizeAvatarMonogram = (login, name) => {
    const src = String(name || login || 'GH').trim();
    const parts = src.split(/[\s._-]+/).filter(Boolean);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return src.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || 'GH';
  };

  const model = {terminal, label, pageInfo, request, assessment, summaryCards, scoreEvidence, apiError, realizationRequest, compareEdges, completePage, shortDigest, humanReason, summarizeGaps, policyTemplates, parseCitations, classifySseToolEvent, buildImpactQuery, rankApplicableRecipes, classifyGapObligation, formatContextOption, selectDefaultContext, findPreferredSpringContext, shouldAutoExpandRunLog, jobProgress, formatHealthRange, confidenceBadge, v2DimensionTone, v2OverallTone, subMetricPosition, isPreliminaryTier, truthTierInfo, formatEtaDuration, formatThroughput, summarizeIncremental, buildCompareHash, parseToolHash, parseRepositoryInput, parseUnifiedDiffFiles, summarizePatch, formatScoreV2Delta, repairTrustMeta, behavioralVerdictMeta, springPreservationMeta, evaluateConfirmationGate, metricPlainHelp, explainMetric, gapRemediationActions, pipelineStageSummary, isSafeHttpUrl, buildGitHubPermalink, matchCodeownersForPath, formatQualityGateBadge, formatRateLimit, formatRelativeTime, sanitizeAvatarMonogram};
  if (typeof module !== 'undefined') module.exports = model;
  else root.EvoModel = model;
})(globalThis);
