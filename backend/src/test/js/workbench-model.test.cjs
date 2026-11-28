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

test('Moonshot #3 EvoBus isolates listener failures, supports unsubscribe, and mirrors canonical state', () => {
  const EvoBus = require('../../main/resources/web/evo-bus.js');
  const bus = EvoBus.createBus();
  assert.equal(bus.isCanonicalEvent('evo:canvas-focus'), true);
  assert.equal(bus.isCanonicalEvent('evo:chat-seed'), true);
  assert.equal(bus.isCanonicalEvent('evo:source-open'), true);
  assert.equal(bus.isCanonicalEvent('evo:repair-seed'), true);
  assert.equal(bus.isCanonicalEvent('evo:snapshot-changed'), true);
  assert.equal(bus.isCanonicalEvent('evo:auth-changed'), true);

  const received = [];
  const unsub1 = bus.on('evo:canvas-focus', () => { throw new Error('boom'); });
  const unsub2 = bus.on('evo:canvas-focus', p => received.push(p.nodeIds));
  const origErr = console.error;
  console.error = () => {};
  let res;
  try {
    res = bus.emit('evo:canvas-focus', { nodeIds: ['n1', 'n2'] });
  } finally {
    console.error = origErr;
  }
  assert.equal(res.delivered, 1);
  assert.equal(res.errors.length, 1);
  assert.deepEqual(received, [['n1', 'n2']]);

  unsub1();
  unsub2();
  assert.equal(bus.listenerCount('evo:canvas-focus'), 0);

  bus.emit('evo:snapshot-changed', { graphId: 'g1', snapshotId: 's1', contextId: 'c1', truthTier: 'DEEP_SEMANTIC_COMPLETE', contexts: [{ identity: 'c1' }] });
  assert.equal(bus.getState().graphId, 'g1');
  assert.equal(bus.getState().contextId, 'c1');
  assert.equal(bus.lastEvent('evo:snapshot-changed').snapshotId, 's1');
  bus.setState({ activeTab: 'dsm' });
  assert.equal(bus.getState().activeTab, 'dsm');
});

test('Moonshot #3 EvoTrust enforces 4 canonical TrustTiers, promotion guard, and DOM pill rendering without innerHTML', () => {
  const EvoTrust = require('../../main/resources/web/trust-badges.js');
  assert.deepEqual([...EvoTrust.TIERS], ['COMPILER', 'SPRING_SAT', 'SOURCE_SYNTAX', 'ADVISORY_UNVERIFIED']);
  assert.equal(EvoTrust.normalize('COMPILER'), 'COMPILER');
  assert.equal(EvoTrust.normalize('spring-sat'), 'SPRING_SAT');
  assert.equal(EvoTrust.normalize('advisory'), 'ADVISORY_UNVERIFIED');
  assert.equal(EvoTrust.normalize('unknown_random_tier'), 'SOURCE_SYNTAX');
  assert.equal(EvoTrust.meta('ADVISORY_UNVERIFIED').quarantined, true);
  assert.equal(EvoTrust.meta('COMPILER').authoritative, true);
  assert.equal(EvoTrust.inferFromRecord({ kind: 'SQL_TABLE' }), 'ADVISORY_UNVERIFIED');
  assert.equal(EvoTrust.inferFromRecord({ kind: 'SPRING_BEAN' }), 'SPRING_SAT');
  assert.equal(EvoTrust.inferFromRecord({ kind: 'TYPE', boundCallTargets: false }), 'SOURCE_SYNTAX');

  const makeEl = tag => ({
    tagName: tag.toUpperCase(),
    className: '',
    textContent: '',
    dataset: {},
    attrs: {},
    children: [],
    setAttribute(k, v) { this.attrs[k] = String(v); },
    append(...nodes) { this.children.push(...nodes); }
  });
  const fakeDoc = { createElement: makeEl };
  const pill = EvoTrust.renderBadge('SPRING_SAT', { classification: 'MAY', doc: fakeDoc });
  assert.equal(pill.dataset.trustTier, 'SPRING_SAT');
  assert.match(pill.className, /is-spring-sat/);
  assert.match(pill.attrs['aria-label'], /Spring SAT \(MAY\)/);
  assert.equal(pill.children.some(c => c.textContent === 'MAY'), true);
});

