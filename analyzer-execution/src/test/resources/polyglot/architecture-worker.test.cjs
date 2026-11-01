'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const worker=require('../../../main/resources/polyglot/architecture/worker.cjs');
// One fixed SDK per test process; each analysis still creates its own semantic state.
// Production CLI verification remains unchanged and verifies bytes before loading tools.
let fixtureTools;
const analyze=async input=>worker.analyze(input,{tools:await(fixtureTools??=require('../../../main/resources/polyglot/architecture/tools.cjs').load('.evo/tools/m845-architecture'))});
const hash=s=>'sha256:'+crypto.createHash('sha256').update(s).digest('hex');
function request(files,profiles=[]) {
  return {schema:'architecture-request:m8.4-v1',scope:hash('authored context'),repository:'urn:fixture:architecture',app:'web',
    profiles,files:Object.entries(files).map(([path,text])=>({path,text,sha256:hash(text),role:'main'})),
    context:{sourceSet:'main',variant:'baseline',conditions:{},resolver:'captured-vfs-v1'}};
}
test('route source retains lazy component and loader contract evidence without asserting deployed service binding',async()=>{
  const route='app/routes/users.tsx',req=request({[route]:'import {request} from "../api"; export const page=()=>import("../components/Page"); export function loader(){return request("/users")}',
    'app/api.ts':'export function request(url:string){return fetch(url)}','app/components/Page.tsx':'export function Page(){return <main>User</main>}',
    'backend/api.json':'{"openapi":"3.1.0","paths":{"/users":{"get":{"responses":{"200":{"description":"ok"}}}}}}'},[{id:'remix',version:'7'},{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));const result=await analyze(req);
  assert.ok(result.facts.some(f=>f.path===route&&f.kind==='DECLARED_ROUTE'&&f.status==='CONDITIONAL'));
  assert.ok(result.facts.some(f=>f.path===route&&f.kind==='LAZY_MODULE'&&f.target==='app/components/Page.tsx'));
  assert.ok(result.facts.some(f=>f.path==='app/components/Page.tsx'&&f.kind==='COMPONENT_DECLARATION'));
  const loader=result.facts.find(f=>f.path===route&&f.kind==='CALLABLE_OWNER'&&f.attributes.name==='loader');assert.ok(loader);
  assert.ok(result.facts.some(f=>f.path===route&&f.kind==='NETWORK_REQUEST'&&f.attributes.owner===loader.target&&f.attributes.endpointDigest===hash('/users')&&f.status==='CONDITIONAL'));
  assert.ok(result.facts.some(f=>f.path==='backend/api.json'&&f.kind==='API_OPERATION'&&f.attributes.route==='/users'&&f.attributes.method==='GET'));
  assert.ok(!result.facts.some(f=>f.kind==='DEPLOYED_SERVICE_BINDING'));
});

test('literal template fragments bind exact declarations while cycles and dynamic selectors retain obligations',async()=>{
  const req=request({'templates/page.html':'<main th:fragment="shell"><aside th:replace="~{parts :: card}"></aside></main>',
    'templates/parts.html':'<section data-th-fragment="card"><div th:insert="~{page :: shell}"></div></section>',
    'templates/dynamic.html':'<div th:replace="~{${template} :: card}"></div><div th:insert="~{missing :: none}"></div>',
    'healthy.json':'{"ready":true}'});
  req.context.templateProfiles=[{id:'thymeleaf-html-literals',version:'3.1',roots:['templates']}];
  const result=await analyze(req),declaration=result.facts.find(f=>f.kind==='TEMPLATE_FRAGMENT_DECLARATION'&&f.path==='templates/parts.html');
  assert.ok(declaration);assert.equal(req.files.find(f=>f.path===declaration.path).text.slice(declaration.span.start,declaration.span.end),'card');
  const binding=result.facts.find(f=>f.kind==='TEMPLATE_FRAGMENT_REFERENCE'&&f.path==='templates/page.html');assert.ok(binding);assert.equal(binding.target,declaration.target);assert.ok(binding.supports.some(p=>p.path==='templates/parts.html'));
  assert.ok(result.obligations.some(g=>g.reason==='TEMPLATE_INCLUDE_CYCLE'));assert.ok(result.obligations.some(g=>g.reason==='TEMPLATE_DYNAMIC_FRAGMENT_REQUIRED'));assert.ok(result.obligations.some(g=>g.reason==='TEMPLATE_SOURCE_UNAVAILABLE'));
  assert.ok(result.facts.some(f=>f.kind==='CONFIG_KEY'&&f.path==='healthy.json'));assert.ok(!result.facts.some(f=>f.kind==='TEMPLATE_FRAGMENT_REFERENCE'&&f.path==='templates/dynamic.html'&&f.status==='RESOLVED'));
  const ambiguous=structuredClone(req);ambiguous.files.push({path:'templates/duplicate.html',text:'<div th:fragment="card"></div><p th:fragment="card"></p>',sha256:hash('<div th:fragment="card"></div><p th:fragment="card"></p>'),role:'main'});
  ambiguous.files.push({path:'templates/use.html',text:'<div th:insert="~{duplicate :: card}"></div>',sha256:hash('<div th:insert="~{duplicate :: card}"></div>'),role:'main'});
  const recovered=await analyze(ambiguous);assert.ok(recovered.obligations.some(g=>g.reason==='TEMPLATE_FRAGMENT_AMBIGUOUS'));assert.ok(!recovered.facts.some(f=>f.kind==='TEMPLATE_FRAGMENT_REFERENCE'&&f.path==='templates/use.html'&&f.status==='RESOLVED'));
});

test('invalid checker context cannot discard independent healthy schema and resource evidence',async()=>{
  for(const [reason,context,profiles] of [['COMPILER_OPTIONS_INVALID',{compilerOptions:{target:'NOT_A_TARGET'}},[]],['EXECUTION_OR_EMIT_OPTION_FORBIDDEN',{compilerOptions:{plugins:[{name:'never-load-target-plugin'}]}},[]],['ANGULAR_COMPILER_OPTION_UNREGISTERED',{angularCompilerOptions:{unknownCompilerOption:true}},[{id:'angular',version:'20.3'}]]]){
    const req=request({'bad.ts':'export const n=1;','healthy.json':'{"ready":true}','index.html':'<img src="./image.svg">','image.svg':'<svg/>'},profiles);Object.assign(req.context,context);
    const result=await analyze(req);assert.equal(result.documents.length,4);assert.equal(result.census.length,48);
    assert.ok(result.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));assert.ok(result.facts.some(f=>f.path==='index.html'&&f.target==='image.svg'));
    assert.ok(result.obligations.some(g=>g.path==='bad.ts'&&g.reason===reason));assert.ok(!result.facts.some(f=>f.kind==='CALLABLE_OWNER'&&f.path==='bad.ts'));
  }
});

