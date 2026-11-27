const {test} = require('node:test');
const assert = require('node:assert/strict');
const model = require('../../main/resources/web/model.js');
test('default packages have a useful label rather than an internal name tuple', () => {
  assert.equal(model.label({kind:'PACKAGE',qualifiedName:'',canonicalName:'java:v1:["package",[]]'}),'(default package)');
});
test('configuration comparisons never promote an unloaded or partial page to absent', () => {
  const edge={identity:'edge',source:'a',target:'b'};
  assert.deepEqual(model.compareEdges({items:[edge],status:'COMPLETE'},{items:[],status:'PARTIAL'}).map(r=>[r.a,r.b]), [['Present','Not established']]);
  assert.deepEqual(model.compareEdges({items:[edge],status:'COMPLETE'},{items:[],status:'COMPLETE'}).map(r=>[r.a,r.b]), [['Present','Absent in this view']]);
  assert.equal(model.compareEdges({items:[],status:'COMPLETE',continuation:'next'},{items:[edge],status:'COMPLETE'})[0].a,'Not established');
});
test('a realized request carries the selected backend realization and cannot silently become union', () => {
  const selection={realization:{world:'exact-world',assignment:{}}};
  assert.deepEqual(model.realizationRequest(selection),{projection:'REALIZED',realization:selection.realization});
  assert.throws(()=>model.realizationRequest(null));
});
test('missing assessments never become healthy scores', () => {
  assert.equal(model.assessment(null), 'Unavailable');
  assert.equal(model.assessment({score: null}), 'Unavailable');
  assert.equal(model.assessment({score: 0}), '0 / 100');
});
test('pagination shows loaded and exact totals', () => {
  assert.equal(model.pageInfo({items: [1], status: 'PARTIAL', bounds: {truncated: true}, continuation: 'next'}), '1 shown · More available');
  assert.equal(model.pageInfo({items: [], exactTotal: 0, status: 'COMPLETE'}), '0 of 0');
});
test('pagination keeps a returned total without displaying evaluation state', () => {
  assert.equal(model.pageInfo({items: [1], returnedTotal: 7, evaluationStatus: 'WITHHELD', continuation: 'next'}), '1 of 7 returned · More available');
});
test('query requests keep one exact selected context and incoming impact is explicit', () => {
  assert.deepEqual(model.request('exact-context'), {context: 'exact-context', scope: 'TYPE', projection: 'BOUNDED_UNION', direction: 'OUTGOING', relationshipKinds: ['graph.depends-on']});
  assert.equal(model.terminal({state: 'CANCELLING'}), false);
  for (const state of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'EMPTY', 'TIMED_OUT', 'CANCELLED']) assert.equal(model.terminal({state}), true);
});
test('shortDigest condenses sha256 digests for scannable display while keeping readable names intact', () => {
  const digest = 'sha256:' + '655a560f'.repeat(8);
  assert.equal(model.shortDigest(digest), '#655a560f');
  assert.equal(model.shortDigest('com.example.UserService'), 'com.example.UserService');
});
test('humanReason translates cryptic gap codes into actionable explanations without hiding the raw code', () => {
  const info = model.humanReason('OPAQUE_CONDITION');
  assert.equal(info.code, 'OPAQUE_CONDITION');
  assert.match(info.title, /condition/i);
  assert.ok(info.explanation.length > 10);
  assert.ok(info.action.length > 10);
  const fallback = model.humanReason('CUSTOM_FUTURE_CODE');
  assert.equal(fallback.code, 'CUSTOM_FUTURE_CODE');
  assert.equal(fallback.title, 'Custom future code');
});
test('summarizeGaps groups observed gap rows by reason code in deterministic count order', () => {
  const summary = model.summarizeGaps([
    {reasonCode: 'SEMANTIC_UNRESOLVED', mechanismCategory: 'JAVA'},
    {reasonCode: 'OPAQUE_CONDITION', mechanismCategory: 'SPRING'},
    {reasonCode: 'SEMANTIC_UNRESOLVED', mechanismCategory: 'JAVA'}
  ]);
  assert.equal(summary.length, 2);
  assert.equal(summary[0].code, 'SEMANTIC_UNRESOLVED');
  assert.equal(summary[0].count, 2);
  assert.equal(summary[1].code, 'OPAQUE_CONDITION');
  assert.equal(summary[1].count, 1);
});
test('policyTemplates provide valid architecture-policy:v1 presets', () => {
  assert.ok(Array.isArray(model.policyTemplates));
  assert.ok(model.policyTemplates.length >= 3);
  for (const preset of model.policyTemplates) {
    assert.match(preset.yaml, /^schema: architecture-policy:v1/m);
    assert.ok(preset.id && preset.label && preset.description);
  }
});
test('parseCitations expands single, grouped and ranged inline evidence markers deterministically', () => {
  const segs = model.parseCitations('See [E1] and [E2, E3] plus [E4-E6] for details.');
  const cited = segs.filter(s => s.type === 'citations').map(s => s.ids);
  assert.deepEqual(cited, [['E1'], ['E2', 'E3'], ['E4', 'E5', 'E6']]);
});
test('classifySseToolEvent handles backend phase start/complete and legacy running status', () => {
  assert.equal(model.classifySseToolEvent({phase: 'start', tool: 'get_repo_map'}), 'start');
  assert.equal(model.classifySseToolEvent({phase: 'complete', tool: 'get_repo_map'}), 'complete');
  assert.equal(model.classifySseToolEvent({status: 'running', tool: 'get_repo_map'}), 'start');
  assert.equal(model.classifySseToolEvent({status: 'done'}), 'complete');
});
test('buildImpactQuery enforces INCOMING direction, identity arg and omits page', () => {
  const q = model.buildImpactQuery('analysis:main', 'sym:1', {scope: 'PACKAGE'});
  assert.equal(q.request.direction, 'INCOMING');
  assert.equal(q.request.scope, 'PACKAGE');
  assert.deepEqual(q.arguments, {identity: 'sym:1'});
  assert.equal('page' in q, false);
});
test('rankApplicableRecipes promotes applicable recipes first and preserves summary fallback', () => {
  const ranked = model.rankApplicableRecipes([
    {id: 'java.remove-unused-import', summary: 'Remove unused import'},
    {id: 'java.break-cycle-with-lazy-injection', summary: 'Break cycle with @Lazy'}
  ], ['java.break-cycle-with-lazy-injection']);
  assert.equal(ranked[0].id, 'java.break-cycle-with-lazy-injection');
  assert.equal(ranked[0].applicable, true);
  assert.equal(ranked[0].displaySummary, 'Break cycle with @Lazy');
  assert.equal(ranked[1].applicable, false);
});
test('findPreferredSpringContext selects spring context when available without inventing one', () => {
  const contexts = [{identity: 'snapshot:1'}, {identity: 'spring-context:main'}];
  assert.equal(model.selectDefaultContext(contexts, 'snapshot:1', ''), 'snapshot:1');
  assert.equal(model.findPreferredSpringContext(contexts, 'snapshot:1'), 'spring-context:main');
  assert.equal(model.findPreferredSpringContext([{identity: 'snapshot:1'}], 'snapshot:1'), 'snapshot:1');
});
test('shouldAutoExpandRunLog opens automatically on user Run, active jobs, and failures while keeping old completed history collapsed', () => {
  assert.equal(model.shouldAutoExpandRunLog({state: 'QUEUED'}, false), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'RUNNING'}, false), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'CANCELLING'}, false), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'FAILED'}, false), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'TIMED_OUT'}, false), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'SUCCEEDED'}, true), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'PARTIAL', reuse: {sourceJob: 'j1'}}, true), true);
  assert.equal(model.shouldAutoExpandRunLog({state: 'SUCCEEDED'}, false), false);
  assert.equal(model.shouldAutoExpandRunLog({state: 'PARTIAL'}, false), false);
  assert.equal(model.shouldAutoExpandRunLog({state: 'CANCELLED'}, false), false);
  assert.equal(model.shouldAutoExpandRunLog(null, true), true);
  assert.equal(model.shouldAutoExpandRunLog(null, false), false);
});
test('jobProgress maps submitting, queued, active stages, reuse, cancellation, and failures deterministically', () => {
  assert.deepEqual(model.jobProgress(null, true), {determinate: false, ratio: 0, tone: 'accent', statusLabel: 'Starting', done: false});
  assert.equal(model.jobProgress({state: 'QUEUED', stage: 'QUEUED'}).determinate, false);
  const analyzing = model.jobProgress({state: 'RUNNING', stage: 'ANALYSIS'});
  assert.equal(analyzing.determinate, true);
  assert.equal(analyzing.done, false);
  assert.ok(analyzing.ratio > 0.4 && analyzing.ratio < 0.8);
  const done = model.jobProgress({state: 'PARTIAL', stage: 'FINISHED'});
  assert.deepEqual(done, {determinate: true, ratio: 1, tone: 'mint', statusLabel: 'Done', done: true});
  const reused = model.jobProgress({state: 'SUCCEEDED', stage: 'REUSED', reuse: {sourceJob: 'prev'}});
  assert.equal(reused.statusLabel, 'Reused');
  assert.equal(reused.ratio, 1);
  const failed = model.jobProgress({state: 'FAILED', stage: 'PLATFORM'});
  assert.equal(failed.tone, 'danger');
  assert.equal(failed.done, true);
  const cancelled = model.jobProgress({state: 'CANCELLED', stage: 'ACQUISITION'});
  assert.equal(cancelled.tone, 'warn');
  assert.equal(cancelled.done, true);
});
test('formatContextOption produces truthful structured labels, badges, subtitles and kinds without placeholder numbering', () => {
  const snap = 'snapshot:sha256:' + 'a1b2c3d4'.repeat(8);
  assert.deepEqual(model.formatContextOption({identity: snap}, snap), {
    label: 'Whole repository',
    badge: 'REPO',
    subtitle: 'All captured files & graphs',
    kind: 'repository'
  });

  const javaMod = model.formatContextOption({
    identity: 'analysis:sha256:' + '11223344'.repeat(8),
    family: 'analysis.manifest',
    modulePath: 'backend',
    sourceSet: 'MAIN',
    language: 'java',
    kind: 'java'
  }, snap);
  assert.deepEqual(javaMod, {
    label: 'backend / main',
    badge: 'JAVA',
    subtitle: 'Java main source set',
    kind: 'java'
  });

  const rootMod = model.formatContextOption({
    identity: 'analysis:sha256:' + '55667788'.repeat(8),
    modulePath: '.',
    sourceSet: 'MAIN',
    kind: 'java'
  }, snap);
  assert.equal(rootMod.label, 'Root module / main');
  assert.equal(rootMod.badge, 'JAVA');

  const springMod = model.formatContextOption({
    identity: 'spring-context:sha256:' + '99aabbcc'.repeat(8),
    family: 'spring.context',
    modulePath: 'backend',
    sourceSet: 'MAIN',
    kind: 'spring'
  }, snap);
  assert.equal(springMod.label, 'backend / Spring');
  assert.equal(springMod.badge, 'SPRING');
  assert.equal(springMod.kind, 'spring');

  const springUnnamed = model.formatContextOption({
    identity: 'spring-context:sha256:' + '99aabbcc'.repeat(8),
    family: 'spring.context'
  }, snap);
  assert.equal(springUnnamed.label, 'Spring configuration');
  assert.equal(springUnnamed.badge, 'SPRING');

  const tsCtx = model.formatContextOption({
    identity: 'sha256:' + '12345678'.repeat(8),
    family: 'polyglot.context',
    modulePath: 'web-app',
    language: 'typescript',
    kind: 'polyglot'
  }, snap);
  assert.equal(tsCtx.label, 'web-app · TypeScript');
  assert.equal(tsCtx.badge, 'TS');
  assert.equal(tsCtx.kind, 'polyglot');

  const dockerCtx = model.formatContextOption({
    identity: 'sha256:' + 'abcdef12'.repeat(8),
    family: 'polyglot.context',
    modulePath: '.',
    language: 'docker',
    kind: 'polyglot'
  }, snap);
  assert.equal(dockerCtx.label, 'Docker');
  assert.equal(dockerCtx.badge, 'DOCKER');

  const fromProv = model.formatContextOption(
    {identity: 'analysis:second'},
    'snapshot:sample',
    {family: 'analysis.manifest', metadata: {modulePath: 'worker', sourceSet: 'MAIN'}}
  );
  assert.equal(fromProv.label, 'worker / main');
  assert.equal(fromProv.badge, 'JAVA');

  const bareJava = model.formatContextOption({identity: 'analysis:sha256:' + 'deadbeef'.repeat(8)}, snap);
  assert.doesNotMatch(bareJava.label, /Analysis context \d+/i);
  assert.equal(bareJava.label, 'Java module · deadbeef');
  assert.equal(bareJava.badge, 'JAVA');

  const bareUnknown = model.formatContextOption({identity: 'sha256:' + 'cafebabe'.repeat(8)}, snap);
  assert.doesNotMatch(bareUnknown.label, /Analysis context \d+/i);
  assert.equal(bareUnknown.label, 'Build context · cafebabe');
  assert.equal(bareUnknown.badge, 'CTX');
});

