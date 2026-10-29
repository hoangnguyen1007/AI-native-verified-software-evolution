'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const worker=require('../../../main/resources/polyglot/typescript-worker.cjs');
const modulePath=process.env.POLYGLOT_TYPESCRIPT_MODULE;
if(!modulePath)throw Error('POLYGLOT_TYPESCRIPT_MODULE is required; zero/skipped native fixtures do not pass');
const ts=worker.compiler(modulePath);
const libraryRoot=path.dirname(modulePath);
const libraries=fs.readdirSync(libraryRoot).filter(name=>/^lib\..*\.d\.ts$/.test(name)).map(name=>file('toolchain/lib/'+name,fs.readFileSync(path.join(libraryRoot,name),'utf8')));
function file(name,text){return {path:name,text,sha256:require('node:crypto').createHash('sha256').update(Buffer.from(text,'utf8')).digest('hex').replace(/^/,'sha256:')};}
function request(files,language='typescript'){return {schema:'typescript-capture-request:m8.4-v1',scope:'sha256:'+'1'.repeat(64),repositoryScope:'urn:fixture:polyglot',packageScope:'app',language,projectFiles:files.map(f=>f.path),files:[...files,...libraries],options:{target:'ES2022',module:'CommonJS',moduleResolution:'Node10',strict:true,allowJs:language==='javascript',checkJs:language==='javascript'}};}
test('actual checker binds aliased cross-file virtual calls with conditional dispatch and full original spans',()=>{
 const domain=file('domain.ts','export class Service { count=0; run(value:number):number { this.count += value; return this.count; } }');
 const main=file('main.ts',"import {Service as S} from './domain';\r\nconst emoji='\u{1f600}'; const service=new S();\r\nexport function use(){ return service.run(2); }");
 const result=worker.analyze(request([domain,main]),ts);
 const call=result.observations.find(row=>row.fields.symbols.some(s=>s.name==='run')&&row.key.endsWith(':A06:call'));
 assert.ok(call);assert.equal(call.fields.status,'CONDITIONAL');assert.equal(call.fields.reason,'TYPESCRIPT_VIRTUAL_RUNTIME_TARGET_NOT_ESTABLISHED');assert.deepEqual(call.fields.uncertainty,[call.fields.reason]);assert.equal(call.fields.symbols[0].path,'domain.ts');assert.equal(call.fields.symbols[0].origin,'PROJECT');
 assert.equal(main.text.slice(call.fields.span.start,call.fields.span.end),'service.run(2)');assert.equal(call.fields.span.startLine,3);
 assert.ok(result.observations.some(row=>row.key.endsWith(':A07:write')));
 assert.equal(result.documents.length,2);assert.equal(result.census.length,result.observations.length);assert.equal(result.trust,'PRODUCER_ASSERTION');
 for(const name of ['domain.ts','main.ts'])for(let i=1;i<=12;i++)assert.ok(result.census.some(row=>row.fields.document===name&&row.fields.category==='A'+String(i).padStart(2,'0')));
});
test('missing import and checker recovery retain errors rather than inventing a target',()=>{
 const result=worker.analyze(request([file('broken.ts',"import {run} from './missing'; export const v=run();")]),ts);
 assert.ok(result.diagnostics.some(d=>d.code===2307));
 const call=result.observations.find(row=>row.key.endsWith(':A06:call'));assert.ok(['UNRESOLVED','ERROR'].includes(call.fields.status));assert.deepEqual(call.fields.targets,[]);
});
test('type-only imports and interfaces are not reported as runtime state reads',()=>{
 const result=worker.analyze(request([file('model.ts','export interface Model { value:number; }'),file('main.ts',"import type {Model} from './model'; export function run(model:Model){return model.value;}")]),ts);
 const reads=result.observations.filter(row=>row.key.includes(':A07:')&&row.fields.status==='RESOLVED');
 assert.ok(reads.every(row=>row.fields.symbols.every(symbol=>symbol.name!=='Model')));
});
test('native JavaScript and JSDoc resolve calls without importing or executing the application',()=>{
 const source=file('main.js','/** @param {number} x */\nfunction twice(x){return x*2;}\nconst answer=twice(4);');
 const result=worker.analyze(request([source],'javascript'),ts);
 const call=result.observations.find(row=>row.key.endsWith(':A06:call'));assert.equal(call.fields.status,'RESOLVED');assert.equal(call.fields.symbols[0].name,'twice');
});
test('raw source and tool pins, membership, plugin policy and missing files fail closed',()=>{
 const input=request([file('main.ts','export const a=1;')]);input.files[0].text='changed';assert.throws(()=>worker.analyze(input,ts),/SOURCE_HASH/);
 const missing=request([file('main.ts','')]);missing.projectFiles.push('missing.ts');assert.throws(()=>worker.analyze(missing,ts),/MEMBERSHIP/);
 const plugin=request([file('main.ts','')]);plugin.options.plugins=[{name:'untrusted'}];assert.throws(()=>worker.analyze(plugin,ts),/FORBIDDEN/);
 assert.throws(()=>worker.compiler(__filename),/PIN_MISMATCH/);
});
test('reordered captured inputs reproduce the same facts and independent census',()=>{
 const files=[file('a.ts','export function f(){return 1;}'),file('b.ts',"import {f} from './a'; export const v=f();")];
 const a=request(files),b=request([...files].reverse());b.files.reverse();
 const first=worker.analyze(a,ts),second=worker.analyze(b,ts);
 assert.deepEqual(first.documents,second.documents);assert.deepEqual(first.census,second.census);assert.deepEqual(first.observations,second.observations);
});
test('current extractor reproduces plain and embedded authored native capture bytes',()=>{
 for(const revision of ['typescript-native-v4','typescript-embedded-v1']){
 const root=path.resolve(__dirname,'../../../../../analyzer/src/test/resources/polyglot',revision);
 const metadata=JSON.parse(fs.readFileSync(path.join(root,'fixture.json'),'utf8'));
 assert.equal(file('generator.cjs',fs.readFileSync(path.join(root,'generator.cjs'),'utf8')).sha256,metadata.generator);
 const input=request([file('main.ts',fs.readFileSync(path.join(root,'main.ts'),'utf8'))]);
 if(revision==='typescript-embedded-v1')input.files[0].role='embedded';
 input.repositoryScope='urn:test:typescript-native';input.packageScope='app';input.scope=metadata.scope;
 const current=worker.analyze(input,ts);const saved=fs.readFileSync(path.join(root,'capture.ndjson'),'utf8').trimEnd().split('\n').map(line=>JSON.parse(line));
 assert.equal(current.extractor,metadata.extractor);assert.equal(current.tool,metadata.compiler);
 for(const [kind,name] of [['DOCUMENT','documents'],['CENSUS','census'],['OBSERVATION','observations']])assert.deepEqual(current[name],saved.filter(row=>row.kind===kind));
 if(revision==='typescript-embedded-v1'){
  const map=JSON.parse(fs.readFileSync(path.join(root,'source-map.json'),'utf8'));const original=fs.readFileSync(path.join(root,'component.vue'),'utf8');const segment=map.segments[0];
  assert.equal(original.slice(segment.originalStart,segment.originalEnd),input.files[0].text);assert.equal(map.generated.content.digest,input.files[0].sha256);assert.equal(map.originals['component.vue'].content.digest,file('component.vue',original).sha256);
 }
 }
});

test('absent written decorators do not establish absent native implicit generation',()=>{
 for(const language of ['typescript','javascript']){
  const result=worker.analyze(request([file(language==='typescript'?'main.ts':'main.js','class Plain {}')],language),ts);
  const keys=new Set(result.census.filter(row=>row.fields.category==='A08').map(row=>row.key));const fact=result.observations.find(row=>keys.has(row.key)).fields;
  assert.equal(fact.status,'UNSUPPORTED');assert.equal(fact.reason,'NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED');
 }
});