test('Moonshot #3 WebGL layout/grid, GraphCanvas auto engine selection, and DSM summary stay deterministic', () => {
  const WebGL = require('../../main/resources/web/webgl-graph-engine.js');
  const GraphCanvas = require('../../main/resources/web/graph-canvas.js');
  const DsmMatrix = require('../../main/resources/web/dsm-matrix.js');

  const sampleNodes = Array.from({ length: 120 }, (_, i) => ({
    id: 'n' + i,
    communityId: 'c' + (i % 4)
  }));
  const sampleEdges = Array.from({ length: 180 }, (_, i) => ({
    source: 'n' + (i % 120),
    target: 'n' + ((i * 7 + 1) % 120)
  }));
  const posA = WebGL.computeDeterministicLayout(sampleNodes, sampleEdges);
  const posB = WebGL.computeDeterministicLayout(sampleNodes, sampleEdges);
  assert.deepEqual([...posA.entries()], [...posB.entries()]);

  const items = sampleNodes.map(n => ({ id: n.id, ...posA.get(n.id), radius: 8 }));
  const grid = WebGL.buildSpatialGrid(items);
  const hit = WebGL.hitTestGrid(items, grid, items[17].x + 1, items[17].y - 1, 1);
  assert.equal(hit?.id, 'n17');

  const normCy = GraphCanvas.normalizeVisualGraph({
    elements: {
      nodes: [{ data: { id: 'community:0', kind: 'COMMUNITY_SUPER_NODE', label: 'Core' }, position: { x: 120, y: -45 } }],
      edges: [{ data: { id: 'e1', source: 'community:0', target: 'community:0', isCycle: true } }]
    },
    metadata: { resolvedLevel: 'MACRO', cycleMode: 'SCC' }
  });
  assert.equal(normCy.nodes.length, 1);
  assert.equal(normCy.nodes[0].x, 120);
  assert.equal(normCy.nodes[0].y, -45);
  assert.equal(normCy.edges[0].inCycle, true);
  assert.equal(GraphCanvas.chooseEngine('auto', normCy, 12, true, true), 'webgl2');
  assert.equal(GraphCanvas.chooseEngine('auto', { nodes: [{ id: 'a', kind: 'TYPE' }], metadata: { resolvedLevel: 'MICRO' } }, 40, true, true), 'cytoscape');
  assert.equal(GraphCanvas.chooseEngine('auto', { nodes: [], metadata: { resolvedLevel: 'MICRO' } }, 400, true, true), 'webgl2');
  assert.equal(GraphCanvas.chooseEngine('cytoscape', normCy, 400, true, true), 'cytoscape');
  // P2-5: community:external / expandable:false must NOT be treated as expandable
  assert.equal(GraphCanvas.isExpandableSuperNodeItem({ id: 'community:0', kind: 'COMMUNITY_SUPER_NODE', properties: { expandable: true } }), true);
  assert.equal(GraphCanvas.isExpandableSuperNodeItem({ id: 'community:external', kind: 'COMMUNITY_SUPER_NODE', properties: { expandable: false, externalGroup: true } }), false);
  // P1-3: concrete scope derivation aligns multi-scale TYPE/SPRING_COMPONENT nodes for impact & shortest-path
  assert.equal(GraphCanvas.resolveEffectiveScopeForNode({ id: 't1', kind: 'TYPE' }, 'PACKAGE', false, true), 'TYPE');
  assert.equal(GraphCanvas.resolveEffectiveScopeForNode({ id: 'b1', kind: 'BEAN_DEFINITION_CANDIDATE' }, 'PACKAGE', true, true), 'SPRING_COMPONENT');
  assert.equal(GraphCanvas.resolveEffectiveScopeForNode({ id: 'x1' }, 'PACKAGE', false, true), 'TYPE');
  assert.equal(GraphCanvas.resolveEffectiveScopeForNode({ id: 'p1', kind: 'PACKAGE' }, 'PACKAGE', false, false), 'PACKAGE');

  const dsmSum = DsmMatrix.summarizeDsm({
    granularity: 'PACKAGE',
    levelizationScore: 0.875,
    totalItems: 120,
    truncated: true,
    order: [
      { index: 0, id: 'p1', label: 'com.acme.a' },
      { index: 1, id: 'p2', label: 'com.acme.b' }
    ],
    sccBlocks: [{ index: 0, start: 0, end: 1, size: 2, cyclic: true, members: ['p1', 'p2'] }],
    cells: [
      { r: 1, c: 0, weight: 4, isCycle: false, isViolation: false },
      { r: 0, c: 1, weight: 2, isCycle: true, isViolation: true }
    ]
  });
  assert.equal(dsmSum.levelizationText, '87.5%');
  assert.equal(dsmSum.tone, 'warn');
  assert.equal(dsmSum.cyclicBlockCount, 1);
  assert.equal(dsmSum.backEdgeCount, 1);
  assert.equal(dsmSum.violationCount, 1);
  assert.equal(dsmSum.truncated, true);
});