test('Architecture Health v2 helpers format single scores, uncertainty bands, withheld status, and enforce visual honesty', () => {
  assert.equal(model.formatHealthRange(null), 'Unavailable');
  assert.equal(model.formatHealthRange({status: 'WITHHELD', score: null}), 'Withheld');
  assert.equal(model.formatHealthRange({status: 'COMPLETE', score: 96.4, conservativeLowerBound: 96.4, uncertaintyDelta: 0}), '96.4 / 100');
  assert.equal(model.formatHealthRange({status: 'PARTIAL', score: 78.0, conservativeLowerBound: 62.3, uncertaintyDelta: 15.7}), '62.3–78.0 / 100');

  const high = model.confidenceBadge({tier: 'HIGH', confidenceScore: 94.2, evidenceTier: 'BYTECODE_AND_SOURCE'});
  assert.equal(high.tone, 'good');
  assert.equal(high.pctText, '94%');
  assert.equal(high.icon, '●');
  assert.equal(model.confidenceBadge({tier: 'INSUFFICIENT', confidenceScore: 31}).tone, 'danger');

  // Visual honesty invariant: LOW confidence caps high score tone from good -> warn
  assert.equal(model.v2DimensionTone({score: 91, status: 'COMPLETE'}, 'HIGH'), 'good');
  assert.equal(model.v2DimensionTone({score: 91, status: 'PARTIAL'}, 'LOW'), 'warn');
  assert.equal(model.v2DimensionTone({score: 91, status: 'PARTIAL'}, 'INSUFFICIENT'), 'muted');
  assert.equal(model.v2DimensionTone({score: null, status: 'NOT_APPLICABLE'}, 'HIGH'), 'muted');
  assert.equal(model.v2DimensionTone({score: 42, status: 'COMPLETE'}, 'HIGH'), 'danger');

  const lowerCurve = {direction: 'LOWER_IS_BETTER', ideal: 0, acceptable: 0.1, poor: 0.3, critical: 0.6};
  assert.equal(model.subMetricPosition(0, lowerCurve).zone, 'ideal');
  assert.equal(model.subMetricPosition(0.2, lowerCurve).zone, 'acceptable');
  assert.equal(model.subMetricPosition(0.45, lowerCurve).zone, 'poor');
  assert.equal(model.subMetricPosition(0.8, lowerCurve).zone, 'critical');

  const higherCurve = {direction: 'HIGHER_IS_BETTER', ideal: 0.9, acceptable: 0.7, poor: 0.4, critical: 0.1};
  assert.equal(model.subMetricPosition(0.95, higherCurve).zone, 'ideal');
  assert.equal(model.subMetricPosition(0.55, higherCurve).zone, 'acceptable');
  assert.equal(model.subMetricPosition(0.05, higherCurve).zone, 'critical');
});

