'use strict';
const {test,before}=require('node:test'),assert=require('node:assert/strict');
const worker=require('../../../main/resources/polyglot/architecture/worker.cjs');
const tools=require('../../../main/resources/polyglot/architecture/tools.cjs');
const models=require('../../../main/resources/polyglot/architecture/bridge-models.cjs');
const {sha}=require('../../../main/resources/polyglot/architecture/common.cjs');
const {libraries}=require('./architecture-fixture.cjs');
let sdk;before(async()=>{sdk=await tools.load('.evo/tools/m845-architecture',undefined,undefined,undefined,'D:/UIT/evo-tool-cache/m845-bridge-native-node-v1');});
async function analyze(files,binding,context={}){
  files={'bridge.json':JSON.stringify({schema:'architecture-bridge-bindings:m8.4-v1',...binding}),...files};
  return worker.analyze({schema:'architecture-request:m8.4-v1',scope:sha('registered native bridge controls'),repository:'urn:fixture:bridges',app:'app',profiles:[],context:{conditions:{},bridgeModels:'bridge.json',...context},files:[...Object.entries(files).map(([path,text])=>({path,text,sha256:sha(text),role:'main'})),...(context.nativeLibraries?libraries(['protobufjs','long']):context.workerRoots?libraries():[])]},{tools:sdk});
}
test('native SQL query and DDL bindings retain database/connection, table and read/write meaning',async()=>{
  const result=await analyze({'schema.sql':'CREATE TABLE app.users(id integer);','query.sql':'SELECT id FROM app.users;','update.sql':'UPDATE app.users SET id=2;'}, {sql:[{files:['schema.sql','query.sql','update.sql'],database:'users-db',connection:'primary',dialect:'Postgresql'}]},{sqlDialect:'Postgresql'});
  const rows=result.facts.filter(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B05');assert.ok(rows.some(f=>f.attributes.direction==='PROVIDES'&&f.attributes.descriptor.object==='users'));assert.ok(rows.some(f=>f.attributes.descriptor.access==='READS'));assert.ok(rows.some(f=>f.attributes.descriptor.access==='WRITES'));assert.ok(rows.every(f=>f.attributes.descriptor.database.startsWith('sha256:')));
  const missing=await analyze({'schema.sql':'CREATE TABLE app.users(id integer);'}, {sql:[{files:['schema.sql'],database:'users-db',connection:'primary',dialect:'MySQL'}]},{sqlDialect:'Postgresql'});assert.ok(missing.obligations.some(g=>g.reason==='SQL_BRIDGE_CONTEXT_REQUIRED'));assert.ok(!missing.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'));
});
test('native GraphQL field-use points to its actual schema owner instead of matching a name',async()=>{
  const result=await analyze({'schema.graphql':'type Query { user(id: ID!): String }','query.graphql':'query User { user(id:"1") }'},{graphql:[{schemas:['schema.graphql'],documents:['query.graphql'],namespace:'users-api',version:'1.0'}]},{graphqlContexts:{'query.graphql':{schema:['schema.graphql']}}});
  const rows=result.facts.filter(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B03');const required=rows.find(f=>f.attributes.direction==='REQUIRES');assert.ok(required);assert.equal(required.attributes.descriptor.operation,'Query.user');assert.equal(required.attributes.candidates.length,1);assert.ok(rows.some(f=>f.id===required.attributes.candidates[0]&&f.path==='schema.graphql'));
  const invalid=await analyze({'schema.graphql':'type Query { user:String }','query.graphql':'query User { missing }'},{graphql:[{schemas:['schema.graphql'],documents:['query.graphql'],namespace:'users-api',version:'1.0'}]},{graphqlContexts:{'query.graphql':{schema:['schema.graphql']}}});assert.ok(invalid.obligations.some(g=>g.reason==='GRAPHQL_OPERATION_INVALID'));assert.ok(!invalid.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.direction==='REQUIRES'));
});


test('native AsyncAPI send/receive retain broker namespace, routing and payload schema',async()=>{
  const spec={asyncapi:'3.0.0',info:{title:'Orders',version:'1.0.0'},servers:{local:{host:'localhost:9092',protocol:'kafka'}},channels:{orders:{address:'orders',servers:[{$ref:'#/servers/local'}],messages:{event:{payload:{type:'object',properties:{id:{type:'integer'}},required:['id']}}}}},operations:{publish:{action:'send',channel:{$ref:'#/channels/orders'}},receive:{action:'receive',channel:{$ref:'#/channels/orders'}}}};
  const result=await analyze({'events.json':JSON.stringify(spec)},{messaging:[{documents:['events.json'],namespace:'shop'}]});
  const rows=result.facts.filter(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B04');
  assert.ok(rows.some(f=>f.attributes.descriptor.bindingRole==='PUBLISHES'));
  assert.ok(rows.some(f=>f.attributes.descriptor.bindingRole==='SUBSCRIBES'));
  assert.ok(rows.every(f=>f.attributes.descriptor.routing.startsWith('sha256:')));
  const amqp=JSON.parse(JSON.stringify(spec));amqp.servers.local={host:'localhost:5672',protocol:'amqp'};const rabbit=await analyze({'events.json':JSON.stringify(amqp)},{messaging:[{documents:['events.json'],namespace:'shop'}]});assert.ok(rabbit.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.descriptor.protocol==='amqp'));
  const bad=await analyze({'events.json':JSON.stringify({...spec,channels:{orders:{$ref:'https://example.invalid/channels.json'}}})},{messaging:[{documents:['events.json'],namespace:'shop'}]});
  assert.ok(bad.obligations.some(f=>f.reason==='ASYNCAPI_EXTERNAL_REFERENCE_REQUIRES_CAPTURE'));assert.ok(!bad.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'));
});


test('native browser Worker registration joins send and typed receiver with exact channel and payload',async()=>{
  const files={'main.ts':"const worker=new Worker(new URL('./worker.ts',import.meta.url),{type:'module'}); worker.postMessage({id:1});",'worker.ts':"self.onmessage=(event:MessageEvent<{id:number}>)=>{ const id=event.data.id; };"};
  const binding={workers:[{parent:'main.ts',worker:'worker.ts',namespace:'app',channel:'task'}]};
  const result=await analyze(files,binding,{workerRoots:['worker.ts']});const rows=result.facts.filter(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B07');
  const sent=rows.find(f=>f.attributes.direction==='REQUIRES');assert.ok(sent);assert.equal(sent.attributes.candidates.length,1);assert.ok(rows.some(f=>f.id===sent.attributes.candidates[0]&&f.path==='worker.ts'));
  const incompatible=await analyze({...files,'worker.ts':"self.onmessage=(event:MessageEvent<{id:string}>)=>{ const id=event.data.id; };"},binding,{workerRoots:['worker.ts']});assert.ok(incompatible.obligations.some(g=>g.reason==='WORKER_PAYLOAD_SHAPE_MISMATCH'));assert.ok(!incompatible.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B07'&&f.attributes.direction==='REQUIRES'));
  const shadow=await analyze({...files,'main.ts':"export {}; class Worker {postMessage(x:unknown){} }; const worker=new Worker(); worker.postMessage({id:1});"},binding,{workerRoots:['worker.ts']});assert.ok(!shadow.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'&&f.attributes.profile==='B07'&&f.attributes.direction==='REQUIRES'));assert.ok(shadow.obligations.some(f=>f.reason==='WORKER_NATIVE_REGISTRATION_REQUIRED'));
});


test('native protobuf generator declaration binds a real TS consumer and rejects changed output bytes',async t=>{
 const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),{digest}=require('../../../main/resources/polyglot/architecture/common.cjs');
 const work=fs.mkdtempSync(path.join(os.tmpdir(),'authored-bridge-rpc-'));t.after(()=>fs.rmSync(work,{recursive:true,force:true}));
 const source='syntax="proto3"; package contract; message Request {string id=1;} message Reply {string name=1;} service Users {rpc Lookup(Request) returns(Reply);}';
 const schema=path.join(work,'users.proto');fs.writeFileSync(schema,source);
 const invoke=(main,args)=>new Promise((resolve,reject)=>main(args,(err,text)=>err?reject(err):resolve(text)));
 const js=await invoke(sdk.bridgeNative.pbjs.main,['--target','static-module','--wrap','commonjs',schema]);fs.writeFileSync(path.join(work,'users.js'),js);
 const dts=await invoke(sdk.bridgeNative.pbts.main,['--no-comments',path.join(work,'users.js')]);assert.ok(dts.includes('readonly path: \"/contract.Users/Lookup\"'));
 const root=sdk.protobufjs.parse(source).root,bytes=sdk.protobufDescriptor.FileDescriptorSet.encode(root.toDescriptor('proto3')).finish();
 const outputs={'generated/users.js':sha(js),'generated/users.d.ts':sha(dts)};
 const receipt={schema:'authored-native-generation:m8.4-v1',source:{path:'users.proto',digest:sha(source)},outputs,generators:{tool:sdk.bridgeNative.identity},client:{tool:sdk.bridgeNative.identity},descriptor:{digest:sha(bytes),data:Buffer.from(bytes).toString('base64')}};
 const files={'users.proto':source,'generation.json':JSON.stringify(receipt),'generated/users.js':js,'generated/users.d.ts':dts,'consumer.ts':"import {contract} from './generated/users'; export function use(client:contract.Users){return client.lookup({id:'one'});}"};
 const binding={protobuf:[{source:'users.proto',receipt:'generation.json',version:'1.0.0'}]};
 const result=await analyze(files,binding,{nativeLibraries:true});const consumer=result.facts.find(f=>f.kind==='BRIDGE_ENDPOINT'&&f.path==='consumer.ts'&&f.attributes.profile==='B03');assert.ok(consumer);assert.ok(consumer.attributes.artifacts.some(id=>result.artifacts.some(a=>a.id===id&&a.content.digest===sha(source))),'Consumer must carry the exact schema operand bytes');
 const tampered=await analyze({...files,'generated/users.d.ts':dts+'//changed'},binding,{nativeLibraries:true});assert.ok(tampered.obligations.some(g=>g.reason==='PROTOBUF_GENERATION_BYTES_OR_DESCRIPTOR_MISMATCH'));assert.ok(!tampered.facts.some(f=>f.kind==='BRIDGE_ENDPOINT'&&f.path==='consumer.ts'&&f.attributes.profile==='B03'));
});


test('captured tsconfig owns JS checking while TS errors remain visible',async()=>{
 const options={target:'ES2022',module:'ESNext',moduleResolution:'Bundler',allowJs:true,checkJs:false,strict:true};
 const result=await analyze({'tsconfig.json':JSON.stringify({compilerOptions:options}),'client.js':'/** @type {number} */ const value="runtime"; export {value};','app.ts':'export const broken:number="wrong";'}, {}, {tsconfig:'tsconfig.json',nativeLibraries:true});
 assert.ok(!result.obligations.some(g=>g.path==='client.js'&&g.reason==='NATIVE_TYPECHECK_ERROR'),'The captured project does not enable JS checking');assert.ok(result.obligations.some(g=>g.path==='app.ts'&&g.reason==='NATIVE_TYPECHECK_ERROR'),'Real TS errors must stay visible');
 const strict=await analyze({'tsconfig.json':JSON.stringify({compilerOptions:{...options,checkJs:true}}),'client.js':'/** @type {number} */ const value="runtime"; export {value};'}, {}, {tsconfig:'tsconfig.json',nativeLibraries:true});assert.ok(strict.obligations.some(g=>g.path==='client.js'&&g.reason==='NATIVE_TYPECHECK_ERROR'));
});