test('mutually exclusive browser and server conditions keep native package bindings in separate captured worlds',async()=>{
  // These are original authored package sources, not substitute framework/library stubs.
  const req=request({'entry.ts':'import {load} from "surface"; export const value=load();','node_modules/surface/package.json':'{"name":"surface","version":"1.0.0","exports":{".":{"browser":"./browser.ts","node":"./server.ts"}}}',
    'node_modules/surface/browser.ts':'export function load(){return "browser";}','node_modules/surface/server.ts':'export function load(){return "server";}'});
  req.files.filter(f=>f.path.startsWith('node_modules/')).forEach(f=>f.role='dependency');
  const results=[];for(const world of ['browser','node']){const selected=structuredClone(req);selected.scope=hash('authored-world:'+world);selected.context.conditions={execution:world};selected.context.compilerOptions={target:'ES2022',module:'ESNext',moduleResolution:'Bundler',customConditions:[world],strict:true};results.push(await analyze(selected));}
  for(let index=0;index<results.length;index++){
    const result=results[index],world=index?'node':'browser',target='node_modules/surface/'+(index?'server':'browser')+'.ts';
    assert.ok(result.facts.some(f=>f.path==='entry.ts'&&f.kind==='MODULE_REFERENCE'&&f.target===target));assert.ok(!result.facts.some(f=>f.path==='entry.ts'&&f.kind==='MODULE_REFERENCE'&&f.target!==target));
    assert.ok(result.facts.every(f=>f.conditions.execution===world));
  }
  assert.notEqual(results[0].inputClosure,results[1].inputClosure);assert.notEqual(results[0].identity,results[1].identity);
});