test('progressive truth tiers, live throughput, incremental summaries, and compare hash links work deterministically', () => {
  assert.equal(model.isPreliminaryTier('PRELIMINARY_STRUCTURE'), true);
  assert.equal(model.isPreliminaryTier('PARTIAL_CONTEXTS'), true);
  assert.equal(model.isPreliminaryTier('DEEP_SEMANTIC_COMPLETE'), false);

  const stage1 = model.truthTierInfo('PRELIMINARY_STRUCTURE');
  assert.equal(stage1.label, 'PRELIMINARY STRUCTURE');
  assert.equal(stage1.authoritative, false);

  const stage2 = model.truthTierInfo('PARTIAL_CONTEXTS', {completedContexts: 3, totalContexts: 5});
  assert.equal(stage2.label, 'PARTIAL · 3/5 MODULES');
  assert.equal(stage2.preliminary, true);

  const stage3 = model.truthTierInfo('DEEP_SEMANTIC_COMPLETE');
  assert.equal(stage3.label, 'DEEP SEMANTIC COMPLETE');
  assert.equal(stage3.authoritative, true);

  const tp = model.formatThroughput({filesPerSecond: 42.5, etaStatus: 'AVAILABLE', etaSeconds: 80, stageDone: 120, stageTotal: 400});
  assert.equal(tp.rateText, '42.5 files/s');
  assert.equal(tp.etaText, 'ETA ~1m 20s');
  assert.equal(tp.stageCountText, '120 / 400 files');
  assert.equal(model.formatThroughput({etaStatus: 'ESTIMATING'}).etaText, 'Estimating ETA…');

  const smooth = model.jobProgress({state: 'RUNNING', stage: 'ANALYSIS'}, false, {overallRatio: 0.675});
  assert.equal(smooth.ratio, 0.675);

  const inc = model.summarizeIncremental({
    mode: 'INCREMENTAL',
    baseSnapshotId: 'snapshot:sha256:' + '1'.repeat(64),
    baseGraphId: 'sha256:' + '2'.repeat(64),
    addedFiles: 1,
    modifiedFiles: 2,
    deletedFiles: 0,
    reusedFiles: 47,
    abiEarlyCutoffCount: 2,
    impactedDependentsCount: 1,
    reusedModules: 4,
    timeSavedEstimateMs: 9500
  });
  assert.equal(inc.totalFiles, 50);
  assert.equal(inc.reusePct, 94);
  assert.equal(inc.changedFiles, 3);
  assert.equal(inc.formattedTimeSaved, '~9.5s');
  assert.equal(inc.hasSavings, true);

  const left = 'sha256:' + 'a'.repeat(64);
  const right = 'sha256:' + 'b'.repeat(64);
  const hash = model.buildCompareHash({left, right, repo: 'repo:sha256:' + 'c'.repeat(64), autorun: true});
  const parsed = model.parseToolHash(hash);
  assert.equal(parsed.baseHash, '#compare');
  assert.equal(parsed.params.left, left);
  assert.equal(parsed.params.right, right);
  assert.equal(parsed.params.repo, 'repo:sha256:' + 'c'.repeat(64));
  assert.equal(parsed.params.autorun, '1');
});

