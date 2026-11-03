'use strict';
// This adapter runs in a registered worker. Host tests use platform-authored input only.
const {load}=require('./tools.cjs');
const {create,digest,canonical,sha}=require('./common.cjs');
const resources=require('./resources.cjs');
const javascript=require('./javascript.cjs');
const frameworks=require('./frameworks.cjs');
const structured=require('./structured.cjs');
const census=require('./census.cjs');
const dsl=require('./dsl.cjs');
async function analyze(input,options={}) {
  const tools=options.tools||await load(options.toolsRoot,options.routingRoot,options.nuxtRoot,options.vueRoot,options.bridgeRoot);
  const s=create(input,tools);
  const expected=census.enumerate(s);
  frameworks.validate(s);
  for(const file of s.files.values()){
    s.documents.push({path:file.path,content:{digest:file.sha256,size:file.size},role:file.role||'unknown',encoding:file.text===null?null:'UTF-8'});
    if(file.text===null){if(!structured.binary(s,file))s.gap(file,'F12','BINARY_METADATA_PROVIDER_REQUIRED');continue;}
    if(/\.html?$/.test(file.path))resources.html(s,file);
    if(/\.(?:css|scss|sass|less)$/.test(file.path))resources.css(s,file);
  }
  require('./vue-context.cjs').analyze(s);
  s.scriptViews=await frameworks.scriptInputs(s);
  javascript.analyze(s);
  structured.analyze(s);
  await require('./bridge-models.cjs').analyze(s);
  require('./templates.cjs').analyze(s);
  dsl.analyze(s);
  await frameworks.analyze(s);
  await require('./federation.cjs').analyze(s);
  require('./source-maps.cjs').analyze(s);
  // The core reader navigates native records through captured documents, never directories.
  if(s.native.some(row=>!s.files.has(row.path)))throw Error('ARCHITECTURE_NATIVE_SCOPE');
  const dispositions=census.dispositions(s);
  const sorted=rows=>rows.sort((a,b)=>canonical(a)<canonical(b)?-1:canonical(a)>canonical(b)?1:0);
  const result={schema:'architecture-capture:m8.4-v2',scope:input.scope,context:input.context,tool:tools.identity,trust:'PRODUCER_ASSERTION',qualified:false,
    inputClosure:digest(['architecture-input:m8.4-v1',input.scope,input.repository,input.app,input.context,input.profiles,[...s.files.values()].map(f=>({path:f.path,sha256:f.sha256,role:f.role||'unknown',size:f.size})).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0)]),
    profiles:input.profiles,documents:sorted(s.documents),census:sorted(expected),dispositions:sorted(dispositions),artifacts:sorted([...new Map(s.artifacts.map(f=>[f.id,f])).values()]),facts:sorted([...new Map(s.facts.map(f=>[f.id,f])).values()]),obligations:sorted([...new Map(s.obligations.map(g=>[g.id,g])).values()]),native:sorted(s.native)};
  result.identity=digest(result);return result;
}
module.exports={analyze};
if(require.main===module){
  const fs=require('node:fs');
  (async()=>{
    const [requestFile,toolsRoot,routingRoot,nuxtRoot,vueRoot,bridgeRoot]=process.argv.slice(2);if(!requestFile||!toolsRoot||![4,5,6,7,8].includes(process.argv.length))throw Error('REGISTERED_ARCHITECTURE_ARGUMENTS_REQUIRED');
    if(fs.statSync(requestFile).size>90*1024*1024)throw Error('ARCHITECTURE_REQUEST_WORKER_REQUIRED');
    const input=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(fs.readFileSync(requestFile)));
    process.stdout.write(canonical(await analyze(input,{toolsRoot,routingRoot,nuxtRoot,vueRoot,bridgeRoot})));
  })().catch(()=>{process.stderr.write('ARCHITECTURE_CAPTURE_FAILED\n');process.exitCode=2;});
}
