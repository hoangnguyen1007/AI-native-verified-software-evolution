'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {sha}=require('../../../main/resources/polyglot/architecture/common.cjs');
let tools;
const fixedTools=()=>tools??=require('../../../main/resources/polyglot/architecture/tools.cjs').load('.evo/tools/m845-architecture','.evo/tools/m845-native-routing-v2','.evo/tools/m845-nuxt-schema-v1');
async function analyze(config,files={},directory='site/views'){
  const input={schema:'architecture-request:m8.4-v1',scope:sha('authored native Nuxt configuration'),repository:'urn:fixture:nuxt-context',app:'web',profiles:[{id:'nuxt',version:'4'},{id:'vue',version:'3.5'}],context:{nativeNuxtConfig:true,conditions:{mode:'production'},routeModels:[{framework:'nuxt',directory,config:'nuxt.config.ts'}]},files:Object.entries({'nuxt.config.ts':config,'site/views/users/[id].vue':'<template><p>User</p></template>',...files}).map(([path,text])=>({path,text,sha256:sha(text),role:'main'}))};
  return require('../../../main/resources/polyglot/architecture/worker.cjs').analyze(input,{tools:await fixedTools()});
}
test('native Nuxt schema qualifies captured source directory and base URL without target evaluation',async()=>{
  const result=await analyze('export default defineNuxtConfig({srcDir:"site",dir:{pages:"views"},app:{baseURL:"/shop/"},ssr:true});');
  const route=result.facts.find(f=>f.kind==='NATIVE_ROUTE_DECLARATION');assert.ok(route);assert.equal(route.attributes.basePath,'/shop/');assert.equal(route.attributes.effectiveConfig,'NATIVE_VALIDATED_STATIC_SOURCE');
  assert.ok(route.supports.some(p=>p.path==='nuxt.config.ts'));assert.ok(result.native.some(n=>n.engine==='@nuxt/schema'&&n.version==='4.6.3'));
  assert.ok(!result.obligations.some(g=>g.reason==='EFFECTIVE_FRAMEWORK_CONFIG_REQUIRED'));assert.equal(result.qualified,false);
});
test('Nuxt source root conflicts, escaping paths and opaque effects retain healthy resources',async()=>{
  for(const config of ['export default {srcDir:"../outside"};','export default {srcDir:"other"};','export default {srcDir:"site",dir:{pages:"views"},modules:["target-plugin-never-run"]};']){
    const result=await analyze(config,{'healthy.json':'{"ready":true}'});
    assert.ok(result.facts.some(f=>f.path==='healthy.json'&&f.kind==='CONFIG_KEY'));assert.ok(result.obligations.some(g=>['FRAMEWORK_PATH_OUTSIDE_CAPTURE','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT','NUXT_CONFIGURATION_EFFECTS_REQUIRED'].includes(g.reason)));
    assert.ok(!result.facts.some(f=>f.kind==='NATIVE_ROUTE_DECLARATION'&&f.attributes.effectiveConfig==='NATIVE_VALIDATED_STATIC_SOURCE'));
  }
});
test('native Nuxt configuration ignores ambient environment and replays the captured world',async()=>{
  const before=process.env.NUXT_APP_BASE_URL;
  try{process.env.NUXT_APP_BASE_URL='/ambient-private-world/';const a=await analyze('export default {srcDir:"site",dir:{pages:"views"},app:{baseURL:"/captured/"}};');process.env.NUXT_APP_BASE_URL='/changed-ambient-world/';const b=await analyze('export default {srcDir:"site",dir:{pages:"views"},app:{baseURL:"/captured/"}};');
    assert.equal(a.identity,b.identity);assert.equal(a.facts.find(f=>f.kind==='NATIVE_ROUTE_DECLARATION').attributes.basePath,'/captured/');assert.ok(!JSON.stringify(a).includes('ambient-private-world'));
  }finally{if(before===undefined)delete process.env.NUXT_APP_BASE_URL;else process.env.NUXT_APP_BASE_URL=before;}
});