test('parseRepositoryInput detects local paths, GitHub repos, tree refs, owner/repo shorthand, and PR URLs', () => {
  assert.equal(model.parseRepositoryInput('').kind, 'EMPTY');

  const pr = model.parseRepositoryInput('https://github.com/acme/payments-api/pull/42/files');
  assert.equal(pr.kind, 'GITHUB_PR');
  assert.equal(pr.owner, 'acme');
  assert.equal(pr.repo, 'payments-api');
  assert.equal(pr.pullNumber, 42);
  assert.equal(pr.normalizedSource, 'https://github.com/acme/payments-api');
  assert.equal(pr.ref, 'pull/42/head');

  const tree = model.parseRepositoryInput('https://github.com/acme/payments-api/tree/release/v2');
  assert.equal(tree.kind, 'GITHUB_REPO');
  assert.equal(tree.ref, 'release/v2');
  assert.equal(tree.normalizedSource, 'https://github.com/acme/payments-api');

  const short = model.parseRepositoryInput('spring-projects/spring-petclinic');
  assert.equal(short.kind, 'GITHUB_REPO');
  assert.equal(short.normalizedSource, 'https://github.com/spring-projects/spring-petclinic');

  const win = model.parseRepositoryInput('D:\\UIT\\my-service');
  assert.equal(win.kind, 'LOCAL_PATH');
  assert.equal(win.normalizedSource, 'D:\\UIT\\my-service');
});

