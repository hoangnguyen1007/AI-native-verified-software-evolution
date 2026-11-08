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
test('incomplete populations never claim a full count or clean result', () => {
  assert.equal(model.pageInfo({items: [1], status: 'PARTIAL', bounds: {truncated: true}, continuation: 'next'}), '1 shown · Incomplete evidence · More available');
  assert.equal(model.pageInfo({items: [], exactTotal: 0, status: 'COMPLETE'}), '0 of 0 · Available results');
});
test('published finding pages retain their withheld evaluation and observed denominator', () => {
  assert.equal(model.pageInfo({items: [1], returnedTotal: 7, evaluationStatus: 'WITHHELD', continuation: 'next'}), '1 of 7 observed · Incomplete evidence · More available');
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
