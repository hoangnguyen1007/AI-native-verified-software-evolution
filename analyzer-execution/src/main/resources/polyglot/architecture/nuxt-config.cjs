'use strict';
const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const {sha,digest}=require('./common.cjs');
function load(root){
  const pin=require('./nuxt-toolchain.json');root=path.resolve(root);
  if(!pin.runtimes.some(r=>r.version===process.versions.node&&r.platform===process.platform))throw Error('NUXT_TOOL_RUNTIME_REQUIRED');
  const lockBytes=fs.readFileSync(path.join(root,'package-lock.json')),lock=JSON.parse(lockBytes);
  if(sha(lockBytes)!==pin.lock||require('./tool-tree.cjs').tree(path.join(root,'node_modules')).digest!==pin.tree.digest)throw Error('NUXT_TOOL_BYTES_CHANGED');
  for(const [name,version] of Object.entries(pin.packages))if(lock.packages?.['node_modules/'+name]?.version!==version)throw Error('NUXT_TOOL_VERSION_CHANGED');
  for(const [name,pkg] of Object.entries(lock.packages))if(name&&(!pkg.integrity||!pkg.resolved?.startsWith('https://registry.npmjs.org/'))){
    const owner=pin.bundled?.[name],parent=owner&&lock.packages[owner],relative=owner&&name.slice(owner.length+'/node_modules/'.length);
    if(!owner||!name.startsWith(owner+'/node_modules/')||!pkg.inBundle||!parent?.integrity||!parent.resolved?.startsWith('https://registry.npmjs.org/')||!parent.bundleDependencies?.includes(relative))throw Error('NUXT_TOOL_CLOSURE_PIN');
  }
  return {identity:digest(pin),resolve(captured,config,mode,namespace=captured){
    // Separate environment and filesystem permissions prevent ambient configuration reads.
    const env={NODE_ENV:mode==='development'?'development':'production',HOME:namespace,USERPROFILE:namespace,TEMP:namespace,TMP:namespace};
    if(process.platform==='win32'&&process.env.SystemRoot)env.SystemRoot=process.env.SystemRoot;
    const raw=execFileSync(process.execPath,['--permission','--allow-fs-read='+root,'--allow-fs-read='+namespace,'--allow-fs-read='+__dirname,path.join(__dirname,'nuxt-schema-runner.cjs'),root],{input:JSON.stringify({root:captured,namespace,config,mode}),encoding:'utf8',env,cwd:captured,timeout:30000,maxBuffer:1024*1024,windowsHide:true,stdio:['pipe','pipe','pipe']});
    return JSON.parse(raw);
  }};
}
module.exports={load};