test('parseUnifiedDiffFiles parses multi-file CREATE/MODIFY/DELETE patches, side-by-side rows, and no-newline markers', () => {
  const patch = [
    'diff --git a/src/OldHelper.java b/src/OldHelper.java',
    'deleted file mode 100644',
    '--- a/src/OldHelper.java',
    '+++ /dev/null',
    '@@ -1,2 +0,0 @@',
    '-class OldHelper {}',
    '\\ No newline at end of file',
    'diff --git a/src/OrderGateway.java b/src/OrderGateway.java',
    'new file mode 100644',
    '--- /dev/null',
    '+++ b/src/OrderGateway.java',
    '@@ -0,0 +1,2 @@',
    '+public interface OrderGateway {',
    '+}',
    'diff --git a/src/Controller.java b/src/Controller.java',
    '--- a/src/Controller.java',
    '+++ b/src/Controller.java',
    '@@ -10,3 +10,3 @@ class Controller {',
    '   private final Service svc;',
    '-  private final Store store;',
    '+  private final OrderGateway gateway;',
    '   Controller() {}'
  ].join('\n');

  const summary = model.summarizePatch(patch);
  assert.equal(summary.totalFiles, 3);
  assert.equal(summary.totalAdditions, 3);
  assert.equal(summary.totalDeletions, 2);
  assert.equal(summary.files[0].changeType, 'DELETE');
  assert.equal(summary.files[0].path, 'src/OldHelper.java');
  assert.equal(summary.files[0].hunks[0].lines[1].type, 'meta');
  assert.equal(summary.files[0].hunks[0].lines[1].oldNum, null);
  assert.equal(summary.files[1].changeType, 'CREATE');
  assert.equal(summary.files[1].path, 'src/OrderGateway.java');
  assert.equal(summary.files[2].changeType, 'MODIFY');
  const changeRow = summary.files[2].rows.find(r => r.kind === 'change');
  assert.equal(changeRow.leftNum, 11);
  assert.equal(changeRow.rightNum, 11);
  assert.equal(changeRow.leftType, 'del');
  assert.equal(changeRow.rightType, 'add');
});

