'use strict';
// First-party authored native fixture only. Existing revisions are never overwritten.
const fs=require('node:fs'),path=require('node:path');
const {analyze}=require('../../../main/resources/polyglot/architecture/worker.cjs');
const {sha,canonical}=require('../../../main/resources/polyglot/architecture/common.cjs');
(async()=>{
  const out=path.resolve(process.argv[2]);if(fs.existsSync(out))throw Error('FIXTURE_REVISION_ALREADY_EXISTS');
  const input={schema:'architecture-request:m8.4-v1',scope:sha('authored architecture fixture v2'),repository:'urn:fixture:architecture',app:'web',profiles:[{id:'vue',version:'3.5'}],
    context:{sourceSet:'main',variant:'baseline',conditions:{feature:'blue'},resolver:'captured-vfs-v1'},
    files:Object.entries({'index.html':'\ufeff<html>\r\n<img src="./logo.svg"><script src="./missing.js"></script></html>',
      'logo.svg':'<svg xmlns="http://www.w3.org/2000/svg"/>','api.json':'{"openapi":"3.1.0","paths":{"/users":{"get":{"responses":{"200":{"description":"ok"}}}}}}',
      'App.vue':'<script setup>import Card from "./Card.vue"</script><template><Card/></template>','Card.vue':'<template><p>Hi</p></template>'}).map(([name,text])=>({path:name,text,sha256:sha(text),role:'main'}))};
  if(process.argv[3]==='native-module')for(const [name,text] of Object.entries({'Library.vue':'<script setup lang="ts">import {amount} from "./amount";</script><template><p>{{amount}}</p></template>','amount.ts':'export const amount=1;'}))input.files.push({path:name,text,sha256:sha(text),role:'main'});
  const report=await analyze(input,{toolsRoot:'.evo/tools/m845-architecture'});fs.mkdirSync(out,{recursive:true});
  fs.writeFileSync(path.join(out,'input.json'),canonical(input));fs.writeFileSync(path.join(out,'capture.json'),canonical(report));
  console.log(JSON.stringify({documents:report.documents.length,census:report.census.length,facts:report.facts.length,gaps:report.obligations.length,identity:report.identity}));
})().catch(e=>{console.error(e.message);process.exitCode=2;});
