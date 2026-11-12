const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const model = require('../../main/resources/web/model.js');

test('overview counts lead to the matching context population', () => {
  const cards = model.summaryCards({status: 'PARTIAL', items: [{nodeCounts: {TYPE: 2, MODULE: 1, SOURCE_DOCUMENT: 2, CAPABILITY_GAP: 1}}]});
  assert.deepEqual(cards.map(({kind, count}) => [kind, count]), [['TYPE', 2], ['MODULE', 1], ['SOURCE_DOCUMENT', 2], ['CAPABILITY_GAP', 1]]);
  assert.deepEqual(cards.map(({tab}) => tab), ['structure', 'structure', 'structure', 'gaps']);
  assert.equal(cards.every(card => card.observed), true);
});

test('penalty evidence uses the exact structural operand or matching canonical findings', () => {
  const density = {metric: {id: 'metric.density'}, ratio: {numerator: 1, denominator: 2}};
  const assessment = {structuralInputs: [density], model: {dimensions: [{id: 'health.coupling', metric: {metric: density.metric}}]}};
  const findings = [{identity: 'expected', bundleIdentity: 'bundle'}, {identity: 'different'}];
  assert.deepEqual(model.scoreEvidence(assessment, {id: 'health.coupling'}, findings), {metric: density, findings: []});
  const boundary = {id: 'health.boundaries', contributions: [{inputs: ['expected', 'bundle', 'rule']}]};
  assert.deepEqual(model.scoreEvidence(assessment, boundary, findings), {metric: undefined, findings: [findings[0]]});
});

if (process.argv[2]) test('workbench model consumes the same loopback HTTP payloads as CLI and export', () => {
  const {summary, assessment, findings, bundle, bundleId, replay, metric, gaps, withheld} = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  assert.deepEqual(model.summaryCards(summary).map(card => card.count), [2, 1, 2, 0]);
  assert.equal(model.assessment(assessment), '67.5 / 100');
  assert.equal(model.assessment(withheld), 'Unavailable');
  assert.equal(assessment.confidence.sufficient, true);
  assert.equal(withheld.confidence.sufficient, false);
  const boundary = assessment.dimensions.find(dimension => dimension.id === 'health.boundaries');
  assert.equal(boundary.appliedPenalty, 25);
  const [finding] = model.scoreEvidence(assessment, boundary, findings.items).findings;
  assert.equal(finding.bundleIdentity, bundleId);
  assert.equal(finding.result.classification, 'MUST');
  assert.equal(bundle.region.witness.formulaReplayed, true);
  assert.equal(replay.items[0].semanticReplayed, false);
  const [source] = bundle.sources;
  assert.deepEqual([source.span.startLine, source.span.startColumn, source.span.endLine, source.span.endColumn], [1, 28, 1, 29]);
  const coupling = assessment.dimensions.find(dimension => dimension.id === 'health.coupling');
  assert.deepEqual(model.scoreEvidence(assessment, coupling, findings.items).metric.ratio, metric.items[0].ratio);
  assert.match(model.pageInfo(gaps), /1 shown.*Incomplete evidence/);
});