test('formatScoreV2Delta, repairTrustMeta, and evaluateConfirmationGate enforce strict safety guardrails', () => {
  const unavailable = model.formatScoreV2Delta(null);
  assert.equal(unavailable.available, false);
  assert.equal(unavailable.deltaText, 'Unavailable');

  const improved = model.formatScoreV2Delta({
    before: 68.4, after: 76.2, delta: 7.8, beforeBand: 'At Risk', afterBand: 'Healthy',
    dimensions: [{id: 'd1', displayName: 'Acyclic Integrity', before: 52.0, after: 84.0, delta: 32.0}]
  });
  assert.equal(improved.available, true);
  assert.equal(improved.tone, 'good');
  assert.equal(improved.deltaText, '+7.8');
  assert.equal(improved.dimensions[0].deltaText, '+32.0');

  assert.equal(model.repairTrustMeta('REJECTED', 'REJECTED_VERIFICATION').isRejected, true);
  assert.equal(model.repairTrustMeta('VERIFIED', 'VERIFIED_ISOLATED_SANDBOX').isVerified, true);
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'COMPILER_SYNTAX_ONLY').trustTone, 'warn');

  const rejectedGate = model.evaluateConfirmationGate(
    {verdict: 'REJECTED', beforeHashesValid: true},
    {mode: 'NEW_BRANCH_WORKTREE', branchName: 'evo/fix', userConfirmed: true, acknowledgeUnverified: true}
  );
  assert.equal(rejectedGate.allowed, false);
  assert.equal(rejectedGate.hardBlocked, true);

  const driftGate = model.evaluateConfirmationGate(
    {verdict: 'VERIFIED', beforeHashesValid: false},
    {mode: 'DIRECT_WORKING_TREE', userConfirmed: true}
  );
  assert.equal(driftGate.allowed, false);
  assert.equal(driftGate.hardBlocked, true);

  const unverifiedMissingAck = model.evaluateConfirmationGate(
    {verdict: 'UNVERIFIED', beforeHashesValid: true},
    {mode: 'DIRECT_WORKING_TREE', userConfirmed: true, acknowledgeUnverified: false}
  );
  assert.equal(unverifiedMissingAck.allowed, false);
  assert.equal(unverifiedMissingAck.requiresUnverifiedAck, true);

  const unverifiedAllowed = model.evaluateConfirmationGate(
    {verdict: 'UNVERIFIED', beforeHashesValid: true},
    {mode: 'DIRECT_WORKING_TREE', userConfirmed: true, acknowledgeUnverified: true}
  );
  assert.equal(unverifiedAllowed.allowed, true);

  const badBranch = model.evaluateConfirmationGate(
    {verdict: 'VERIFIED', beforeHashesValid: true},
    {mode: 'NEW_BRANCH_WORKTREE', branchName: 'bad..branch', userConfirmed: true}
  );
  assert.equal(badBranch.allowed, false);

  const prNoRemote = model.evaluateConfirmationGate(
    {verdict: 'VERIFIED', beforeHashesValid: true},
    {mode: 'GITHUB_PR', prTitle: 'Fix layering', userConfirmed: true, remoteConfirmed: false}
  );
  assert.equal(prNoRemote.allowed, false);
  const prOk = model.evaluateConfirmationGate(
    {verdict: 'VERIFIED', beforeHashesValid: true},
    {mode: 'GITHUB_PR', prTitle: 'Fix layering', userConfirmed: true, remoteConfirmed: true}
  );
  assert.equal(prOk.allowed, true);
});

