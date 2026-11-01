'use strict';
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const {sha,digest}=require('./common.cjs');
function load(root){
  const pin=require('./vue-toolchain.json');root=path.resolve(root);const bytes=fs.readFileSync(path.join(root,'package-lock.json')),lock=JSON.parse(bytes);
  if(!pin.runtimes.some(r=>r.platform===process.platform&&r.version===process.versions.node))throw Error('VUE_TOOL_RUNTIME_REQUIRED');
  if(sha(bytes)!==pin.lock||require('./tool-tree.cjs').tree(path.join(root,'node_modules')).digest!==pin.tree.digest)throw Error('VUE_TOOL_BYTES_CHANGED');
  for(const [name,version] of Object.entries(pin.packages))if(lock.packages?.['node_modules/'+name]?.version!==version)throw Error('VUE_TOOL_VERSION_CHANGED');
  for(const [name,pkg] of Object.entries(lock.packages))if(name&&(!pkg.integrity||!pkg.resolved?.startsWith('https://registry.npmjs.org/')))throw Error('VUE_TOOL_CLOSURE_PIN');
  const native=createRequire(path.join(root,'package.json')),helpers=new Map(),helperRoot=path.join(path.dirname(native.resolve('@vue/language-core/package.json')),'types');
  function collect(dir,prefix=''){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const relative=prefix+entry.name,p=path.join(dir,entry.name);if(entry.isDirectory())collect(p,relative+'/');else if(entry.name.endsWith('.d.ts'))helpers.set(relative,fs.readFileSync(p,'utf8'));}}collect(helperRoot);
  return {identity:digest(pin),typescript:native('typescript'),core:native('@vue/language-core'),proxy:native('@volar/typescript/lib/node/proxyCreateProgram').proxyCreateProgram,helpers};
}
function analyze(s){
  if(s.input.context.nativeVue!==true)return;
  const files=[...s.files.values()].filter(f=>f.text!==null&&!['dependency','toolchain'].includes(f.role)&&f.path.endsWith('.vue')).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
  const sdk=s.tools.vueContext;if(!sdk){for(const f of files)s.gap(f,'F12','VUE_CONTEXT_SDK_REQUIRED');return;}
  if(!s.profiles.has('vue')&&!s.profiles.has('nuxt'))return;
  const configured=s.input.context.vueCompilerOptions||{},allowed=new Set(['strictTemplates','strictCssModules','strictVModel','checkUnknownProps','checkUnknownEvents','checkUnknownDirectives','checkUnknownComponents','skipTemplateCodegen']);
  if(!configured||typeof configured!=='object'||Array.isArray(configured)||Object.entries(configured).some(([k,v])=>!allowed.has(k)||typeof v!=='boolean')){for(const f of files)s.gap(f,'F12','VUE_COMPILER_OPTION_UNREGISTERED');return;}
  let plugin;const generatedIds=new Set();
  try{
    const raw=s.input.context.compilerOptions||{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',jsx:'preserve',allowJs:true,checkJs:true,strict:true};
    if(['plugins','outDir','outFile'].some(k=>k in raw))throw Error('VUE_EXECUTION_OR_EMIT_OPTION_FORBIDDEN');
    const ts=sdk.typescript,parsed=ts.convertCompilerOptionsFromJson(raw,'/');if(parsed.errors.length)throw Error('VUE_COMPILER_OPTIONS_INVALID');
    const sources=new Map([...s.files.values()].filter(f=>f.text!==null).map(f=>['/'+f.path,f]));
    let helperPrefix='/__evo_vue_types',suffix=0;while([...sources.keys()].some(p=>p===helperPrefix||p.startsWith(helperPrefix+'/')))helperPrefix='/__evo_vue_types_'+(++suffix);
    for(const [name,text] of sdk.helpers)sources.set(helperPrefix+'/'+name,{text,role:'toolchain'});
    const names=[...sources.keys()],lookup=n=>sources.get(path.posix.normalize(n));
    const host=require('./javascript.cjs').captureHost(ts,lookup,names,parsed.options);
    const wrapped={...ts,sys:{...ts.sys,...host,useCaseSensitiveFileNames:true,getFileSize:n=>Buffer.byteLength(lookup(n)?.text||'','utf8'),readDirectory:()=>[],writeFile:()=>{throw Error('VUE_FILE_WRITE_FORBIDDEN');}}};
    const options={...sdk.core.getDefaultCompilerOptions(3.5,'vue',configured.strictTemplates===true,helperPrefix),...configured};
    plugin=sdk.core.createVueLanguagePlugin(wrapped,parsed.options,options,id=>id);
    const createVirtual=plugin.createVirtualCode;plugin.createVirtualCode=function(id,...args){generatedIds.add(id);return createVirtual.call(this,id,...args);};
    let language;
    const create=sdk.proxy(wrapped,ts.createProgram,()=>({languagePlugins:[plugin],setup(value){language=value;}}));
    const roots=[...s.files.values()].filter(f=>f.text!==null&&!['dependency','toolchain'].includes(f.role)&&/\.(?:vue|[cm]?[jt]sx?)$/.test(f.path)).map(f=>'/'+f.path).sort();
    const program=create({rootNames:roots,options:{...parsed.options,noEmit:true,allowNonTsExtensions:true},host});
    const diagnostics=ts.getPreEmitDiagnostics(program);s.vueTypechecked=new Set();
    for(const file of files){
      const script=language.scripts.get('/'+file.path),service=script?.generated&&plugin.typescript.getServiceScript(script.generated.root);
      if(!service){s.gap(file,'F12','VUE_VIRTUAL_DOCUMENT_OMITTED');continue;}
      const text=service.code.snapshot.getText(0,service.code.snapshot.getLength()),artifact=s.artifact(file,'vue-typecheck-virtual',text);
      const mappings=(service.code.mappings||[]).map(m=>({sourceOffsets:m.sourceOffsets,generatedOffsets:m.generatedOffsets,lengths:m.lengths,...(m.generatedLengths?{generatedLengths:m.generatedLengths}:{}),capabilityKeys:Object.keys(m.data||{}).sort()}));
      const mapping=s.artifact(file,'vue-typecheck-mappings',{source:file.path,sourceDigest:file.sha256,generated:artifact.content,capabilitySemantics:'PINNED_LANGUAGE_CORE',mappings});
      const own=diagnostics.filter(d=>d.file?.fileName==='/'+file.path);
      const descriptor=s.tools['@vue/compiler-sfc'].parse(file.text,{filename:file.path}).descriptor;
      const templateSupported=!descriptor.template||!descriptor.template.src&&(!descriptor.template.lang||descriptor.template.lang==='html');
      const scriptsSupported=[descriptor.script,descriptor.scriptSetup].filter(Boolean).every(script=>!script.lang||['js','ts','jsx','tsx'].includes(script.lang));
      for(const d of own)if(d.category===ts.DiagnosticCategory.Error){
        const start=d.start??0,end=start+(d.length??0);
        try{s.gap(file,'F12','VUE_NATIVE_TYPECHECK_ERROR',start,end,{code:d.code});}catch{s.gap(file,'F12','VUE_NATIVE_DIAGNOSTIC_MAPPING_REQUIRED',0,file.text.length,{code:d.code});}
      }
      s.native.push({path:file.path,engine:'@vue/language-core',version:'3.3.13',typescript:'5.9.3',mode:'configured-sfc-program-noEmit',strictTemplates:configured.strictTemplates===true,templateCodegen:templateSupported&&!configured.skipTemplateCodegen,diagnostics:own.map(d=>d.code),generated:artifact.id,mapping:mapping.id,tool:sdk.identity});
      if(scriptsSupported)s.vueTypechecked.add(file.path);
      else s.gap(file,'F12','VUE_SCRIPT_CONTEXT_PROVIDER_REQUIRED');
      if(!templateSupported)s.gap(file,'F02','VUE_TEMPLATE_CONTEXT_PROVIDER_REQUIRED');
      if(configured.skipTemplateCodegen)s.gap(file,'F02','VUE_TEMPLATE_CHECKING_DISABLED');
    }
    const first=files[0];if(first)for(const d of diagnostics)if(!d.file||!s.files.has(d.file.fileName.replace(/^\//,'')))s.gap(first,'F12','VUE_NATIVE_CONTEXT_DIAGNOSTIC',0,first.text.length,{code:d.code});
  }catch(error){for(const file of files)s.gap(file,'F12',error.message==='VUE_EXECUTION_OR_EMIT_OPTION_FORBIDDEN'?error.message:'VUE_NATIVE_CONTEXT_REQUIRED',0,file.text.length,{errorKind:error.constructor.name});}
  finally{if(plugin)for(const id of generatedIds)plugin.disposeVirtualCode?.(id);}
}
module.exports={load,analyze};
