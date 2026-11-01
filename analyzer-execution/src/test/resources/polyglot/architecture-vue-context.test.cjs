'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {sha}=require('../../../main/resources/polyglot/architecture/common.cjs');
const {libraries}=require('./architecture-fixture.cjs');
let tools,decls;
async function analyze(files,vueOptions={strictTemplates:true}){
  tools??=require('../../../main/resources/polyglot/architecture/tools.cjs').load('.evo/tools/m845-architecture',undefined,undefined,'.evo/tools/m845-vue-language-3.3.13');
  if(!decls){const root='.evo/tools/m845-vue-declarations-3.5.22',lock=JSON.parse(fs.readFileSync(root+'/package-lock.json','utf8'));decls=libraries(Object.keys(lock.packages).filter(n=>n.startsWith('node_modules/')&&!n.slice(13).includes('/node_modules/')).map(n=>n.slice(13)),root+'/node_modules');}
  const input={schema:'architecture-request:m8.4-v1',scope:sha('native Vue full-context fixture'),repository:'urn:fixture:vue-context',app:'web',profiles:[{id:'vue',version:'3.5'}],context:{nativeVue:true,conditions:{},compilerOptions:{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',jsx:'preserve',allowJs:true,checkJs:true,strict:true,skipLibCheck:true},vueCompilerOptions:vueOptions},files:[...Object.entries(files).map(([path,text])=>({path,text,sha256:sha(text),role:'main'})),...decls]};
  return require('../../../main/resources/polyglot/architecture/worker.cjs').analyze(input,{tools:await tools});
}
test('native Vue program checks original SFC template props and keeps mapped diagnostics',async()=>{
  const source='<!--😀-->\r\n<script setup lang="ts">import Card from "./Card.vue"; const text="bad";</script><template><Card :amount="text"/></template>';
  const result=await analyze({'App.vue':source,'Card.vue':'<script setup lang="ts">defineProps<{amount:number}>();</script><template><p>{{amount}}</p></template>'});
  assert.ok(result.native.some(n=>n.path==='App.vue'&&n.engine==='@vue/language-core'&&n.mode==='configured-sfc-program-noEmit'));
  const diagnostic=result.obligations.find(g=>g.path==='App.vue'&&g.reason==='VUE_NATIVE_TYPECHECK_ERROR'&&g.details.code===2322);assert.ok(diagnostic,JSON.stringify(result.obligations.filter(g=>g.reason.startsWith('VUE_')).map(g=>[g.path,g.reason,g.details.code])));assert.ok(diagnostic.span.start>=source.indexOf('<template>'));assert.ok(diagnostic.span.end<=source.length);
  assert.ok(result.artifacts.some(a=>a.source==='App.vue'&&a.kind==='vue-typecheck-virtual'));assert.ok(result.artifacts.some(a=>a.kind==='vue-typecheck-mappings'));
  assert.ok(!result.obligations.some(g=>g.path==='App.vue'&&g.reason==='NATIVE_TYPECHECK_ERROR'&&g.details.code===2307));assert.ok(!result.obligations.some(g=>g.path==='App.vue'&&g.reason==='EMBEDDED_LANGUAGE_CONTEXT_PARTIAL'));
});
test('Vue plugins stay unexecuted and a healthy complete SFC context replays after another source',async()=>{
  const files={'App.vue':'<script setup lang="ts">import Card from "./Card.vue"; const amount=1;</script><template><Card :amount="amount"/></template>','Card.vue':'<script setup lang="ts">defineProps<{amount:number}>();</script><template><p>{{amount}}</p></template>'};
  const first=await analyze(files);assert.ok(!first.obligations.some(g=>['VUE_NATIVE_TYPECHECK_ERROR','VUE_NATIVE_CONTEXT_DIAGNOSTIC','VUE_NATIVE_CONTEXT_REQUIRED'].includes(g.reason)),JSON.stringify(first.obligations.filter(g=>g.reason.startsWith('VUE_')).map(g=>[g.path,g.reason,g.details.code])));
  assert.equal(first.facts.filter(f=>f.kind==='COMPONENT_DECLARATION').length,2,'native virtual helpers must not become authored components');
  const forbidden=await analyze({...files,'never-run.cjs':'throw Error("target plugin must never execute")','healthy.json':'{"ready":true}'},{plugins:['./never-run.cjs']});assert.ok(forbidden.obligations.some(g=>g.reason==='VUE_COMPILER_OPTION_UNREGISTERED'));assert.ok(forbidden.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));
  const replay=await analyze(Object.fromEntries(Object.entries(files).reverse()));assert.equal(first.identity,replay.identity);
});

test('external templates and disabled template checking keep scoped obligations and independent evidence',async()=>{
  const result=await analyze({'App.vue':'<script setup lang="ts">const n=1;</script><template src="./missing.html"/>','healthy.json':'{"ready":true}'});
  assert.ok(result.obligations.some(g=>g.path==='App.vue'&&g.reason==='VUE_TEMPLATE_CONTEXT_PROVIDER_REQUIRED'));assert.ok(result.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));
  const disabled=await analyze({'App.vue':'<template><p>Hi</p></template>'},{strictTemplates:true,skipTemplateCodegen:true});assert.ok(disabled.obligations.some(g=>g.reason==='VUE_TEMPLATE_CHECKING_DISABLED'));
  const malformed=await analyze({'App.vue':'<template><p></template>','healthy.json':'{"ready":true}'});assert.ok(malformed.obligations.some(g=>g.reason==='VUE_SFC_PARSE_ERROR'));assert.ok(malformed.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));
});

test('SFC package references use the native module target instead of a false missing resource',async()=>{
  const result=await analyze({'App.vue':'<script setup lang="ts">import {ref} from "vue"; const n=ref(0);</script><template><p>{{n}}</p></template>'});
  assert.ok(result.facts.some(f=>f.kind==='SFC_MODULE_REFERENCE'&&f.target?.startsWith('node_modules/vue/')));
  assert.ok(!result.obligations.some(g=>g.reason==='RESOURCE_UNAVAILABLE'));
});