test('explainMetric, gapRemediationActions, and pipelineStageSummary produce clear plain-language outputs', () => {
  assert.match(model.explainMetric('SDP').plain, /Stable packages/i);
  assert.equal(model.explainMetric('UNKNOWN_METRIC'), null);

  const actions = model.gapRemediationActions([{reason: 'MISSING_POM', count: 4}, {reason: 'OPAQUE_CONDITION', count: 2}]);
  assert.equal(actions.length, 2);
  assert.equal(actions[0].code, 'MISSING_POM');
  assert.equal(actions[0].bucket, 'environment');

  const running = model.pipelineStageSummary({state: 'RUNNING', stage: 'ANALYSIS'});
  assert.equal(running.steps[0].status, 'done');
  assert.equal(running.steps[1].status, 'done');
  assert.equal(running.steps[2].status, 'active');
  assert.equal(running.steps[3].status, 'pending');

  const done = model.pipelineStageSummary({state: 'SUCCEEDED', stage: 'FINISHED'});
  assert.equal(done.steps.every(s => s.status === 'done'), true);
});

test('multi-user auth and enterprise GitHub helpers sanitize URLs, match CODEOWNERS, build permalinks, and format gates & rate limits', () => {
  assert.equal(model.isSafeHttpUrl('https://github.com/acme/payments/pull/12'), true);
  assert.equal(model.isSafeHttpUrl('http://localhost:8080/callback'), true);
  assert.equal(model.isSafeHttpUrl('javascript:alert(1)'), false);
  assert.equal(model.isSafeHttpUrl('data:text/html,<script>alert(1)</script>'), false);
  assert.equal(model.isSafeHttpUrl('vbscript:msgbox(1)'), false);
  assert.equal(model.isSafeHttpUrl(''), false);

  const perm = model.buildGitHubPermalink({
    owner: 'acme', repo: 'payments-api',
    commitSha: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
    path: 'src/main/java/com/acme/Order Controller.java',
    startLine: 14, endLine: 22
  });
  assert.equal(
    perm,
    'https://github.com/acme/payments-api/blob/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2/src/main/java/com/acme/Order%20Controller.java#L14-L22'
  );
  assert.equal(model.buildGitHubPermalink({owner: 'acme', repo: 'payments', path: ''}), null);
  assert.equal(model.buildGitHubPermalink({owner: 'bad/owner', repo: 'x', path: 'a.java'}), null);

  const rules = [
    {pattern: '*', owners: ['@org-default']},
    {pattern: '/src/main/java/com/acme/payments/', owners: ['@payments-team', '@alice']},
    {pattern: '*.sql', owners: ['@dba-team']}
  ];
  assert.deepEqual(model.matchCodeownersForPath(rules, 'src/main/java/com/acme/payments/OrderController.java'), ['@payments-team', '@alice']);
  assert.deepEqual(model.matchCodeownersForPath(rules, 'db/migration/V1__init.sql'), ['@dba-team']);
  assert.deepEqual(model.matchCodeownersForPath(rules, 'README.md'), ['@org-default']);

  assert.equal(model.formatQualityGateBadge('PASSED').tone, 'good');
  assert.equal(model.formatQualityGateBadge('WARN').tone, 'warn');
  assert.equal(model.formatQualityGateBadge('FAILED').tone, 'bad');

  const rlGood = model.formatRateLimit({remaining: 4200, limit: 5000});
  assert.equal(rlGood.available, true);
  assert.equal(rlGood.tone, 'good');
  assert.equal(rlGood.percent, 84);
  assert.equal(model.formatRateLimit({remaining: 150, limit: 5000}).tone, 'bad');
  assert.equal(model.formatRateLimit(null).available, false);

  assert.equal(model.sanitizeAvatarMonogram('octo-dev', 'Octo Dev'), 'OD');
  assert.equal(model.sanitizeAvatarMonogram('alice', ''), 'AL');
  assert.equal(model.formatRelativeTime(Date.now() - 120000), '2m ago');
  assert.equal(model.formatRelativeTime(Date.now() - 7200000), '2h ago');
});

