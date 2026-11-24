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
