'use strict';
// Explicit platform-owned tool root only; application modules/configuration are never required.
const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const {pathToFileURL}=require('node:url');
const {digest,sha}=require('./common.cjs');
async function load(root,routingRoot,nuxtRoot,vueRoot) {
  if(!root)throw Error('ARCHITECTURE_PINNED_TOOL_ROOT_REQUIRED');
  const pin=require('./toolchain.json');
  if(!pin.runtimes.some(r=>r.version===process.versions.node&&r.platform===process.platform))throw Error('ARCHITECTURE_NODE_VERSION_MISMATCH');
  if(sha(fs.readFileSync(path.resolve(root,'package-lock.json')))!==pin.lock||require('./tool-tree.cjs').tree(path.resolve(root,'node_modules')).digest!==pin.tree.digest)throw Error('ARCHITECTURE_TOOL_BYTES_CHANGED');
  const requireTool=createRequire(path.resolve(root,'package.json'));
  const lock=JSON.parse(fs.readFileSync(path.resolve(root,'package-lock.json'),'utf8'));
  const expected={'typescript':'5.9.3','parse5':'7.3.0','postcss':'8.5.6','css-tree':'3.1.0','yaml':'2.9.1','graphql':'16.11.0','protobufjs':'8.8.1','@iarna/toml':'2.2.5','node-sql-parser':'5.3.13','@xmldom/xmldom':'0.9.12','jsonc-parser':'3.3.1','@vue/compiler-sfc':'3.5.22','svelte':'5.39.6','@astrojs/compiler':'2.13.0','@angular/compiler':'20.3.4','@angular/compiler-cli':'20.3.4','hcl2-parser':'1.0.3','dockerfile-ast':'0.7.1','bash-parser':'0.5.0','avsc':'5.7.9'};
  for(const [name,version] of Object.entries(expected))if(lock.packages?.['node_modules/'+name]?.version!==version)throw Error('ARCHITECTURE_TOOL_VERSION_'+name);
  const identities=[];for(const [name,value] of Object.entries(lock.packages||{}))if(name){if(!value.integrity||!value.resolved?.startsWith('https://registry.npmjs.org/'))throw Error('ARCHITECTURE_TOOL_CLOSURE_PIN');identities.push([name,value.version,value.integrity]);}
  const result={};
  for(const name of Object.keys(expected)){
    let resolved=requireTool.resolve(name==='svelte'?'svelte/compiler':name);result[name]=await import(pathToFileURL(resolved));
    if(result[name].default&&name!=='@angular/compiler')result[name]=Object.assign({},result[name].default,result[name]);
    identities.push([name,sha(fs.readFileSync(resolved))]);
  }
  result.protobufDescriptor=requireTool('protobufjs/ext/descriptor');
  result.sourceMap=requireTool('@jridgewell/trace-mapping');result.sourceMapEncoder=requireTool('@jridgewell/gen-mapping');
  if(routingRoot){result.routing=await require('./routing.cjs').load(routingRoot);identities.push(['native-routing',result.routing.identity]);}
  if(nuxtRoot){result.nuxtConfig=require('./nuxt-config.cjs').load(nuxtRoot);identities.push(['native-nuxt-configuration',result.nuxtConfig.identity]);}
  if(vueRoot){result.vueContext=require('./vue-context.cjs').load(vueRoot);identities.push(['native-vue-context',result.vueContext.identity]);}
  const extractor=fs.readdirSync(__dirname).filter(name=>name.endsWith('.cjs')).sort().map(name=>[name,sha(fs.readFileSync(path.join(__dirname,name)))]);
  result.identity=digest(['architecture-tools:m8.4-v1',pin,identities,extractor]);return result;
}
module.exports={load};