test('native descriptor metadata preserves binary evidence and independent schema declarations',async()=>{
  const tools=await(fixtureTools??=require('../../../main/resources/polyglot/architecture/tools.cjs').load('.evo/tools/m845-architecture'));
  assert.ok(tools.protobufDescriptor);
  const bytes=Buffer.from(tools.protobufDescriptor.FileDescriptorSet.encode({file:[{name:'api.proto',package:'example',syntax:'proto3',messageType:[{name:'Request',field:[{name:'id',number:1,label:1,type:9}]}],service:[{name:'Api',method:[{name:'Read',inputType:'.example.Request',outputType:'.example.Request'}]}]}]}).finish());
  const req=request({'healthy.json':'{"ready":true}'});req.files.push({path:'api.desc',data:bytes.toString('base64'),sha256:hash(bytes),role:'generated'});req.context.protobufDescriptorSets=['api.desc'];
  const result=await analyze(req),method=result.facts.find(f=>f.kind==='PROTO_DESCRIPTOR_METHOD');assert.ok(method);assert.equal(method.span,null);assert.equal(method.attributes.requestType,'.example.Request');
  assert.ok(result.facts.some(f=>f.kind==='PROTO_DESCRIPTOR_FIELD'&&f.attributes.name==='id'&&f.attributes.type==='string'));assert.ok(!result.obligations.some(g=>g.path==='api.desc'&&g.reason==='BINARY_METADATA_PROVIDER_REQUIRED'));
  const bad=request({'healthy.json':'{"ready":true}'});bad.files.push({path:'bad.desc',data:Buffer.from([255]).toString('base64'),sha256:hash(Buffer.from([255])),role:'generated'});bad.context.protobufDescriptorSets=['bad.desc'];
  const recovered=await analyze(bad);assert.ok(recovered.obligations.some(g=>g.reason==='PROTO_DESCRIPTOR_DECODE_FAILED'));assert.ok(recovered.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));
});
test('native DDL and ORM metadata retain declared mappings and migration conflicts without a database',async()=>{
  const req=request({'db/V001__users.sql':'CREATE TABLE billing.users (id BIGINT PRIMARY KEY, email VARCHAR(90) NOT NULL);','db/V2__read.sql':'SELECT id FROM billing.users;',
    'orm.xml':'<entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" version="3.1"><package>example</package><entity class="User"><table name="users" schema="billing"/><attributes><id name="id"><column name="id"/></id><basic name="email"><column name="email"/></basic></attributes></entity></entity-mappings>'});
  req.context.sqlDialect='Postgresql';req.context.sqlMigrationProfiles=[{id:'flyway-default-sql',version:'1',roots:['db']}];
  const result=await analyze(req);assert.ok(result.facts.some(f=>f.kind==='SQL_COLUMN_DECLARATION'&&f.attributes.name==='email'&&f.attributes.dataType==='VARCHAR'));
  assert.ok(result.facts.some(f=>f.kind==='ORM_TABLE_MAPPING'&&f.attributes.table==='users'&&f.attributes.schema==='billing'));assert.ok(result.facts.some(f=>f.kind==='ORM_ATTRIBUTE_MAPPING'&&f.attributes.attribute==='email'&&f.attributes.column==='email'));
  assert.ok(result.facts.some(f=>f.kind==='DECLARED_MIGRATION_PRECEDES'&&f.path==='db/V001__users.sql'&&f.attributes.next==='db/V2__read.sql'));
  const conflict=structuredClone(req);conflict.files.push({path:'db/V1.0__duplicate.sql',text:'SELECT 1;',sha256:hash('SELECT 1;'),role:'main'});
  const recovered=await analyze(conflict);assert.ok(recovered.obligations.some(g=>g.reason==='MIGRATION_VERSION_CONFLICT'));assert.ok(!recovered.facts.some(f=>f.kind==='DECLARED_MIGRATION_PRECEDES'));assert.ok(recovered.facts.some(f=>f.kind==='ORM_TABLE_MAPPING'));
});
test('native source-map chains verify captured content and reject a forged original source',async()=>{
  const tools=await(fixtureTools??=require('../../../main/resources/polyglot/architecture/tools.cjs').load('.evo/tools/m845-architecture'));
  const make=(source,content)=>{const map=new tools.sourceMapEncoder.GenMapping();tools.sourceMapEncoder.addSegment(map,0,0,source,0,0);tools.sourceMapEncoder.setSourceContent(map,source,content);return JSON.stringify(tools.sourceMapEncoder.toEncodedMap(map));};
  const source='export const value=1;',middle='var value=1;',output='var v=1;';
  const req=request({'source.ts':source,'intermediate.js':middle,'intermediate.js.map':make('source.ts',source),'dist/bundle.min.js':output,'dist/bundle.min.js.map':make('../intermediate.js',middle)});
  req.context.sourceMaps=[{generated:'intermediate.js',map:'intermediate.js.map'},{generated:'dist/bundle.min.js',map:'dist/bundle.min.js.map'}];
  const r=await analyze(req);assert.ok(r.facts.some(f=>f.kind==='SOURCE_MAP_CHAIN'&&f.attributes.documents.join('|')==='dist/bundle.min.js|intermediate.js|source.ts'));assert.ok(!r.obligations.some(g=>g.path==='dist/bundle.min.js'&&g.reason==='POSSIBLE_MINIFIED_SOURCE_MAPPING_REQUIRED'));
  const forged=structuredClone(req),map=forged.files.find(f=>f.path==='intermediate.js.map');map.text=make('source.ts','different bytes');map.sha256=hash(map.text);
  const bad=await analyze(forged);assert.ok(bad.obligations.some(g=>g.reason==='SOURCE_MAP_SOURCE_CONTENT_MISMATCH'));assert.ok(!bad.facts.some(f=>f.kind==='SOURCE_MAP_CHAIN'&&f.attributes.documents.at(-1)==='source.ts'));
});
test('package alternatives and API schema relations retain source ownership without leaking server values',async()=>{
  const r=await analyze(request({'package.json':'{"name":"web-app","exports":{".":{"browser":"./browser.ts","node":"./server.ts"}}}',
    'browser.ts':'export const client=1;','server.ts':'export const server=1;',
    'api.json':'{"openapi":"3.1.0","servers":[{"url":"https://user:private-password@api.invalid"}],"paths":{"/users":{"get":{"parameters":[{"name":"id","in":"query","schema":{"type":"string"}}],"responses":{"200":{"description":"ok","content":{"application/json":{"schema":{"type":"object"}}}}}}}}}',
    'api.graphql':'type User { id: ID! } type Query { user(id: ID!): User } query UserById { user(id:"1") { id } }'}));
  const exports=r.facts.filter(f=>f.kind==='PACKAGE_EXPORT');assert.equal(exports.length,2);assert.ok(exports.every(f=>f.status==='CONDITIONAL'));assert.ok(r.facts.some(f=>f.kind==='PACKAGE_FILE_OWNERSHIP'&&f.path==='browser.ts'));
  assert.ok(r.facts.some(f=>f.kind==='API_PARAMETER'&&f.attributes.name==='id'));assert.ok(r.facts.some(f=>f.kind==='API_RESPONSE'&&f.attributes.statusCode==='200'));
  assert.ok(r.facts.some(f=>f.kind==='GRAPHQL_FIELD_REFERENCE'&&f.attributes.field==='user'&&f.status==='RESOLVED'));assert.ok(!JSON.stringify(r).includes('private-password'));
});
test('properties logical keys preserve escapes, continuation and last assignment without exposing values',async()=>{
  const r=await analyze(request({'application.properties':'# comment\r\nspace\\ key : private-value\r\nuni\\u0063ode=value\nlong\\\n  key=one\nspace\\ key=two\nemptyKey\n'}));
  const keys=r.facts.filter(f=>f.kind==='CONFIG_KEY').sort((a,b)=>a.span.start-b.span.start).map(f=>f.attributes);assert.deepEqual(keys.map(a=>a.keyPath[0]),['space key','unicode','longkey','space key','emptyKey']);assert.equal(keys[0].effectiveWithinDocument,false);assert.equal(keys[3].effectiveWithinDocument,true);assert.ok(!JSON.stringify(r).includes('private-value'));assert.ok(!r.obligations.some(g=>g.reason==='PROPERTIES_CONTINUATION_REQUIRED'));
});
test('HTML uses retain exact source and missing resources remain obligations',async()=>{
  const text='<main><script type="module" src="./entry.js"></script><img src="./missing.png"></main>';
  const result=await analyze(request({'index.html':text,'entry.js':'export const n=1;'}));
  const script=result.facts.find(f=>f.kind==='RESOURCE_REFERENCE'&&f.target==='entry.js');
  assert.ok(script,'native HTML resource reference');
  assert.equal(text.slice(script.span.start,script.span.end),'./entry.js');
  assert.ok(result.obligations.some(o=>o.reason==='RESOURCE_UNAVAILABLE'&&o.path==='index.html'));
  assert.equal(result.documents.length,2);
});
module.exports={request,hash};
test('CSS imports, layers and custom properties preserve resource ownership',async()=>{
  const r=await analyze(request({'main.css':'@import "./base.css"; @layer app { :root { --ink: red; background: url("./font.woff"); } }','base.css':'p {}'}));
  assert.ok(r.facts.some(f=>f.kind==='STYLE_DEPENDENCY'&&f.target==='base.css'));
  assert.ok(r.facts.some(f=>f.kind==='CSS_LAYER'&&f.attributes.name==='app'));
  assert.ok(r.obligations.some(o=>o.reason==='RESOURCE_UNAVAILABLE'));
});
test('captured references resolve cycles and fragments without network fetch',async()=>{
  const r=await analyze(request({'a.json':'{"$schema":"https://json-schema.org/draft/2020-12/schema","$defs":{"A":{"$ref":"b.json#/$defs/B"}}}',
    'b.json':'{"$defs":{"B":{"$ref":"a.json#/$defs/A"}},"properties":{"remote":{"$ref":"https://example.invalid/missing.json"}}}'}));
  const refs=r.facts.filter(f=>f.kind==='SCHEMA_REFERENCE');assert.equal(refs.filter(f=>f.status==='RESOLVED').length,2);assert.equal(refs.filter(f=>f.status==='UNRESOLVED').length,1);
  assert.ok(r.obligations.some(o=>o.reason==='SCHEMA_REFERENCE_UNAVAILABLE'));
});
test('OpenAPI operation identity includes app and document while keys do not expose secret values',async()=>{
  const req=request({'api.json':'{"openapi":"3.1.0","paths":{"/users":{"get":{"responses":{"200":{"description":"ok"}}}}}}',
    '.env':'TOKEN=extremely-private-value\nPUBLIC_URL=https://local.invalid\n'});
  const a=await analyze(req),b=await analyze({...req,app:'other'});
  const op=a.facts.find(f=>f.kind==='API_OPERATION');assert.equal(op.attributes.method,'GET');assert.equal(op.attributes.route,'/users');
  assert.notEqual(op.target,b.facts.find(f=>f.kind==='API_OPERATION').target);
  assert.ok(!JSON.stringify(a).includes('extremely-private-value'));
});
test('duplicate JSON and YAML keys preserve independent healthy resources',async()=>{
  const r=await analyze(request({'bad.json':'{"a":1,"a":2}','bad.yaml':'a: 1\na: 2\n','ok.json':'{"enabled":true}'}));
  assert.equal(r.documents.length,3);assert.equal(r.obligations.filter(o=>['STRUCTURED_PARSE_ERROR','DUPLICATE_CONFIG_KEY'].includes(o.reason)).length,2);
  assert.ok(r.facts.some(f=>f.path==='ok.json'&&f.kind==='CONFIG_KEY'));
});
test('native GraphQL schema, operation and unknown fragment controls',async()=>{
  const r=await analyze(request({'api.graphql':'type Query { user: String } query User { user }','bad.graphql':'query Missing { ...Absent }'}));
  assert.ok(r.facts.some(f=>f.kind==='GRAPHQL_OPERATION'&&f.attributes.name==='User'));
  assert.ok(r.obligations.some(o=>o.reason==='GRAPHQL_FRAGMENT_UNAVAILABLE'));
});
test('native Protobuf package/service/message and unresolved imports',async()=>{
  const r=await analyze(request({'echo.proto':'syntax="proto3"; package demo; message Request { string text=1; } service Echo { rpc Call(Request) returns (Request); }',
    'missing.proto':'syntax="proto3"; import "absent.proto"; message Broken { Missing body=1; }'}));
  assert.ok(r.facts.some(f=>f.kind==='PROTO_DECLARATION'&&f.attributes.name==='.demo.Echo'&&f.attributes.methods.includes('Call')));
  assert.ok(r.obligations.some(o=>o.reason==='PROTO_TYPE_REFERENCE_UNAVAILABLE'));
});
test('SQL dialect/search path are explicit; no live database is consulted',async()=>{
  const req=request({'query.sql':'SELECT * FROM accounting.users;','ambiguous.sql':'SELECT * FROM users;'});req.context.sqlDialect='Postgresql';
  const r=await analyze(req);assert.ok(r.facts.some(f=>f.kind==='SQL_TABLE_ACCESS'&&f.attributes.schema==='accounting'));
  assert.ok(r.facts.filter(f=>f.kind==='SQL_TABLE_ACCESS').every(f=>f.attributes.rangePrecision==='DOCUMENT_SUPPORT'));
  assert.ok(r.obligations.some(o=>o.reason==='SQL_SEARCH_PATH_UNAVAILABLE'));
  const missing=await analyze(request({'query.sql':'SELECT * FROM users;'}));assert.ok(missing.obligations.some(o=>o.reason==='SQL_DIALECT_REQUIRED'));
});
test('XML forbids entities and records namespaced declarations',async()=>{
  const r=await analyze(request({'beans.xml':'<beans xmlns="http://www.springframework.org/schema/beans"><bean id="a" class="demo.A"/></beans>',
    'bad.xml':'<!DOCTYPE x [<!ENTITY ex SYSTEM "file:///private">]><x>&ex;</x>'}));
  assert.ok(r.facts.some(f=>f.kind==='DECLARED_XML_BEAN'&&f.attributes.namespace==='http://www.springframework.org/schema/beans'));
  assert.ok(r.obligations.some(o=>o.reason==='XML_EXTERNAL_OR_INTERNAL_ENTITY_FORBIDDEN'));
});
test('Vue native SFC/script/template compilation binds imported child',async()=>{
  const files={'App.vue':'<script setup>import Card from "./Card.vue"</script><template><p>Title</p><Card @click="go" /></template>','Card.vue':'<template><button>Card</button></template>'};
  const r=await analyze(request(files,[{id:'vue',version:'3.5'}]));
  assert.ok(r.facts.some(f=>f.kind==='COMPONENT_USE'&&f.target==='Card.vue'));assert.ok(r.native.some(n=>n.engine==='@vue/compiler-sfc'));
  assert.ok(r.facts.find(f=>f.kind==='COMPONENT_DECLARATION'&&f.path==='App.vue').attributes.generated.length===2);
  assert.ok(!r.obligations.some(g=>g.reason==='SOURCE_MAP_SOURCE_UNAVAILABLE'));
  const mapped=r.facts.find(f=>f.kind==='SOURCE_MAP_BINDING'&&f.path==='App.vue'&&f.attributes.sourceProjection?.language==='html');assert.ok(mapped);
  assert.equal(files['App.vue'].slice(mapped.attributes.sourceProjection.start,mapped.attributes.sourceProjection.end),'<p>Title</p><Card @click="go" />');
});
test('Svelte native compiler keeps component, event and conditional structure',async()=>{
  const r=await analyze(request({'App.svelte':'<script>import Card from "./Card.svelte"; let show = true;</script>{#if show}<Card/>{/if}','Card.svelte':'<button onclick={() => {}}>Hello</button>'},[{id:'svelte',version:'5'}]));
  assert.ok(r.facts.some(f=>f.kind==='COMPONENT_USE'&&f.target==='Card.svelte'));assert.ok(r.facts.some(f=>f.kind==='TEMPLATE_GUARD'));
});
test('Astro native transform binds island with checked original coordinates',async()=>{
  const text='---\nimport Card from "./Card.astro";\n---\n<Card client:load/>';
  const r=await analyze(request({'src/pages/index.astro':text,'src/pages/Card.astro':'<p>Hi</p>'},[{id:'astro',version:'2.13'}]));
  const component=r.facts.find(f=>f.kind==='COMPONENT_USE');assert.ok(component);assert.equal(text.slice(component.span.start,component.span.end),'Card');
  assert.ok(r.facts.some(f=>f.kind==='HYDRATION_BOUNDARY'));assert.ok(r.native.some(n=>n.engine==='@astrojs/compiler'));
});
test('framework lookalikes and type-only imports never become state or runtime composition',async()=>{
  const r=await analyze(request({'fake.tsx':'function useState(n:number){ return n; } useState(1); function get(s:string){return s;} get("/api"); export {};'},[{id:'react',version:'19'}]));
  assert.equal(r.facts.filter(f=>['STATE_SERVICE_USE','NETWORK_REQUEST'].includes(f.kind)).length,0);
});
test('profile versions, source hashes and duplicate paths fail closed',async()=>{
  const unknown=await analyze(request({'x.vue':'<template/>'},[{id:'vue',version:'999'}]));assert.ok(unknown.obligations.some(o=>o.reason==='FRAMEWORK_PROFILE_UNAVAILABLE'));assert.equal(unknown.facts.filter(f=>f.kind==='COMPONENT_DECLARATION').length,0);assert.equal(unknown.documents.length,1);
  const bad=request({'x.ts':'const n=1'});bad.files[0].text+=' ';await assert.rejects(()=>analyze(bad),/CAPTURED_SOURCE_HASH_OR_DUPLICATE/);
});
test('same captured input produces identical canonical facts and gap identities',async()=>{
  const req=request({'a.json':'{"$ref":"missing.json"}','a.html':'<img src="./missing.png">'});
  assert.deepEqual(await analyze(req),await analyze(req));
});
test('real React definitions distinguish alias hooks from same-name local functions',async()=>{
  const req=request({'App.tsx':'import {useState as state} from "react"; export function App(){const [n]=state(1);return <button>{n}</button>;}','fake.ts':'function useState(n:number){return n} useState(1); export {};'},[{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));
  const r=await analyze(req),hooks=r.facts.filter(f=>f.kind==='STATE_SERVICE_USE');assert.equal(hooks.length,1);assert.equal(hooks[0].attributes.api,'useState');
  assert.equal(r.obligations.filter(o=>o.reason==='NATIVE_TYPECHECK_ERROR'&&o.path==='App.tsx').length,0);
});
test('real DOM definitions bind fetch alias, reject local fetch and keep computed endpoint unknown',async()=>{
  const req=request({'api.ts':'const load = fetch; export const run=()=>load("/users"); export const dynamic=(p:string)=>fetch(p);',
    'xhr.ts':'export function xhr(){const client=new XMLHttpRequest();client.open("GET","/users");navigator.serviceWorker.register("/worker.js");}',
    'fake.ts':'function fetch(x:string){return x;} fetch("/fake");const fake={open(a:string,b:string){},register(s:string){}};fake.open("GET","/fake");fake.register("/fake");export {};'});req.files.push(...require('./architecture-fixture.cjs').libraries());
  const r=await analyze(req);assert.equal(r.facts.filter(f=>f.kind==='NETWORK_REQUEST').length,1);assert.ok(r.obligations.some(o=>o.reason==='DYNAMIC_ENDPOINT'));
  assert.equal(r.facts.filter(f=>f.kind==='CLIENT_API_USE'&&f.attributes.protocol==='XMLHttpRequest').length,1);assert.ok(r.facts.some(f=>f.kind==='WORKER_BOUNDARY'&&f.attributes.protocol==='ServiceWorker'));assert.ok(!r.facts.some(f=>f.path==='fake.ts'&&['WORKER_BOUNDARY','CLIENT_API_USE'].includes(f.kind)));
});
test('pure local API wrapper refinement follows resolved aliases and terminates cycles',async()=>{
  const req=request({'api.ts':'export function request(url:string){return fetch(url)} export const again=(url:string)=>request(url); export const run=()=>again("/users"); function cycle(url:string):unknown{return cycle(url)} cycle("/none");'});req.files.push(...require('./architecture-fixture.cjs').libraries());
  const r=await analyze(req),requestFact=r.facts.find(f=>f.kind==='NETWORK_REQUEST'&&f.attributes.wrapperFootprint.length===2);assert.ok(requestFact);assert.equal(requestFact.attributes.endpointDigest,hash('/users'));
});
test('real Angular decorator and native template syntax retain unresolved AOT obligation',async()=>{
  const req=request({'app.ts':'import {Component} from "@angular/core"; @Component({selector:"demo-app",standalone:true,template:`<button (click)="run()">{{name}}</button>`}) export class App {name="hi";run(){}}'},[{id:'angular',version:'20.3'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@angular/core','rxjs']));
  const r=await analyze(req);assert.ok(r.facts.some(f=>f.kind==='ANGULAR_DECLARATION'));assert.ok(r.native.some(n=>n.engine==='@angular/compiler'));
});
test('Angular AOT binds standalone imported component through the real template checker',async()=>{
  const req=request({'app.ts':'import {Component} from "@angular/core"; @Component({selector:"demo-child",standalone:true,template:`<p>Child</p>`}) export class Child {} @Component({selector:"demo-app",standalone:true,imports:[Child],template:`<demo-child/>`}) export class App {}'},[{id:'angular',version:'20.3'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@angular/core','rxjs']));req.context.angularCompilerOptions={strictTemplates:true};
  req.context.compilerOptions={target:'ES2022',module:'ESNext',moduleResolution:'Bundler',strict:true,experimentalDecorators:true,skipLibCheck:true};
  const r=await analyze(req);const edge=r.facts.find(f=>f.kind==='COMPONENT_USE'&&f.attributes.binding==='ngtsc-template-checker');assert.ok(edge);assert.equal(edge.attributes.target,'Child');
  assert.ok(r.native.some(n=>n.engine==='@angular/compiler-cli'&&n.mode==='strictTemplates'));
});
test('native HCL and Docker metadata keep executable effects explicitly unknown',async()=>{
  const r=await analyze(request({'infra.tf':'module "local" { source = "./module" }\nresource "demo_type" "one" { name="demo" }',
    'Dockerfile':'FROM alpine:3.22\nCOPY ./app /app\nRUN echo authored\n'}));
  assert.ok(r.facts.some(f=>f.kind==='HCL_DECLARATION'&&f.attributes.family==='resource'));
  assert.ok(r.facts.some(f=>f.kind==='CONTAINER_IMAGE'));assert.ok(r.obligations.some(o=>o.reason==='EXECUTABLE_BUILD_EFFECTS_UNKNOWN'));
});
test('binary and unknown files retain full independent category/disposition accounting',async()=>{
  const req=request({'future.xyz':'opaque input'});req.files.push({path:'logo.bin',data:'AAECAw==',sha256:hash(Buffer.from([0,1,2,3])),role:'resource'});
  const r=await analyze(req);assert.equal(r.documents.length,2);assert.equal(r.census.length,24);assert.equal(r.dispositions.length,2);
  assert.ok(r.obligations.some(o=>o.reason==='BINARY_METADATA_PROVIDER_REQUIRED'&&o.span===null));assert.ok(r.obligations.some(o=>o.reason==='RESOURCE_CLASSIFICATION_REQUIRED'));
});
test('type-only imported JSX name cannot certify runtime component composition',async()=>{
  const req=request({'App.tsx':'import type {Card} from "./Card"; export const App=()=> <Card/>;','Card.tsx':'export function Card(){return <p>Hi</p>}'},[{id:'react',version:'19'}]);req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));
  const r=await analyze(req);assert.equal(r.facts.filter(f=>f.path==='App.tsx'&&f.kind==='COMPONENT_USE'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.path==='App.tsx'&&o.reason==='COMPONENT_BINDING_UNAVAILABLE'));
});
test('Vue template-local loop binding cannot be replaced by a same-name import',async()=>{
  const r=await analyze(request({'App.vue':'<script setup>import Card from "./Card.vue";const items=[];</script><template><div v-for="Card in items"><Card/></div></template>','Card.vue':'<template><p>Hi</p></template>'},[{id:'vue',version:'3.5'}]));
  assert.equal(r.facts.filter(f=>f.path==='App.vue'&&f.kind==='COMPONENT_USE'&&f.target==='Card.vue'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.reason==='TEMPLATE_LOCAL_COMPONENT_BINDING'));
});
test('Svelte each-local binding cannot be replaced by a same-name import',async()=>{
  const r=await analyze(request({'App.svelte':'<script>import Card from "./Card.svelte"; let items=[];</script>{#each items as Card}<Card/>{/each}','Card.svelte':'<p>Hi</p>'},[{id:'svelte',version:'5'}]));
  assert.equal(r.facts.filter(f=>f.path==='App.svelte'&&f.kind==='COMPONENT_USE'&&f.target==='Card.svelte'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.reason==='TEMPLATE_LOCAL_COMPONENT_BINDING'));
});
test('captured Protobuf imports resolve message references through the declared import root',async()=>{
  const r=await analyze(request({'schema/model.proto':'syntax="proto3"; package demo; message Request { string text=1; }','api.proto':'syntax="proto3"; package demo; import "schema/model.proto"; service Echo { rpc Call(Request) returns (Request); }'}));
  assert.equal(r.obligations.filter(o=>o.path==='api.proto'&&o.reason==='PROTO_TYPE_REFERENCE_UNAVAILABLE').length,0);
  assert.ok(r.facts.some(f=>f.path==='api.proto'&&f.kind==='PROTO_TYPE_REFERENCE'&&f.target==='schema/model.proto#demo.Request'));
});
test('Avro native schema validation retains namespaced record fields and rejects missing types',async()=>{
  const r=await analyze(request({'event.json':'{"type":"record","name":"Event","namespace":"demo","fields":[{"name":"id","type":"string"}]}','bad.json':'{"type":"record","name":"Broken","fields":[{"name":"body","type":"Missing"}]}'}));
  assert.ok(r.facts.some(f=>f.kind==='AVRO_SCHEMA'&&f.attributes.name==='demo.Event'&&f.attributes.fields.includes('id')));
  assert.ok(r.obligations.some(o=>o.reason==='AVRO_SCHEMA_REFERENCE_OR_DIALECT_UNSUPPORTED'));
});
test('duplicate schema base URIs never select the first captured document as certain',async()=>{
  const r=await analyze(request({'a.json':'{"$id":"https://schema.invalid/shared","type":"string"}','b.json':'{"$id":"https://schema.invalid/shared","type":"number"}','use.json':'{"$ref":"https://schema.invalid/shared"}'}));
  assert.equal(r.facts.filter(f=>f.path==='use.json'&&f.kind==='SCHEMA_REFERENCE'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.reason==='SCHEMA_BASE_URI_CONFLICT'));
});
test('source input enumeration order cannot change the captured semantic result',async()=>{
  const req=request({'z.ts':'function shared(){return 1;} shared();','a.ts':'function shared(){return 2;} shared();','a.json':'{"$id":"https://schema.invalid/same"}','b.json':'{"$id":"https://schema.invalid/same"}','use.json':'{"$ref":"https://schema.invalid/same"}'});
  const first=await analyze(req),reverse=await analyze({...req,files:[...req.files].reverse()});assert.equal(first.inputClosure,reverse.inputClosure);assert.deepEqual(first,reverse);
});

test('Next execution directives require a valid profile and a directive prologue',async()=>{
  const r=await analyze(request({'client.ts':'"use client"; export const n=1;',
    'late.ts':'const n=1; "use client";', 'nested.ts':'export function run(){ "use client"; return 1; }'},[{id:'next',version:'15'}]));
  assert.deepEqual(r.facts.filter(f=>f.kind==='EXECUTION_DIRECTIVE'&&f.status==='RESOLVED').map(f=>f.path),['client.ts']);
  const wrong=await analyze(request({'client.ts':'"use client"; export const n=1;'},[{id:'next',version:'999'}]));
  assert.equal(wrong.facts.filter(f=>f.kind==='EXECUTION_DIRECTIVE'&&f.status==='RESOLVED').length,0);
  assert.ok(wrong.obligations.some(o=>o.reason==='FRAMEWORK_PROFILE_UNAVAILABLE'));
});

test('GraphQL fragment binding keeps the fragment namespace and duplicate ambiguity',async()=>{
  const r=await analyze(request({'wrong.graphql':'type Query { user: String } query User { ...Query }',
    'ambiguous.graphql':'type Query { user: String } query User { ...Shared } fragment Shared on Query { user } fragment Shared on Query { user }'}));
  assert.equal(r.facts.filter(f=>f.kind==='GRAPHQL_FRAGMENT_REFERENCE'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.path==='wrong.graphql'&&o.reason==='GRAPHQL_FRAGMENT_UNAVAILABLE'));
  assert.ok(r.obligations.some(o=>o.path==='ambiguous.graphql'&&o.reason==='GRAPHQL_FRAGMENT_AMBIGUOUS'));
});

test('captured GraphQL context binds an anonymous operation to its explicit schema and fragment',async()=>{
  const req=request({'schema.graphql':'type Query { user: String }','fragment.graphql':'fragment Shared on Query { user }','client.graphql':'{ ...Shared }'});
  req.context.graphqlContexts={'client.graphql':{schema:['schema.graphql'],fragments:['fragment.graphql']}};
  const r=await analyze(req);
  assert.ok(r.facts.some(f=>f.path==='client.graphql'&&f.kind==='GRAPHQL_OPERATION'&&f.attributes.name===null));
  assert.ok(r.facts.some(f=>f.path==='client.graphql'&&f.kind==='GRAPHQL_FRAGMENT_REFERENCE'&&f.status==='RESOLVED'));
  assert.ok(r.facts.some(f=>f.path==='client.graphql'&&f.kind==='GRAPHQL_SCHEMA_BINDING'&&f.status==='RESOLVED'&&f.supports.some(p=>p.path==='schema.graphql')));
});

test('valid floating config values and cyclic YAML aliases cannot discard healthy resources',async()=>{
  const r=await analyze(request({'compose.json':'{"services":{"api":{"build":{"args":{"RATIO":0.5}}}}}',
    'cycle.yaml':'services:\n  api:\n    build: &loop {again: *loop}\n','healthy.json':'{"ready":true}'}));
  assert.ok(r.facts.some(f=>f.path==='compose.json'&&f.kind==='SERVICE_CONFIG_REFERENCE'));
  assert.ok(r.obligations.some(o=>o.path==='cycle.yaml'&&o.reason==='CYCLIC_CONFIG_ALIAS'));
  assert.ok(r.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));
});

test('explicit GraphQL source selection acquires files with nonstandard extensions',async()=>{
  const req=request({'schema.contract':'type Query { user: String }','fragment.contract':'fragment Shared on Query { user }','client.graphql':'{ ...Shared }'});
  req.context.graphqlContexts={'client.graphql':{schema:['schema.contract'],fragments:['fragment.contract']}};
  const r=await analyze(req);
  assert.ok(r.facts.some(f=>f.path==='fragment.contract'&&f.kind==='GRAPHQL_DEFINITION'));
  assert.equal(r.dispositions.find(d=>d.path==='schema.contract').disposition,'NATIVE_CONTEXT_INPUT');
  const missing=structuredClone(req);missing.context.graphqlContexts['client.graphql'].fragments.push('missing.contract');
  const incomplete=await analyze(missing);
  assert.equal(incomplete.facts.filter(f=>f.path==='client.graphql'&&f.kind==='GRAPHQL_FRAGMENT_REFERENCE'&&f.status==='RESOLVED').length,0);
});

test('unregistered frontend imports retain a candidate and an actionable extension obligation',async()=>{
  const r=await analyze(request({'main.ts':'import {createSignal} from "solid-js"; export const value=createSignal(1);'}));
  assert.ok(r.facts.some(f=>f.kind==='FRAMEWORK_CANDIDATE'&&f.status==='CONDITIONAL'&&f.attributes.package==='solid-js'));
  assert.ok(r.obligations.some(o=>o.reason==='FRAMEWORK_EXTENSION_PROFILE_REQUIRED'));
});

test('source-map-less possible minified inputs preserve their mapping obligation',async()=>{
  const r=await analyze(request({'bundle.min.js':'(()=>{})();'}));
  assert.ok(r.obligations.some(o=>o.path==='bundle.min.js'&&o.reason==='POSSIBLE_MINIFIED_SOURCE_MAPPING_REQUIRED'));
  assert.equal(r.dispositions.length,1);
});

test('an unsupported framework tuple cannot certify state use through otherwise valid React bindings',async()=>{
  const req=request({'App.tsx':'import {useState} from "react"; export function App(){return useState(1);}'},[{id:'react',version:'999'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));
  const r=await analyze(req);assert.equal(r.facts.filter(f=>f.kind==='STATE_SERVICE_USE'&&f.status==='RESOLVED').length,0);
  assert.ok(r.obligations.some(o=>o.reason==='STATE_SERVICE_PROFILE_REQUIRED'));
});

test('native Svelte script input binds store calls back to original UTF16 source',async()=>{
  const text='<!--😀-->\r\n<script lang="ts">import {writable} from "svelte/store"; const value=writable(1);</script><p>Hi</p>';
  const req=request({'App.svelte':text},[{id:'svelte',version:'5'}]);req.files.push(...require('./architecture-fixture.cjs').libraries(['svelte','@types/estree','csstype']));
  const r=await analyze(req),state=r.facts.find(f=>f.path==='App.svelte'&&f.kind==='STATE_SERVICE_USE'&&f.status==='RESOLVED');assert.ok(state);
  assert.equal(text.slice(state.span.start,state.span.end),'writable(1)');
  assert.ok(r.native.some(n=>n.path==='App.svelte'&&n.engine==='typescript'&&n.sourceProjection));
  assert.equal(r.documents.filter(d=>!['dependency','toolchain'].includes(d.role)).length,1);
});

test('native Vue script source uses captured runtime definitions and keeps type errors explicit',async()=>{
  const text='<script setup lang="ts">import {ref} from "vue"; const value=ref(1); const wrong:number="bad";</script><template><p>Hi</p></template>';
  const req=request({'App.vue':text},[{id:'vue',version:'3.5'}]);
  const modules='.evo/tools/m845-vue-declarations-3.5.22/node_modules';const lock=require(require('node:path').resolve(modules,'../package-lock.json'));
  req.files.push(...require('./architecture-fixture.cjs').libraries(Object.keys(lock.packages).filter(p=>p.startsWith('node_modules/')).map(p=>p.slice(13)),modules));
  const r=await analyze(req),state=r.facts.find(f=>f.path==='App.vue'&&f.kind==='STATE_SERVICE_USE'&&f.attributes.api==='ref');assert.ok(state);
  assert.equal(text.slice(state.span.start,state.span.end),'ref(1)');
  assert.ok(r.obligations.some(o=>o.path==='App.vue'&&o.reason==='NATIVE_TYPECHECK_ERROR'));
});

test('producer positions agree with original CRLF and reject split surrogate offsets',()=>{
  const {span}=require('../../../main/resources/polyglot/architecture/common.cjs');
  assert.deepEqual(span('a\r\nb',2,3),{start:2,end:3,startLine:1,startColumn:3,endLine:2,endColumn:1});
  assert.throws(()=>span('😀',1,2),/NATIVE_SOURCE_RANGE_INVALID/);
});

test('unrelated Svelte component scripts never share lexical wrapper scope',async()=>{
  const req=request({'A.svelte':'<script>function localRequest(url){return fetch(url)}</script><p>A</p>',
    'B.svelte':'<script>localRequest("/users");</script><p>B</p>'},[{id:'svelte',version:'5'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries());
  const r=await analyze(req);assert.equal(r.facts.filter(f=>f.path==='B.svelte'&&f.kind==='NETWORK_REQUEST').length,0);
  assert.ok(r.obligations.some(o=>o.path==='B.svelte'&&o.reason==='NATIVE_TYPECHECK_ERROR'));
});

test('captured compiler target chooses the matching native default library',async()=>{
  const req=request({'target.ts':'export const last=[1].findLast(n=>n>0);'});req.files.push(...require('./architecture-fixture.cjs').libraries());
  req.context.compilerOptions={target:'ES2015',module:'ESNext',moduleResolution:'Bundler',strict:true};
  const old=await analyze(req);assert.ok(old.obligations.some(o=>o.path==='target.ts'&&o.reason==='NATIVE_TYPECHECK_ERROR'));
  req.context.compilerOptions.target='ESNext';const current=await analyze(req);
  assert.equal(current.obligations.filter(o=>o.path==='target.ts'&&o.reason==='NATIVE_TYPECHECK_ERROR').length,0);
});

test('native automatic type roots see captured directories without reading ambient packages',async()=>{
  const req=request({'App.tsx':'export const App=()=> <button disabled>Hi</button>;','Bad.tsx':'export const Bad=()=> <button definitelyInvalid={1}/>;'},[{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));
  const r=await analyze(req);
  assert.equal(r.obligations.filter(o=>o.path==='App.tsx'&&o.reason==='NATIVE_TYPECHECK_ERROR').length,0);
  assert.ok(r.obligations.some(o=>o.path==='Bad.tsx'&&o.reason==='NATIVE_TYPECHECK_ERROR'));
});

test('unsupported schema and OpenAPI dialect values retain digests without exposing credentials',async()=>{
  const secret='private-schema-password';
  const r=await analyze(request({'schema.json':JSON.stringify({$schema:'https://user:'+secret+'@schema.invalid/private?token='+secret}),
    'api.json':JSON.stringify({openapi:'3.1.'+secret,paths:{'/users':{get:{responses:{}}}}})}));
  assert.ok(!JSON.stringify(r).includes(secret));
  assert.equal(r.facts.filter(f=>f.kind==='API_OPERATION').length,0);
  assert.ok(r.obligations.some(o=>o.reason==='JSON_SCHEMA_DIALECT_UNSUPPORTED'));
  assert.ok(r.obligations.some(o=>o.reason==='OPENAPI_VERSION_UNSUPPORTED'));
});

test('component callable ownership connects its state and refined API boundary',async()=>{
  const req=request({'App.tsx':'import {useState} from "react";import {request} from "./api";export function App(){const [n]=useState(1);request("/users");return <button>{n}</button>}',
    'api.ts':'export function request(url:string){return fetch(url)}'},[{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));const r=await analyze(req);
  const declared=r.facts.find(f=>f.kind==='DECLARATION'&&f.attributes.name==='App');
  const component=r.facts.find(f=>f.kind==='COMPONENT_DECLARATION'&&f.target===declared.target);assert.ok(component);
  const owner=r.facts.find(f=>f.kind==='CALLABLE_OWNER'&&f.attributes.symbol===declared.target);assert.ok(owner);
  for(const kind of ['STATE_SERVICE_USE','NETWORK_REQUEST'])assert.ok(r.facts.some(f=>f.path==='App.tsx'&&f.kind===kind&&f.attributes.owner===owner.target),kind);
});

test('JSX namespace components bind lowercase aliases and withhold type-only namespace targets',async()=>{
  const req=request({'App.tsx':'import * as ui from "./Card";import type * as types from "./Card";export const App=()=> <><ui.Card/><types.Card/></>;',
    'Card.tsx':'export function Card(){return <p>Hi</p>}'},[{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));const r=await analyze(req);
  const uses=r.facts.filter(f=>f.path==='App.tsx'&&f.kind==='COMPONENT_USE'&&f.status==='RESOLVED');assert.equal(uses.length,1);assert.equal(uses[0].attributes.tag,'ui.Card');
  assert.ok(r.obligations.some(o=>o.path==='App.tsx'&&o.reason==='COMPONENT_BINDING_UNAVAILABLE'));
});

test('native inline CSS references preserve HTML and SFC original source offsets',async()=>{
  const files={'index.html':'<!--😀-->\r\n<style>@import "./base.css";p{background:url("./image.svg")}</style>',
    'App.vue':'<template><p>Hi</p></template><style>@import "./base.css";p{background:url("./image.svg")}</style>',
    'Card.svelte':'<p>Hi</p><style>@import "./base.css";p{background:url("./image.svg")}</style>',
    'base.css':'p{}','image.svg':'<svg/>'};
  const r=await analyze(request(files,[{id:'vue',version:'3.5'},{id:'svelte',version:'5'}]));
  for(const path of ['index.html','App.vue','Card.svelte']){
    const link=r.facts.find(f=>f.path===path&&f.kind==='STYLE_RESOURCE'&&f.target==='image.svg');assert.ok(link,path);
    assert.equal(files[path].slice(link.span.start,link.span.end),'url("./image.svg")');
    assert.ok(r.facts.some(f=>f.path===path&&f.kind==='STYLE_DEPENDENCY'&&f.target==='base.css'));
  }
});

test('React class component state use links the method to its native component container',async()=>{
  const req=request({'App.tsx':'import * as React from "react";export class App extends React.Component<{}, {count:number}>{state={count:0};componentDidMount(){this.setState({count:1})}render(){return <p>{this.state.count}</p>}}'},[{id:'react',version:'19'}]);
  req.files.push(...require('./architecture-fixture.cjs').libraries(['@types/react','csstype']));const r=await analyze(req);
  const component=r.facts.find(f=>f.kind==='COMPONENT_DECLARATION'&&f.attributes.meaning==='native-react-base-type');assert.ok(component);
  const update=r.facts.find(f=>f.kind==='STATE_SERVICE_USE'&&f.attributes.api==='setState');assert.ok(update);
  assert.ok(r.facts.some(f=>f.kind==='CALLABLE_OWNER'&&f.target===update.attributes.owner&&f.attributes.container===component.target));
});

test('native CSS value locations retain repeated and escaped URL occurrences',async()=>{
  const text='p{background:url("background") url("./image.svg") url("./image.svg") url("./image\\20 one.svg")}';
  const r=await analyze(request({'main.css':text,'background':'opaque','image.svg':'<svg/>','image one.svg':'<svg/>'}));
  const refs=r.facts.filter(f=>f.path==='main.css'&&f.kind==='STYLE_RESOURCE');assert.equal(refs.length,4);
  assert.equal(new Set(refs.map(f=>f.span.start)).size,4);
  assert.ok(refs.some(f=>f.target==='image one.svg'&&f.status==='RESOLVED'));
  assert.ok(refs.every(f=>text.slice(f.span.start,f.span.end).startsWith('url(')));
});

test('native HTML script excerpts expose API usage at the original source location',async()=>{
  const text='<!--😀-->\r\n<script>fetch("/users");</script>';const req=request({'index.html':text},[{id:'html',version:'html5'}]);req.files.push(...require('./architecture-fixture.cjs').libraries());
  const r=await analyze(req),api=r.facts.find(f=>f.path==='index.html'&&f.kind==='NETWORK_REQUEST');assert.ok(api);
  assert.equal(text.slice(api.span.start,api.span.end),'"/users"');
  assert.ok(r.native.some(n=>n.path==='index.html'&&n.engine==='typescript'&&n.sourceProjection?.language==='js'));
});

test('declared HTML base never resolves an asset against the wrong captured directory',async()=>{
  const req=request({'index.html':'<base href="./assets/"><img src="./image.svg"><style>p{background:url("./image.svg")}</style>',
    'external.html':'<base href="https://example.invalid/assets/"><img src="./image.svg">','image.svg':'<svg/>','assets/image.svg':'<svg/>'});
  const r=await analyze(req);
  for(const kind of ['RESOURCE_REFERENCE','STYLE_RESOURCE'])assert.ok(r.facts.some(f=>f.path==='index.html'&&f.kind===kind&&f.target==='assets/image.svg'));
  assert.equal(r.facts.filter(f=>f.path==='external.html'&&f.kind==='RESOURCE_REFERENCE'&&f.status==='RESOLVED').length,0);
});
