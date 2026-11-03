const {test} = require('node:test');
const assert = require('node:assert/strict');
const model = require('../../main/resources/web/bridge-model.js');
const scope = 'sha256:' + 'a'.repeat(64);
test('page selection preserves the exact capability and continuation without synthesizing counts', () => {
  const cursor = {request: scope, position: {table: scope, rows: '20', previousKey: 'last'}};
  assert.deepEqual(model.request({scope, section: 'RELATIONS', category: 'B06', symbol: scope}, cursor), {scope, section: 'RELATIONS', size: 20, category: 'B06', symbol: scope, cursor});
  assert.throws(() => model.request({scope, section: 'ALL'})); assert.throws(() => model.request({scope, section: 'GAPS', category: 'B09'}));
});
test('uncertain outcomes are displayed without promoting a single target or hiding a failed child', () => {
  assert.equal(model.describe({key: 'x', fields: {category: 'B06', state: 'CANDIDATE', targets: [{partition: 'c'}]}}).state, 'CANDIDATE');
  assert.equal(model.describe({key: 'broken', fields: {language: 'python', state: 'FAILED'}}).state, 'FAILED');
  assert.equal(model.describe({key: 'gap', fields: {reasonCode: 'BRIDGE_REOPENED'}}).state, 'BRIDGE_REOPENED');
});
test('native source pages require an exact partition and preserve native category/path filters', () => {
  assert.throws(() => model.request({scope, section: 'FACTS'}));
  assert.deepEqual(model.request({scope, section: 'FACTS', partition: 'web', category: 'F03', document: 'app/pages/users/[id].vue'}), {scope, section: 'FACTS', size: 20, partition: 'web', category: 'F03', document: 'app/pages/users/[id].vue'});
  assert.throws(() => model.request({scope, section: 'FACTS', partition: 'web', category: 'B02'}));
  assert.equal(model.describe({key: 'x', fields: {category: 'F03', kind: 'NATIVE_ROUTE_DECLARATION', status: 'CONDITIONAL', path: 'app/page.vue'}}).state, 'CONDITIONAL');
});
test('people see source names, evidence precision and readable states instead of internal identifiers', () => {
  const component=model.describe({key:scope,fields:{category:'F02',kind:'COMPONENT_DECLARATION',path:'app/Card.vue',target:scope,status:'RESOLVED',span:{startLine:1,endLine:8},attributes:{rangePrecision:'DOCUMENT_SUPPORT'}}});
  assert.match(component.title,/Card.vue/);assert.doesNotMatch(component.title,/sha256|F02|COMPONENT_DECLARATION/);assert.match(component.location,/whole file/);assert.equal(component.statusLabel,'Resolved');
  assert.equal(model.formatCount('9007199254740993'),'9,007,199,254,740,993');
  assert.equal(model.formatCount(9007199254740993),'Exact count unavailable');
});
test('source-only contexts open their captured documents without pretending native facts exist',()=>{
  assert.equal(model.structure({fields:{documents:{rows:'1'}}}),'DOCUMENTS');assert.equal(model.structure({fields:{child:{facts:{rows:'2'}}}}),'FACTS');
  const source=model.describe({kind:'DOCUMENT',key:'src/app/Users.java',fields:{content:{digest:scope},role:'MAIN'}});assert.equal(source.source,'src/app/Users.java');assert.equal(source.title,'src/app/Users.java');assert.equal(source.state,'CAPTURED');
});
test('evidence labels use captured source and role metadata while unidentified references stay explicit',()=>{
  const row={fields:{descriptor:{parameters:scope},source:{path:'api.json',content:{digest:'source'}}}};
  assert.equal(model.evidenceLabel(scope,row,[],[],1),'Request schemas');assert.equal(model.evidenceLabel('source',row,[],[],2),'api.json');
  assert.equal(model.evidenceLabel('unknown',row,[],[],3),'Supporting reference 3');assert.equal(model.describe({fields:{source:{path:'Users.java'},descriptor:{sourcePrecision:'ORIGINAL_SOURCE_RANGE'}}}).precision,'Source range');
});
test('configuration records are distinguished by captured JSON pointer keys without disclosing values',()=>{
  const a=model.describe({fields:{kind:'CONFIG_KEY',path:'api.json',attributes:{keyPath:['paths','/users','get'],value:'private-value'}}});
  assert.equal(a.title,'Config entry · /paths/~1users/get');assert.ok(!a.title.includes('private-value'));
});
test('summary explains unmet census separately and never turns partial data into a health score',()=>{
  const stats=model.stats({partitions:'3',requirements:'17',endpoints:'21',missingCensus:'16',gaps:'27',outcomes:{BOUND:'10',UNRESOLVED:'7',NOT_APPLICABLE:'0'}});
  assert.equal(stats.find(item=>item.key==='missing').value,'16');assert.equal(stats.find(item=>item.key==='pending').value,'7');assert.ok(stats.every(item=>!item.label.includes('%')));
});

test('binding outcomes and coverage obligations keep their exact separate denominators',()=>{
  const stats=model.stats({partitions:'8',requirements:'89',endpoints:'46',missingCensus:'0',outcomes:{BOUND:'19',UNRESOLVED:'66',CANDIDATE:'2',REOPENED:'2'},accounting:{bindings:'24',evidenceObligations:'65',bindingOutcomes:{BOUND:'19',UNRESOLVED:'1',CANDIDATE:'2',REOPENED:'2'},evidenceOutcomes:{UNRESOLVED:'65'}}});
  assert.equal(stats.find(item=>item.key==='pending').value,'5');
  assert.equal(stats.find(item=>item.key==='coverage').value,'65');
  assert.equal(stats.find(item=>item.key==='matched').value,'19');
});

test('native language observations keep category, target names and source context',()=>{
  assert.deepEqual(model.request({scope,section:'FACTS',partition:'library',category:'A06'}),{scope,section:'FACTS',size:20,partition:'library',category:'A06'});
  const view=model.describe({key:scope,fields:{category:'A06',path:'library/Shared.kt',status:'RESOLVED',targets:[{name:'upper'}]}});assert.match(view.title,/upper/);assert.equal(view.source,'library/Shared.kt');assert.doesNotMatch(view.title,/sha256/);
});


test('native bridge records show operation, table or channel names without descriptor hashes',()=>{
 for(const [profile,descriptor,label] of [['B03',{service:'contract.Users',operation:'Lookup'},'contract.Users · Lookup'],['B05',{schema:'app',object:'users',access:'READS'},'app.users · Reads'],['B04',{channel:'users',bindingRole:'SUBSCRIBES'},'users · Subscribes']]){
 const view=model.describe({fields:{kind:'BRIDGE_ENDPOINT',path:'bridge.json',attributes:{profile,descriptor:{...descriptor,lineage:scope}}}});assert.equal(view.title,label);assert.ok(!view.title.includes('sha256:'));
 }
});


test('impact actions require a scoped bridge reference rather than a native target string',()=>{
 assert.equal(model.impactReference({fields:{targets:[scope]}}),null);
 const ref={identity:scope,source:{path:'api/users.proto'}};assert.equal(model.impactReference({fields:{targets:[ref]}}),ref);
});
