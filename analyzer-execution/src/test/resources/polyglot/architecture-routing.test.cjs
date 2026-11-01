'use strict';
const {test,before}=require('node:test'),assert=require('node:assert/strict');
const routing=require('../../../main/resources/polyglot/architecture/routing.cjs');
const {create,sha}=require('../../../main/resources/polyglot/architecture/common.cjs');
let native;before(async()=>{native=await routing.load('.evo/tools/m845-native-routing-v2');});
async function analyze(files,model,overrides={}){
  const input={schema:'architecture-request:m8.4-v1',scope:sha('authored routing context'),repository:'urn:fixture:routing',app:'web',profiles:[{id:model.framework,version:{next:'15',nuxt:'4',sveltekit:'2',remix:'7'}[model.framework]}],context:{conditions:{},routeModels:[model]},files:Object.entries(files).map(([path,text])=>({path,text,role:'main',sha256:sha(text)}))};
  const state=create(input,{identity:sha('routing fixture'),routing:{...native,...overrides}});await routing.analyze(state);return state;
}
test('Next native collection excludes private folders and preserves parallel/group source identity',async()=>{
  const s=await analyze({'app/(shop)/users/[id]/page.tsx':'export default function Page(){return null}','app/@modal/(.)photo/[id]/page.tsx':'export default function Modal(){return null}','app/_private/page.tsx':'throw Error("never execute")','app/(shop)/layout.tsx':'export default function Layout(){return null}'},{framework:'next',directory:'app',mode:'app'});
  assert.ok(s.facts.some(f=>f.kind==='NATIVE_ROUTE_DECLARATION'&&f.path==='app/(shop)/users/[id]/page.tsx'&&f.attributes.route==='/users/[id]'));
  assert.ok(!s.facts.some(f=>f.path==='app/_private/page.tsx'));
  assert.ok(s.facts.some(f=>f.path==='app/@modal/(.)photo/[id]/page.tsx'&&f.attributes.route.includes('(.)photo')));
  assert.ok(s.facts.some(f=>f.kind==='NATIVE_ROUTE_LAYOUT'&&f.path.endsWith('/layout.tsx')));
  assert.ok(s.facts.every(f=>f.status==='CONDITIONAL'&&f.attributes.reachable==='NOT_ESTABLISHED'));
  assert.ok(s.obligations.some(g=>g.reason==='EFFECTIVE_FRAMEWORK_CONFIG_REQUIRED'));
});
test('Next pages SDK removes index and keeps API file bindings without evaluating modules',async()=>{
  const s=await analyze({'pages/users/index.tsx':'throw Error("must not evaluate")','pages/api/users.ts':'export default function handler(){}','pages/_app.tsx':'export default function App(){return null}'},{framework:'next',directory:'pages',mode:'pages'});
  assert.ok(s.facts.some(f=>f.path==='pages/users/index.tsx'&&f.attributes.route==='/users'));
  assert.ok(s.facts.some(f=>f.path==='pages/api/users.ts'&&f.attributes.route==='/api/users'));
});
test('Nuxt actual unrouting engine expands optional parameters and retains nested children',async()=>{
  const s=await analyze({'app/pages/index.vue':'<template>home</template>','app/pages/users/[[id]].vue':'<template>user</template>','app/pages/users.vue':'<NuxtPage />'},{framework:'nuxt',directory:'app/pages'});
  assert.ok(s.facts.some(f=>f.path==='app/pages/index.vue'&&f.attributes.route==='/'));
  assert.ok(s.facts.some(f=>f.path==='app/pages/users/[[id]].vue'&&f.attributes.route.includes(':id')&&f.attributes.parent!==null));
  assert.ok(s.native.some(n=>n.engine==='unrouting'&&n.version==='0.3.1'));
});
test('SvelteKit manifest binds page, layout, endpoint and matcher without loading target JS',async()=>{
  const s=await analyze({'src/routes/+layout.svelte':'<slot />','src/routes/(group)/users/[id=integer]/+page.svelte':'<p>user</p>','src/routes/(group)/users/[id=integer]/+page.server.ts':'throw Error("never run server loader")','src/routes/api/+server.ts':'throw Error("never run endpoint")','src/params/integer.ts':'throw Error("never run matcher")'},{framework:'sveltekit',directory:'src/routes'});
  assert.ok(s.facts.some(f=>f.path.endsWith('+page.svelte')&&f.attributes.parameters.some(p=>p.name==='id'&&p.matcher==='integer')));
  assert.ok(s.facts.some(f=>f.path==='src/routes/api/+server.ts'&&f.attributes.role==='endpoint'));
  assert.ok(s.facts.some(f=>f.path==='src/params/integer.ts'&&f.kind==='NATIVE_ROUTE_MATCHER'));
});
test('React Router native file convention retains layout, index and dynamic path hierarchy',async()=>{
  const previous=globalThis.__reactRouterAppDirectory;
  const s=await analyze({'app/root.tsx':'throw Error("never run root")','app/routes/_auth.tsx':'export default function Auth(){return null}','app/routes/_auth.login.tsx':'export default function Login(){return null}','app/routes/users.$id.tsx':'export default function User(){return null}','app/routes/_index.tsx':'export default function Index(){return null}'},{framework:'remix',directory:'app',routesDirectory:'routes'});
  assert.ok(s.facts.some(f=>f.path==='app/routes/users.$id.tsx'&&f.attributes.route==='users/:id'));
  assert.ok(s.facts.some(f=>f.path==='app/routes/_index.tsx'&&f.attributes.index===true));
  assert.ok(s.facts.some(f=>f.path==='app/routes/_auth.login.tsx'&&f.attributes.parent!==null));
  assert.equal(globalThis.__reactRouterAppDirectory,previous);
});
test('missing native SDK is a typed obligation and cannot be replaced by a filename assertion',async()=>{
  const s=create({schema:'architecture-request:m8.4-v1',scope:sha('missing SDK'),repository:'x',app:'web',profiles:[{id:'next',version:'15'}],context:{routeModels:[{framework:'next',directory:'app',mode:'app'}]},files:[{path:'app/page.tsx',text:'export {}',sha256:sha('export {}'),role:'main'}]},{identity:sha('no routing SDK')});
  await routing.analyze(s);assert.equal(s.facts.length,0);assert.ok(s.obligations.some(g=>g.reason==='PINNED_NATIVE_ROUTE_TOOLS_REQUIRED'));
});
test('traversal and unregistered options never expand the captured namespace',async()=>{
  for(const model of [{framework:'next',directory:'../outside',mode:'app'},{framework:'next',directory:'app',mode:'app',loadConfig:true},{framework:'next',directory:'app',mode:'app',extensions:['exe']}]){
    const s=await analyze({'app/page.tsx':'export {}'},model);assert.equal(s.facts.length,0);assert.ok(s.obligations.some(g=>g.reason==='NATIVE_ROUTE_MODEL_UNAVAILABLE'&&g.details.diagnosticDigest?.startsWith('sha256:')));
  }
});
test('invalid SvelteKit matcher context is withheld instead of inventing a parameter binding',async()=>{
  const s=await analyze({'src/routes/[id=missing]/+page.svelte':'<p>invalid matcher</p>'},{framework:'sveltekit',directory:'src/routes'});
  assert.equal(s.facts.length,0);assert.ok(s.obligations.some(g=>g.reason==='NATIVE_ROUTE_MODEL_UNAVAILABLE'));
});
test('React Router native failure restores process context and does not evaluate a config file',async()=>{
  const prior=globalThis.__reactRouterAppDirectory;
  const s=await analyze({'app/routes/a.tsx':'export {}','react-router.config.ts':'throw Error("must never run config")'},{framework:'remix',directory:'app'});
  assert.equal(s.facts.length,0);assert.ok(s.obligations.some(g=>g.reason==='NATIVE_ROUTE_MODEL_UNAVAILABLE'));assert.equal(globalThis.__reactRouterAppDirectory,prior);
});
test('native route facts replay independently of source input ordering or temporary worker path',async()=>{
  const files={'app/pages/z.vue':'<p>z</p>','app/pages/a.vue':'<p>a</p>','app/pages/a/index.vue':'<p>index</p>'},model={framework:'nuxt',directory:'app/pages'};
  const a=await analyze(files,model),b=await analyze(Object.fromEntries(Object.entries(files).reverse()),model);
  const projection=s=>JSON.stringify([s.facts,s.obligations,s.native]);assert.equal(projection(a),projection(b));assert.ok(!projection(a).includes('evo-native-routes-'));
});
test('actual worker wires the pinned native recipe and does not duplicate a convention hint',async()=>{
  const s=await require('../../../main/resources/polyglot/architecture/worker.cjs').analyze({schema:'architecture-request:m8.4-v1',scope:sha('wired fixture'),repository:'urn:fixture:wired-routing',app:'web',profiles:[{id:'nuxt',version:'4'},{id:'vue',version:'3.5'}],context:{routeModels:[{framework:'nuxt',directory:'app/pages'}]},files:[{path:'app/pages/index.vue',text:'<template><p>home</p></template>',role:'main',sha256:sha('<template><p>home</p></template>')}]},{toolsRoot:'.evo/tools/m845-architecture',routingRoot:'.evo/tools/m845-native-routing-v2'});
  assert.ok(s.facts.some(f=>f.kind==='NATIVE_ROUTE_DECLARATION'&&f.attributes.route==='/'));
  assert.ok(!s.facts.some(f=>f.kind==='DECLARED_ROUTE'));
  assert.ok(!s.obligations.some(g=>g.reason==='NATIVE_ROUTE_MANIFEST_REQUIRED'));
  assert.ok(s.obligations.some(g=>g.reason==='EFFECTIVE_FRAMEWORK_CONFIG_REQUIRED'));
  assert.equal(s.qualified,false);
});
test('captured literal configuration controls native route input and keeps source ownership',async()=>{
  const next=await analyze({'next.config.ts':'export default {basePath:"/shop",pageExtensions:["page.tsx"]} satisfies import("next").NextConfig;','app/users/page.page.tsx':'export default function Page(){return null}','app/ignored/page.tsx':'export default function Ignored(){return null}'},{framework:'next',directory:'app',mode:'app',config:'next.config.ts'});
  const page=next.facts.find(f=>f.kind==='NATIVE_ROUTE_DECLARATION');assert.ok(page);assert.equal(page.path,'app/users/page.page.tsx');assert.equal(page.attributes.publicRoute,'/shop/users');assert.ok(page.supports.some(s=>s.path==='next.config.ts'));assert.ok(next.facts.some(f=>f.kind==='FRAMEWORK_CONFIGURATION'&&f.status==='RESOLVED'));
  const kit=await analyze({'svelte.config.js':'export default {kit:{files:{routes:"views"},paths:{base:"/portal"}}};','views/+page.svelte':'<p>Home</p>'},{framework:'sveltekit',directory:'views',config:'svelte.config.js'});
  assert.ok(kit.facts.some(f=>f.path==='views/+page.svelte'&&f.attributes.basePath==='/portal'));assert.ok(!kit.obligations.some(g=>g.reason==='NATIVE_ROUTE_MODEL_UNAVAILABLE'));
});
test('computed or effectful config cannot masquerade as a complete captured static model',async()=>{
  const s=await analyze({'next.config.js':'const config={basePath:"/wrong"}; config.basePath=process.env.BASE; export default config;','app/page.tsx':'export default function Page(){return null}'},{framework:'next',directory:'app',mode:'app',config:'next.config.js'});
  assert.ok(s.facts.some(f=>f.kind==='NATIVE_ROUTE_DECLARATION'&&!f.attributes.publicRoute));assert.ok(s.obligations.some(g=>g.reason==='CONFIGURATION_EFFECTS_UNMODELED'));assert.ok(!s.facts.some(f=>f.kind==='FRAMEWORK_CONFIGURATION'&&f.status==='RESOLVED'));
});
test('React Router declaration helpers use the native SDK without evaluating a target routes module',async()=>{
  const s=await analyze({'app/root.tsx':'throw Error("never execute root")','app/routes.ts':'import {route as page,index,layout,prefix} from "@react-router/dev/routes"; export default [layout("layout.tsx",[index("home.tsx"),...prefix("account",[page("users/:id","user.tsx")])])];','app/layout.tsx':'export default function Layout(){return null}','app/home.tsx':'export default function Home(){return null}','app/user.tsx':'export default function User(){return null}'},{framework:'remix',directory:'app',routesConfig:'app/routes.ts'});
  const user=s.facts.find(f=>f.path==='app/user.tsx'&&f.kind==='NATIVE_ROUTE_DECLARATION');assert.ok(user);assert.equal(user.attributes.route,'account/users/:id');assert.equal(user.attributes.parent,'app/layout.tsx');assert.ok(user.supports.some(s=>s.path==='app/routes.ts'));
});
test('configuration paths stay in the captured app namespace before any native filesystem scan',async()=>{
  let scanned=false;
  const unsafe=await analyze({'svelte.config.js':'export default {kit:{files:{params:"../outside"}}};','src/routes/+page.svelte':'<p>Home</p>'},{framework:'sveltekit',directory:'src/routes',config:'svelte.config.js'},{kit:()=>{scanned=true;return {};}});
  assert.equal(scanned,false);assert.ok(unsafe.obligations.some(g=>g.reason==='FRAMEWORK_PATH_OUTSIDE_CAPTURE'));
  const nested=await analyze({'apps/site/svelte.config.js':'export default {kit:{files:{routes:"views"},paths:{base:"/site"}}};','apps/site/views/+page.svelte':'<p>Home</p>'},{framework:'sveltekit',directory:'apps/site/views',config:'apps/site/svelte.config.js'});
  assert.ok(nested.facts.some(f=>f.path==='apps/site/views/+page.svelte'&&f.attributes.basePath==='/site'));
});
test('Module Federation options are natively validated while remote modules remain undeployed declarations',async()=>{
  const files={'federation.config.js':'export default {name:"shell",exposes:{"./Card":"./Card.tsx"},remotes:{profile:"profile@https://user:private-password@remote.invalid/entry.js"},shared:{react:{singleton:true}}};','Card.tsx':'throw Error("never execute component")','consumer.ts':'export const page=()=>import("profile/Profile");'};
  const input={schema:'architecture-request:m8.4-v1',scope:sha('federation fixture'),repository:'urn:fixture:federation',app:'shell',profiles:[],context:{federationModels:[{profile:'webpack5',config:'federation.config.js'}]},files:Object.entries(files).map(([path,text])=>({path,text,sha256:sha(text),role:'main'}))};
  const s=create(input,{routing:native});await require('../../../main/resources/polyglot/architecture/federation.cjs').analyze(s);
  assert.ok(s.facts.some(f=>f.kind==='FEDERATION_EXPOSE'&&f.target==='Card.tsx'));assert.ok(s.facts.some(f=>f.kind==='FEDERATED_MODULE_REFERENCE'&&f.attributes.remote==='profile'));assert.ok(s.facts.some(f=>f.kind==='FEDERATION_SHARED_DEPENDENCY'&&f.attributes.name==='react'));assert.ok(!JSON.stringify(s.facts).includes('private-password'));
});