test('Moonshot #4 EvoVerticalTrace groups 8 canonical lanes, normalizes canonical vs advisory counts, and builds slice highlight metadata', () => {
  const VTrace = require('../../main/resources/web/vertical-trace.js');

  assert.deepEqual(
    VTrace.CANONICAL_LAYERS.map(l => l.id),
    ['FRONTEND', 'API_ROUTE', 'SPRING_CONTROLLER', 'SPRING_DI', 'SERVICE', 'JPA_ENTITY', 'SQL_TABLE', 'TERRAFORM_RESOURCE']
  );

  const trace = VTrace.normalizeTrace({
    graphDigest: 'sha256:abc123',
    context: 'analysis:main',
    seed: 'com.acme.OrderController',
    truncated: false,
    hops: [
      { index: 3, layer: 'SERVICE', nodeId: 'svc:1', label: 'OrderService', kind: 'TYPE', trustTier: 'COMPILER', sourcePath: 'src/main/java/com/acme/OrderService.java', startLine: 24, endLine: 68 },
      { index: 0, layer: 'FRONTEND', nodeId: 'fe:1', label: 'CheckoutForm.tsx', kind: 'FILE', trustTier: 'SOURCE_SYNTAX', sourcePath: 'web/CheckoutForm.tsx', startLine: 12 },
      { index: 1, layer: 'SPRING_CONTROLLER', nodeId: 'ctrl:1', label: 'OrderController', kind: 'TYPE', trustTier: 'COMPILER', sourcePath: 'src/main/java/com/acme/OrderController.java', startLine: 18 },
      { index: 2, layer: 'SPRING_DI', nodeId: 'bean:1', label: 'orderServiceBean', kind: 'SPRING_BEAN', trustTier: 'SPRING_SAT', classification: 'MAY', witnessId: 'wit-prod-1' },
      { index: 4, layer: 'SQL_TABLE', nodeId: 'sql:orders', label: 'orders', kind: 'SQL_TABLE', trustTier: 'COMPILER', sourcePath: 'db/V1__init.sql', startLine: 5, gapReason: 'Heuristic table link' },
      { index: 5, layer: 'TERRAFORM_RESOURCE', nodeId: 'tf:rds', label: 'aws_db_instance.orders', kind: 'TERRAFORM_RESOURCE', sourcePath: 'infra/rds.tf', startLine: 1 }
    ],
    gaps: [
      { code: 'MISSING_API_BRIDGE', layer: 'API_ROUTE', reason: 'No OpenAPI bridge captured' },
      {
        gapId: 'gap:1',
        mechanismCategory: 'SPRING',
        reasonCode: 'OPAQUE_CONDITION',
        affectedNodeIds: ['bean:1', 'svc:1'],
        sourcePaths: ['src/main/java/com/acme/OrderConfig.java'],
        limitations: ['Custom @Conditional predicate could not be evaluated statically']
      }
    ]
  });

  // Promotion guard: SQL_TABLE & TERRAFORM_RESOURCE always remain ADVISORY_UNVERIFIED even if raw trustTier claims COMPILER
  assert.equal(trace.totalHops, 6);
  assert.equal(trace.canonicalHopCount, 4);
  assert.equal(trace.advisoryHopCount, 2);
  assert.equal(trace.hops[0].layer, 'FRONTEND');
  assert.equal(trace.hops[2].classification, 'MAY');
  assert.equal(trace.hops[2].witnessId, 'wit-prod-1');
  assert.equal(trace.hops[4].trustTier, 'ADVISORY_UNVERIFIED');
  assert.equal(trace.hops[4].isAdvisory, true);
  assert.equal(trace.hops[5].trustTier, 'ADVISORY_UNVERIFIED');
  assert.equal(trace.allGaps.length, 3);
  assert.equal(trace.gaps[1].code, 'OPAQUE_CONDITION');
  assert.equal(trace.gaps[1].mechanismCategory, 'SPRING');
  assert.deepEqual(trace.gaps[1].affectedNodeIds, ['bean:1', 'svc:1']);
  assert.deepEqual(trace.gaps[1].sourcePaths, ['src/main/java/com/acme/OrderConfig.java']);
  assert.match(trace.gaps[1].reason, /SPRING/);
  assert.match(trace.gaps[1].reason, /Custom @Conditional predicate/);
  assert.match(trace.gaps[1].reason, /OrderConfig\.java/);
  assert.match(trace.gaps[1].reason, /2 affected nodes/);

  const lanes = VTrace.groupHopsByLane(trace);
  assert.equal(lanes.length, 8);
  assert.equal(lanes[0].id, 'FRONTEND');
  assert.equal(lanes[0].count, 1);
  assert.equal(lanes[1].id, 'API_ROUTE');
  assert.equal(lanes[1].count, 0);
  assert.equal(lanes[1].hasGap, true);
  assert.equal(lanes[6].id, 'SQL_TABLE');
  assert.equal(lanes[6].advisoryCount, 1);
  assert.equal(lanes[6].hasGap, true);
  assert.equal(lanes[7].id, 'TERRAFORM_RESOURCE');
  assert.equal(lanes[7].advisoryCount, 1);

  const slice = VTrace.normalizeSlice({
    path: 'src/main/java/com/acme/OrderService.java',
    methodSignature: 'createOrder(OrderRequest)',
    seedLine: 42,
    direction: 'BACKWARD',
    trustTier: 'COMPILER',
    truncated: false,
    totalMethodStatements: 14,
    statements: [
      { line: 42, endLine: 42, kind: 'RETURN', code: 'return repo.save(entity);', dependsOnLines: [38, 30], dependencyKind: 'SEED' },
      { line: 30, endLine: 31, kind: 'IF', code: 'if (req.total() <= 0) throw new IllegalArgumentException();', dependsOnLines: [], dependencyKind: 'CONTROL' },
      { line: 38, endLine: 38, kind: 'ASSIGN', code: 'OrderEntity entity = mapper.toEntity(req);', dependsOnLines: [30], dependencyKind: 'DATA' },
      { line: 40, endLine: 40, kind: 'CALL', code: 'entity.setStatus(Status.NEW);', dependsOnLines: [38, 30], dependencyKind: 'BOTH' }
    ],
    sliceLines: [42, 30, 38, 40],
    limitations: ['Intra-procedural only']
  });

  assert.equal(slice.direction, 'BACKWARD');
  assert.equal(slice.trustTier, 'COMPILER');
  assert.deepEqual(slice.sliceLines, [30, 38, 40, 42]);
  assert.equal(slice.statements[0].line, 30);
  assert.equal(slice.statements[3].isSeed, true);
  assert.deepEqual(slice.statements[3].dependsOnLines, [30, 38]);
  assert.equal(slice.highlightMeta.seedLine, 42);
  assert.equal(slice.highlightMeta.minLine, 30);
  assert.equal(slice.highlightMeta.maxLine, 42);
  assert.equal(slice.highlightMeta.lineCount, 4);
  assert.deepEqual(slice.highlightMeta.byKind, { SEED: 1, DATA: 1, CONTROL: 1, BOTH: 1 });
  assert.equal(slice.highlightMeta.lineMeta[30].dependencyKind, 'CONTROL');
  assert.equal(slice.highlightMeta.lineMeta[40].dependencyKind, 'BOTH');
  assert.deepEqual(slice.limitations, ['Intra-procedural only']);
});