test('multi-user local account and in-app GitHub setup apiError codes stay plain-English and env-free', () => {
  const codes = [
    'INVALID_CREDENTIALS', 'ACCOUNT_EXISTS', 'ACCOUNT_ALREADY_EXISTS', 'ACCOUNT_DISABLED',
    'ADMIN_REQUIRED', 'LAST_ADMIN_PROTECTED', 'PASSWORD_REQUIRED_BEFORE_UNLINK',
    'WEAK_PASSWORD', 'GITHUB_SETUP_REQUIRED',
    'LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER', 'WEBHOOK_SECRET_NOT_CONFIGURED'
  ];
  for (const code of codes) {
    const msg = model.apiError(code);
    assert.ok(msg.length > 15 && !msg.includes(code), `Expected human copy for ${code}, got: ${msg}`);
    assert.equal(/\$env:|EVO_GITHUB_/.test(msg), false, `Must not show env var names to users for ${code}: ${msg}`);
  }
});

test('Moonshot #1 behavioralVerdictMeta, springPreservationMeta, and expanded repairTrustMeta labels stay truthful', () => {
  const eq = model.behavioralVerdictMeta('EQUIVALENT_ON_BOUNDED_CHECKS');
  assert.equal(eq.tone, 'good');
  assert.equal(eq.shortLabel, 'EQUIVALENT');

  const reg = model.behavioralVerdictMeta('BEHAVIOR_CHANGED', 'UNINTENDED_REGRESSION');
  assert.equal(reg.tone, 'bad');
  assert.equal(reg.shortLabel, 'BEHAVIOR_CHANGED');

  const fix = model.behavioralVerdictMeta('BEHAVIOR_CHANGED', 'INTENDED_FIX');
  assert.equal(fix.tone, 'warn');

  const unk = model.behavioralVerdictMeta('UNKNOWN_UNTESTED');
  assert.equal(unk.tone, 'warn');
  assert.equal(unk.verdict, 'UNKNOWN_UNTESTED');

  assert.equal(model.springPreservationMeta('PRESERVED').tone, 'good');
  assert.equal(model.springPreservationMeta('DRIFT_DETECTED').tone, 'bad');
  assert.equal(model.springPreservationMeta('NOT_APPLICABLE').tone, 'muted');

  assert.equal(model.repairTrustMeta('VERIFIED', 'ISOLATED_CONTAINER_VERIFIED').trustTone, 'good');
  assert.equal(model.repairTrustMeta('VERIFIED', 'ISOLATED_VM_VERIFIED').trustTone, 'good');
  assert.equal(model.repairTrustMeta('REJECTED', 'ISOLATED_CONTAINER_REJECTED').trustTone, 'bad');
  assert.equal(model.repairTrustMeta('REJECTED', 'ISOLATED_VM_REJECTED').trustTone, 'bad');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'ISOLATED_CONTAINER_UNVERIFIED').trustTone, 'warn');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'ISOLATED_VM_UNVERIFIED').trustTone, 'warn');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'PASSIVE_REANALYSIS_ONLY').trustTone, 'warn');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'SYNTAX_ONLY').trustTone, 'warn');
  // Legacy persisted REJECTED/UNVERIFIED rows with ISOLATED_*_VERIFIED must coerce to truthful bad/warn badges
  assert.equal(model.repairTrustMeta('REJECTED', 'ISOLATED_CONTAINER_VERIFIED').trustTone, 'bad');
  assert.equal(model.repairTrustMeta('REJECTED', 'ISOLATED_CONTAINER_VERIFIED').trustLabel, 'ISOLATED_CONTAINER_REJECTED');
  assert.equal(model.repairTrustMeta('REJECTED', 'ISOLATED_VM_VERIFIED').trustTone, 'bad');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'ISOLATED_CONTAINER_VERIFIED').trustTone, 'warn');
  assert.equal(model.repairTrustMeta('UNVERIFIED', 'ISOLATED_VM_VERIFIED').trustTone, 'warn');
  assert.ok(model.apiError('INTERRUPTED_ON_RESTART').toLowerCase().includes('restarted'));
  assert.ok(model.apiError('SERVER_RESTARTED_IN_FLIGHT').toLowerCase().includes('restarted'));
});
