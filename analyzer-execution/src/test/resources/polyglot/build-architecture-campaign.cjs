'use strict';
// Fixed authored contexts and independent labels, sealed before worker execution.
const fs=require('node:fs'),path=require('node:path');
const {libraries}=require('./architecture-fixture.cjs');
const {sha,canonical}=require('../../../main/resources/polyglot/architecture/common.cjs');
const [asset,output,selection='baseline',routingAsset,nuxtAsset,vueAsset]=process.argv.slice(2);if(!asset||!output||fs.existsSync(output)||fs.existsSync(output+'.labels.json'))throw Error('NEW_ARCHITECTURE_CAMPAIGN_REQUIRED');
const contexts={},labels={};
function add(name,files,profiles,packages,expected,extra={}){
  const request={schema:'architecture-request:m8.4-v1',language:'frontendarchitecture',dialect:'m8.4-v1',scope:sha('architecture-authored:'+name),repository:'urn:fixture:architecture:'+name,app:'web',profiles,
    context:{sourceSet:'main',variant:'baseline',resolver:'captured-vfs-v1',conditions:{},...extra},
    files:[...Object.entries(files).map(([path,text])=>({path,text,sha256:sha(text),role:'main'})),...libraries(packages)]};
  contexts[name]={language:'frontendarchitecture',request};labels[name]=expected;
}
function pin(file){const bytes=fs.readFileSync(file);return {path:path.resolve(file),size:bytes.length,digest:sha(bytes)};}
function publish(selected,selectedLabels,replays,assets,occurrences={}){
  const campaign={schema:'authored-native-campaign:m8.4-v1',assets,contexts:selected},encoded=canonical(campaign);if(Buffer.byteLength(encoded)>64*1024*1024)throw Error('ARCHITECTURE_SHARD_WORKER_REQUIRED');
  fs.writeFileSync(output,encoded);fs.writeFileSync(output+'.labels.json',canonical({schema:'architecture-authored-labels:m8.4-v1',expectedContexts:Object.keys(selected).length,expectedLabels:Object.values(selectedLabels).reduce((a,v)=>a+v.length,0),labels:selectedLabels,replays,occurrences}));
  console.log(JSON.stringify({contexts:Object.keys(selected).length,labels:Object.values(selectedLabels).reduce((a,v)=>a+v.length,0),occurrences:Object.values(occurrences).reduce((a,v)=>a+v.length,0),bytes:Buffer.byteLength(encoded),sourceDocuments:Object.values(selected).reduce((a,c)=>a+c.request.files.length,0)}));
}
if(selection==='native-context'){
  if(!routingAsset||!nuxtAsset||!vueAsset)throw Error('NATIVE_CONTEXT_SDK_ASSETS_REQUIRED');
  const page='<!--😀-->\r\n<script setup lang="ts">import {ref} from "vue"; import Card from "../../components/Card.vue"; import {load} from "../../lib/api"; const n=ref(0); const refresh=()=>load("/users");</script><template><Card :amount="n"/><button @click="refresh">{{n}}</button></template>';
  const files={'nuxt.config.ts':'export default {srcDir:"app",app:{baseURL:"/portal/"},ssr:true};',
    'app/pages/users/[id].vue':page,'app/components/Card.vue':'<script setup lang="ts">defineProps<{amount:number}>();</script><template><p>{{amount}}</p></template>',
    'app/lib/api.ts':'export function load(url:string){return fetch(url)}','app/assets/site.css':'p{background:url("./logo.svg")}','app/assets/logo.svg':'<svg/>',
    'backend/api.json':'{"openapi":"3.1.0","paths":{"/users":{"get":{"responses":{"200":{"description":"ok"}}}}}}'};
  const expected=[{kind:'NATIVE_NUXT_CONFIGURATION',path:'nuxt.config.ts',status:'RESOLVED'},{kind:'NATIVE_ROUTE_DECLARATION',path:'app/pages/users/[id].vue',status:'CONDITIONAL'},
    {kind:'COMPONENT_USE',path:'app/pages/users/[id].vue',target:'app/components/Card.vue',status:'RESOLVED'},{kind:'STATE_SERVICE_USE',path:'app/pages/users/[id].vue',status:'RESOLVED'},
    {kind:'NETWORK_REQUEST',path:'app/pages/users/[id].vue',status:'CONDITIONAL'},{ownerName:'refresh',boundaryKinds:['NETWORK_REQUEST']},{kind:'API_OPERATION',path:'backend/api.json',route:'/users'},
    {kind:'STYLE_RESOURCE',path:'app/assets/site.css',target:'app/assets/logo.svg',status:'RESOLVED'},
    ...['VUE_NATIVE_TYPECHECK_ERROR','VUE_NATIVE_CONTEXT_DIAGNOSTIC','EFFECTIVE_FRAMEWORK_CONFIG_REQUIRED','NUXT_CONFIGURATION_EFFECTS_REQUIRED','VUE_NATIVE_CONTEXT_REQUIRED','SOURCE_MAP_SOURCE_VERSION_MISMATCH'].map(forbiddenGap=>({forbiddenGap}))];
  add('native-context',files,[{id:'nuxt',version:'4'},{id:'vue',version:'3.5'}],[],expected,{nativeNuxtConfig:true,nativeVue:true,conditions:{mode:'production'},routeModels:[{framework:'nuxt',directory:'app/pages',config:'nuxt.config.ts'}],compilerOptions:{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',jsx:'preserve',allowJs:true,checkJs:true,strict:true,skipLibCheck:true},vueCompilerOptions:{strictTemplates:true}});
  const modules=path.resolve('.evo/tools/m845-vue-declarations-3.5.22/node_modules'),lock=JSON.parse(fs.readFileSync(path.resolve(modules,'../package-lock.json'),'utf8'));
  contexts['native-context'].request.files=contexts['native-context'].request.files.filter(f=>f.role==='main').concat(libraries(Object.keys(lock.packages).filter(n=>n.startsWith('node_modules/')&&!n.slice(13).includes('/node_modules/')).map(n=>n.slice(13)),modules));
  contexts['native-context-replay']=structuredClone(contexts['native-context']);labels['native-context-replay']=expected;
  const occurrence=(id,document,category,kind,statuses,attributes={},target=null,token=null,minimum=1,maximum=1)=>({id,document,source:{digest:sha(files[document]),size:Buffer.byteLength(files[document])},category,kind,statuses,attributes,target,range:token?{start:files[document].indexOf(token),end:files[document].indexOf(token)+token.length}:null,minimum,maximum});
  const independent=[occurrence('component','app/pages/users/[id].vue','F02','COMPONENT_USE',['RESOLVED'],{},'app/components/Card.vue','<Card :amount="n"/>'),
    occurrence('state','app/pages/users/[id].vue','F04','STATE_SERVICE_USE',['RESOLVED'],{api:'ref'},null,'ref(0)'),
    occurrence('request','app/pages/users/[id].vue','F05','NETWORK_REQUEST',['CONDITIONAL'],{endpointDigest:sha('/users')},null,'load("/users")'),
    occurrence('route','app/pages/users/[id].vue','F03','NATIVE_ROUTE_DECLARATION',['CONDITIONAL'],{basePath:'/portal/'}),
    occurrence('configuration','nuxt.config.ts','F10','NATIVE_NUXT_CONFIGURATION',['RESOLVED'],{baseURL:'/portal/'}),
    occurrence('contract','backend/api.json','F05','API_OPERATION',['RESOLVED'],{method:'GET',route:'/users'}),
    occurrence('asset','app/assets/site.css','F07','STYLE_RESOURCE',['RESOLVED'],{},'app/assets/logo.svg'),
    occurrence('no-card-request','app/components/Card.vue','F05','NETWORK_REQUEST',['RESOLVED','CONDITIONAL'],{},null,null,0,0)];
  publish(contexts,labels,[['native-context','native-context-replay']],{architecture:pin(asset),'architecture-routing':pin(routingAsset),'architecture-nuxt':pin(nuxtAsset),'architecture-vue':pin(vueAsset)},Object.fromEntries(Object.keys(contexts).map(name=>[name,independent])));process.exit(0);
}
add('html',{'index.html':'<script type="module" src="./main.ts"></script><iframe src="./embedded.html"></iframe>','main.ts':'export const run=()=>fetch("/users");','embedded.html':'<p>Embedded</p>'},[{id:'html',version:'html5'}],[],[{kind:'RESOURCE_REFERENCE',target:'main.ts'},{kind:'NETWORK_REQUEST'},{kind:'EMBEDDED_SURFACE'}]);
add('react',{'App.tsx':'import {useState as state} from "react"; export function Card(){return <p>Hi</p>} export function App(){const [n]=state(1);return <Card/>}'},[{id:'react',version:'19'}],['@types/react','csstype'],[{kind:'STATE_SERVICE_USE'},{kind:'COMPONENT_USE'}]);
add('vue',{'App.vue':'<script setup>import Card from "./Card.vue"</script><template><Card/></template>','Card.vue':'<template><p>Hi</p></template>'},[{id:'vue',version:'3.5'}],[],[{kind:'COMPONENT_USE',target:'Card.vue'},{kind:'COMPONENT_DECLARATION'}]);
add('angular',{'app.ts':'import {Component} from "@angular/core"; @Component({selector:"demo-child",standalone:true,template:`<p>Child</p>`}) export class Child {} @Component({selector:"demo-app",standalone:true,imports:[Child],template:`<demo-child/>`}) export class App {}'},[{id:'angular',version:'20.3'}],['@angular/core','rxjs'],[{kind:'COMPONENT_USE',binding:'ngtsc-template-checker'},{kind:'ANGULAR_DECLARATION'}],{compilerOptions:{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',strict:true,experimentalDecorators:true,skipLibCheck:true},angularCompilerOptions:{strictTemplates:true}});
add('svelte',{'App.svelte':'<script>import Card from "./Card.svelte"; let visible=true;</script>{#if visible}<Card/>{/if}','Card.svelte':'<p>Hi</p>'},[{id:'svelte',version:'5'}],[],[{kind:'COMPONENT_USE',target:'Card.svelte'},{kind:'TEMPLATE_GUARD'}]);
add('astro',{'src/pages/index.astro':'---\nimport Card from "../components/Card.astro";\n---\n<Card client:load/>','src/components/Card.astro':'<p>Hi</p>'},[{id:'astro',version:'2.13'}],[],[{kind:'COMPONENT_USE',target:'src/components/Card.astro'},{kind:'HYDRATION_BOUNDARY'},{kind:'DECLARED_ROUTE',route:'/'}]);
add('next',{'app/(shop)/users/[id]/page.tsx':'"use client"; export default function Page(){return <p>User</p>}','app/layout.tsx':'export default function Layout(){return <main/>}'},[{id:'next',version:'15'},{id:'react',version:'19'}],['@types/react','csstype'],[{kind:'DECLARED_ROUTE',route:'/users/[id]'},{kind:'EXECUTION_DIRECTIVE'}]);
add('nuxt',{'app/pages/users/[id].vue':'<script setup>import Card from "../../components/Card.vue"</script><template><Card/></template>','app/components/Card.vue':'<template><p>Hi</p></template>'},[{id:'nuxt',version:'4'},{id:'vue',version:'3.5'}],[],[{kind:'DECLARED_ROUTE',route:'/users/[id]'},{kind:'COMPONENT_USE',target:'app/components/Card.vue'}]);
add('sveltekit',{'src/routes/users/[id]/+page.svelte':'<script>import Card from "../../../lib/Card.svelte";</script><Card/>','src/lib/Card.svelte':'<p>Hi</p>'},[{id:'sveltekit',version:'2'},{id:'svelte',version:'5'}],[],[{kind:'DECLARED_ROUTE',route:'/users/[id]'},{kind:'COMPONENT_USE',target:'src/lib/Card.svelte'}]);
add('remix',{'app/routes/users.$id.tsx':'export async function loader(){return fetch("/users")} export default function User(){return <p>User</p>}'},[{id:'remix',version:'7'},{id:'react',version:'19'}],['@types/react','csstype'],[{kind:'DECLARED_ROUTE',route:'/users/$id'},{kind:'NETWORK_REQUEST'}]);
add('schemas',{'api.json':'{"openapi":"3.1.0","paths":{"/users":{"get":{"responses":{"200":{"description":"ok"}}}}}}','schema.graphql':'type Query { user: String } query User { user }','echo.proto':'syntax="proto3"; package demo; message Request { string text=1; } service Echo { rpc Call(Request) returns (Request); }','query.sql':'SELECT * FROM accounting.users;','config.yaml':'services:\n  api:\n    image: example/app\n','infra.tf':'resource "demo" "service" { name="demo" }','Dockerfile':'FROM alpine:3.22\nCOPY app /app\n','event.json':'{"type":"record","name":"Event","namespace":"demo","fields":[{"name":"id","type":"string"}]}'},[],[],[{kind:'API_OPERATION'},{kind:'GRAPHQL_OPERATION'},{kind:'PROTO_DECLARATION'},{kind:'SQL_TABLE_ACCESS'},{kind:'DECLARED_SERVICE'},{kind:'HCL_DECLARATION'},{kind:'CONTAINER_IMAGE'},{kind:'AVRO_SCHEMA'}],{sqlDialect:'Postgresql'});
add('vue-shadow',{'App.vue':'<script setup>import Card from "./Card.vue";const items=[];</script><template><div v-for="Card in items"><Card/></div></template>','Card.vue':'<template><p>Hi</p></template>'},[{id:'vue',version:'3.5'}],[],[{forbiddenKind:'COMPONENT_USE',target:'Card.vue',status:'RESOLVED'},{gap:'TEMPLATE_LOCAL_COMPONENT_BINDING'}]);
add('svelte-shadow',{'App.svelte':'<script>import Card from "./Card.svelte"; let items=[];</script>{#each items as Card}<Card/>{/each}','Card.svelte':'<p>Hi</p>'},[{id:'svelte',version:'5'}],[],[{forbiddenKind:'COMPONENT_USE',target:'Card.svelte',status:'RESOLVED'},{gap:'TEMPLATE_LOCAL_COMPONENT_BINDING'}]);
add('proto-import',{'schema/model.proto':'syntax="proto3"; package demo; message Request { string text=1; }','api.proto':'syntax="proto3"; package demo; import "schema/model.proto"; service Echo { rpc Call(Request) returns (Request); }'},[],[],[{kind:'PROTO_TYPE_REFERENCE',target:'schema/model.proto#demo.Request'}]);
add('react-type-only',{'App.tsx':'import type {Card} from "./Card";export const App=()=> <Card/>;','Card.tsx':'export function Card(){return <p>Hi</p>}'},[{id:'react',version:'19'}],['@types/react','csstype'],[{forbiddenKind:'COMPONENT_USE',status:'RESOLVED'},{gap:'COMPONENT_BINDING_UNAVAILABLE'}]);
contexts['react-replay']=JSON.parse(JSON.stringify(contexts.react));labels['react-replay']=labels.react;
add('vue-shadow-replay',{},[],[],[]);contexts['vue-shadow-replay']=structuredClone(contexts['vue-shadow']);labels['vue-shadow-replay']=labels['vue-shadow'];
add('schema-conflict',{'a.json':'{"$id":"https://schema.invalid/shared","type":"string"}','b.json':'{"$id":"https://schema.invalid/shared","type":"number"}','use.json':'{"$ref":"https://schema.invalid/shared"}'},[],[],[{forbiddenKind:'SCHEMA_REFERENCE',status:'RESOLVED'},{gap:'SCHEMA_BASE_URI_AMBIGUOUS'}]);
add('directive-invalid',{'late.ts':'const n=1; "use client";','nested.ts':'export function run(){"use client";return 1;}','conflict.ts':'"use client";"use server"; export const n=1;'},[{id:'next',version:'15'}],[],[{forbiddenKind:'EXECUTION_DIRECTIVE',status:'RESOLVED'},{gap:'CONFLICTING_EXECUTION_DIRECTIVES'}]);
add('graphql-namespaces',{'wrong.graphql':'type Query { user: String } query User { ...Query }','ambiguous.graphql':'type Query { user: String } query User { ...Shared } fragment Shared on Query { user } fragment Shared on Query { user }'},[],[],[{forbiddenKind:'GRAPHQL_FRAGMENT_REFERENCE',status:'RESOLVED'},{gap:'GRAPHQL_FRAGMENT_AMBIGUOUS'},{gap:'GRAPHQL_FRAGMENT_UNAVAILABLE'}]);
add('floating-config',{'compose.json':'{"services":{"api":{"build":{"args":{"RATIO":0.5}}}}}','cycle.yaml':'services:\n  api:\n    build: &loop {again: *loop}\n','healthy.json':'{"ready":true}'},[],[],[{kind:'SERVICE_CONFIG_REFERENCE'},{gap:'CYCLIC_CONFIG_ALIAS'}]);
add('state-unknown-tuple',{'App.tsx':'import {useState} from "react"; export function App(){return useState(1)}'},[{id:'react',version:'999'}],['@types/react','csstype'],[{forbiddenKind:'STATE_SERVICE_USE',status:'RESOLVED'},{gap:'STATE_SERVICE_PROFILE_REQUIRED'}]);
add('script-scope',{'A.svelte':'<script>function localRequest(url){return fetch(url)}</script><p>A</p>','B.svelte':'<script>localRequest("/users");</script><p>B</p>'},[{id:'svelte',version:'5'}],[],[{forbiddenKind:'NETWORK_REQUEST'},{gap:'NATIVE_TYPECHECK_ERROR'}]);
add('extension-mapping',{'bundle.min.js':'(()=>{})();','main.ts':'import {createSignal} from "solid-js"; export const value=createSignal(1);'},[],[],[{kind:'FRAMEWORK_CANDIDATE',status:'CONDITIONAL'},{gap:'FRAMEWORK_EXTENSION_PROFILE_REQUIRED'},{gap:'POSSIBLE_MINIFIED_SOURCE_MAPPING_REQUIRED'}]);
add('target-low',{'target.ts':'export const last=[1].findLast(n=>n>0);'},[],[],[{gap:'NATIVE_TYPECHECK_ERROR'}],{compilerOptions:{target:'ES2015',module:'ESNext',moduleResolution:'Bundler',strict:true}});
add('dialect-redaction',{'schema.json':'{"$schema":"https://user:private-password@schema.invalid/private?token=private-password"}','api.json':'{"openapi":"3.1.private-password","paths":{"/users":{"get":{"responses":{}}}}}'},[],[],[{forbiddenKind:'API_OPERATION'},{gap:'JSON_SCHEMA_DIALECT_UNSUPPORTED'},{gap:'OPENAPI_VERSION_UNSUPPORTED'}]);
add('graphql-context',{'schema.contract':'type Query { user: String }','fragment.contract':'fragment Shared on Query { user }','client.graphql':'{ ...Shared }'},[],[],[{kind:'GRAPHQL_SCHEMA_BINDING',status:'RESOLVED'},{kind:'GRAPHQL_FRAGMENT_REFERENCE',status:'RESOLVED'}],{graphqlContexts:{'client.graphql':{schema:['schema.contract'],fragments:['fragment.contract']}}});
add('svelte-store',{'App.svelte':'<!--😀-->\r\n<script lang="ts">import {writable} from "svelte/store"; const value=writable(1);</script><p>Hi</p>'},[{id:'svelte',version:'5'}],['svelte','@types/estree','csstype'],[{kind:'STATE_SERVICE_USE',status:'RESOLVED'}]);
add('vue-state',{'App.vue':'<script setup lang="ts">import {ref} from "vue"; const value=ref(1); const wrong:number="bad";</script><template><p>Hi</p></template>'},[{id:'vue',version:'3.5'}],[],[{kind:'STATE_SERVICE_USE',status:'RESOLVED'},{gap:'NATIVE_TYPECHECK_ERROR'}]);
if(selection==='sources'){
  const modules=path.resolve('.evo/tools/m845-vue-declarations-3.5.22/node_modules'),lock=JSON.parse(fs.readFileSync(path.resolve(modules,'../package-lock.json'),'utf8'));
  const own=contexts['vue-state'].request.files.filter(f=>!['dependency','toolchain'].includes(f.role));
  contexts['vue-state'].request.files=own.concat(libraries(Object.keys(lock.packages).filter(p=>p.startsWith('node_modules/')).map(p=>p.slice(13)),modules));
}
add('target-high',{'target.ts':'export const last=[1].findLast(n=>n>0);'},[],[],[{forbiddenGap:'NATIVE_TYPECHECK_ERROR'}],{compilerOptions:{target:'ESNext',module:'ESNext',moduleResolution:'Bundler',strict:true}});
contexts['svelte-store-replay']=structuredClone(contexts['svelte-store']);labels['svelte-store-replay']=labels['svelte-store'];
add('composition-chain',{'App.tsx':'import {useState} from "react";import {request} from "./api";export function App(){const [n]=useState(1);request("/users");return <button>{n}</button>}',
  'api.ts':'export function request(url:string){return fetch(url)}'},[{id:'react',version:'19'}],['@types/react','csstype'],[{ownerName:'App',boundaryKinds:['STATE_SERVICE_USE','NETWORK_REQUEST']},{kind:'COMPONENT_DECLARATION',status:'RESOLVED'}]);
add('inline-surfaces',{'index.html':'<!--😀-->\r\n<script>fetch("/users");</script><style>@import "./base.css";p{background:url("./image.svg")}</style>',
  'App.vue':'<template><p>Hi</p></template><style>@import "./base.css";p{background:url("./image.svg")}</style>',
  'Card.svelte':'<p>Hi</p><style>@import "./base.css";p{background:url("./image.svg")}</style>',
  'base.css':'p{}','image.svg':'<svg/>'},[{id:'html',version:'html5'},{id:'vue',version:'3.5'},{id:'svelte',version:'5'}],[],[
  {kind:'NETWORK_REQUEST',path:'index.html'}, {kind:'STYLE_RESOURCE',path:'index.html',target:'image.svg'},
  {kind:'STYLE_RESOURCE',path:'App.vue',target:'image.svg'},{kind:'STYLE_RESOURCE',path:'Card.svelte',target:'image.svg'}]);
const groups={baseline:['html','react','vue','angular','svelte','astro','next','nuxt','sveltekit','remix','schemas','react-replay'],
  negative:['vue-shadow','svelte-shadow','proto-import','react-type-only','vue-shadow-replay','schema-conflict','directive-invalid','graphql-namespaces','floating-config','state-unknown-tuple','script-scope','extension-mapping','target-low','dialect-redaction'],
  sources:['graphql-context','svelte-store','vue-state','target-high','svelte-store-replay','composition-chain','inline-surfaces']};
if(!groups[selection])throw Error('REGISTERED_ARCHITECTURE_SHARD_REQUIRED');
const selected=Object.fromEntries(groups[selection].map(name=>[name,contexts[name]])),selectedLabels=Object.fromEntries(groups[selection].map(name=>[name,labels[name]]));
const replays=[['react','react-replay'],['vue-shadow','vue-shadow-replay'],['svelte-store','svelte-store-replay']].filter(pair=>pair.every(name=>selected[name]));
publish(selected,selectedLabels,replays,{architecture:pin(asset)});