test('Moonshot #5 EvoMcpScipHub normalizes MCP setup, builds client tabs, encodes SCIP Base64, and summarizes SCIP upgrades', () => {
  const Hub = require('../../main/resources/web/mcp-scip-hub.js');

  // 1. Setup normalization with both new & fallback shapes
  const setup = Hub.normalizeMcpSetup({
    evoJarPath: 'C:/evo/evo.jar',
    storeDir: 'C:/evo/.evo',
    defaultGraph: 'sha256:abc123',
    streamableHttpUrl: 'http://127.0.0.1:8080/mcp',
    protocolVersion: '2025-11-25',
    toolCount: 23,
    resourceCount: 7,
    patRequired: true,
    resources: [{ uri: 'evo://snapshot/current', description: 'Current snapshot' }]
  });
  assert.equal(setup.streamableHttpUrl, 'http://127.0.0.1:8080/mcp');
  assert.equal(setup.toolCount, 23);
  assert.equal(setup.resourceCount, 7);
  assert.equal(setup.patRequired, true);

  const legacySetup = Hub.normalizeMcpSetup({
    workbenchPort: 9090,
    streamableHttp: { url: 'http://127.0.0.1:9090/mcp' },
    offlineStore: { mcpServers: { 'evo-offline': { command: 'java', args: ['-jar', 'evo.jar'] } } }
  });
  assert.equal(legacySetup.streamableHttpUrl, 'http://127.0.0.1:9090/mcp');
  assert.ok(legacySetup.clients.offlineStore);

  // 2. Client config tabs (6 tabs: Claude Code, Cursor, Antigravity, Streamable HTTP, VS Code, CLI Offline)
  const tabs = Hub.buildMcpClientTabs(setup);
  assert.deepEqual(tabs.map(t => t.id), ['claude', 'cursor', 'antigravity', 'streamable-http', 'vscode', 'stdio']);
  assert.match(tabs[0].snippet, /http:\/\/127\.0\.0\.1:8080\/mcp/);
  assert.match(tabs[0].snippet, /Bearer/);
  assert.match(tabs[3].snippet, /"streamable-http"/);
  assert.match(tabs[4].snippet, /"servers"/);
  assert.match(tabs[5].snippet, /evo\.jar/);

  // 3. Base64 SCIP helper & upload validation
  const bytes = new Uint8Array([0x0a, 0x10, 0x53, 0x43, 0x49, 0x50]);
  const b64 = Hub.arrayBufferToBase64(bytes);
  assert.equal(Buffer.from(b64, 'base64').equals(Buffer.from(bytes)), true);
  assert.equal(Hub.validateScipUpload({ name: 'index.scip', size: 0 }).ok, false);
  assert.equal(Hub.validateScipUpload({ name: 'index.scip', size: 16 * 1024 * 1024 + 1 }).ok, false);
  assert.equal(Hub.validateScipUpload({ name: 'index.scip', size: 16 * 1024 * 1024 }).ok, true);
  assert.equal(Hub.validateScipUpload({ name: 'index.scip', size: 128 }, 'bad-sha').ok, false);
  const valid = Hub.validateScipUpload({ name: 'index.scip', size: 128 }, 'sha256:' + 'a'.repeat(64));
  assert.equal(valid.ok, true);
  assert.equal(valid.expectedSha256, 'a'.repeat(64));

  // 4. Upgrade summary formatting & MCP tool grouping
  const summary = Hub.summarizeScipIngest({
    artifactSha256: 'sha256:' + 'b'.repeat(64),
    toolName: 'scip-typescript',
    toolVersion: '0.3.22',
    documentCount: 42,
    symbolCount: 310,
    upgradedToCompilerCount: 198,
    occurrenceCount: 1420,
    ambiguousDefinitionCount: 2,
    coverageGaps: [{ reasonCode: 'DYNAMIC_IMPORT', symbolOrPath: 'src/lazy.ts', detail: 'Dynamic module path' }],
    samples: [{
      symbol: 'scip-typescript npm pkg 1.0 OrderService#',
      displayName: 'OrderService',
      language: 'typescript',
      relativePath: 'src/order-service.ts',
      startLine: 14,
      endLine: 48,
      previousTier: 'SOURCE_SYNTAX',
      trustTier: 'COMPILER',
      referenceCount: 9
    }],
    trustTier: 'COMPILER'
  });
  assert.equal(summary.trustTier, 'COMPILER');
  assert.equal(summary.upgradedToCompilerCount, 198);
  assert.equal(summary.gapCount, 1);
  assert.equal(summary.samples[0].relativePath, 'src/order-service.ts');

  const grouped = Hub.groupMcpTools([
    { name: 'get_graph_summary', description: 'Graph summary' },
    { name: 'list_policy_violations', description: 'Policy findings' },
    { name: 'vertical_trace', description: 'Trace hops' }
  ]);
  assert.equal(grouped.length, 3);
});
