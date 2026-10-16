const {test} = require('node:test');
const assert = require('node:assert/strict');
const model = require('../../main/resources/web/model.js');
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
