'use strict';
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),{createRequire}=require('node:module'),{pathToFileURL}=require('node:url');
const {sha,digest,logical}=require('./common.cjs');
async function load(root){
  const pin=require('./routing-toolchain.json');
  if(!root||!pin.runtimes.some(r=>r.version===process.versions.node&&r.platform===process.platform))throw Error('ROUTING_TOOL_RUNTIME_REQUIRED');
  if(sha(fs.readFileSync(path.resolve(root,'package-lock.json')))!==pin.lock||require('./tool-tree.cjs').tree(path.resolve(root,'node_modules')).digest!==pin.tree.digest)throw Error('ROUTING_TOOL_BYTES_CHANGED');
  const lock=JSON.parse(fs.readFileSync(path.resolve(root,'package-lock.json'),'utf8'));
  for(const [name,version] of Object.entries(pin.packages))if(lock.packages?.['node_modules/'+name]?.version!==version)throw Error('ROUTING_TOOL_VERSION_CHANGED');
  for(const [name,pkg] of Object.entries(lock.packages))if(name&&(!pkg.integrity||!pkg.resolved?.startsWith('https://registry.npmjs.org/')))throw Error('ROUTING_TOOL_CLOSURE_PIN');
  const native=createRequire(path.resolve(root,'package.json')),kit=path.dirname(native.resolve('@sveltejs/kit/package.json'));
  return {identity:digest(pin),typescript:native('typescript'),webpack:()=>{if(process.env.NEXT_PRIVATE_LOCAL_WEBPACK)throw Error('AMBIENT_WEBPACK_FORBIDDEN');const sdk=native('next/dist/compiled/webpack/webpack');sdk.init();return sdk.webpack;},next:native('next/dist/build/entries.js'),nextDirs:native('next/dist/lib/find-pages-dir.js').findPagesDir,nextSchema:native('next/dist/server/config-schema.js').configSchema,nextPrefix:native('next/dist/shared/lib/router/utils/add-path-prefix.js').addPathPrefix,nextMatcher:native('next/dist/server/lib/find-page-file.js'),nextPaths:native('next/dist/shared/lib/router/utils/app-paths.js'),nextConstants:native('next/dist/lib/constants.js'),nuxt:await import(pathToFileURL(native.resolve('unrouting'))),kit:(await import(pathToFileURL(path.join(kit,'src/core/sync/create_manifest_data/index.js')))).default,kitConfig:(await import(pathToFileURL(path.join(kit,'src/core/config/index.js')))).validate_config,remix:native('@react-router/fs-routes'),routerConfig:native('@react-router/dev/routes')};
}
const sourceFiles=s=>[...s.files.values()].filter(f=>!['dependency','toolchain'].includes(f.role)).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
function materialize(s){
  const base=fs.realpathSync(os.tmpdir()),root=fs.mkdtempSync(path.join(base,'evo-native-routes-'));
  const remove=()=>{
    const target=path.resolve(root);
    if(path.dirname(target)!==base||!path.basename(target).startsWith('evo-native-routes-')||fs.realpathSync(target)!==target)throw Error('ROUTE_TEMP_IDENTITY_CHANGED');
    fs.rmSync(target,{recursive:true,force:true});
  };
  try{for(const file of sourceFiles(s)){
    if(process.platform==='win32'&&file.path.split('/').some(p=>/[. ]$/.test(p)||/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(p)))throw Error('ROUTE_FILESYSTEM_IDENTITY_UNAVAILABLE');
    const destination=path.resolve(root,...file.path.split('/'));
    const relative=path.relative(root,destination);if(!relative||path.isAbsolute(relative)||relative==='..'||relative.startsWith('..'+path.sep))throw Error('ROUTE_TEMP_PATH_ESCAPE');
    fs.mkdirSync(path.dirname(destination),{recursive:true,mode:0o700});
    fs.writeFileSync(destination,file.text===null?Buffer.from(file.data,'base64'):Buffer.from(file.text,'utf8'),{flag:'wx',mode:0o400});
  }}catch(error){remove();throw error;}
  return {root,remove};
}
function validate(model,s){
  if(!model||typeof model!=='object'||Array.isArray(model)||!['next','nuxt','sveltekit','remix'].includes(model.framework)||!s.profiles.has(model.framework))throw Error('ROUTE_MODEL_PROFILE_REQUIRED');
  const allowed=new Set(['framework','directory','config',...(model.framework==='next'?['mode','extensions']:model.framework==='remix'?['routesDirectory','routesConfig']:[])]);
  if(Object.keys(model).some(k=>!allowed.has(k)))throw Error('ROUTE_MODEL_OPTION_UNREGISTERED');
  logical(model.directory);
  if(model.config)logical(model.config);if(model.routesConfig)logical(model.routesConfig);
  if(model.framework==='next'){
    if(!['app','pages'].includes(model.mode))throw Error('ROUTE_MODEL_MODE_REQUIRED');
    if(model.extensions&&(!Array.isArray(model.extensions)||!model.extensions.length||new Set(model.extensions).size!==model.extensions.length||model.extensions.some(e=>!['js','jsx','ts','tsx'].includes(e))))throw Error('ROUTE_MODEL_EXTENSION_UNREGISTERED');
  }
  if(model.framework==='remix')logical(model.routesDirectory||'routes');
}
async function analyze(s){
  const models=s.input.context.routeModels;if(models===undefined)return;
  const files=sourceFiles(s),native=s.tools.routing;
  function gap(reason,model,details={}){
    const selected=model?.config?files.filter(f=>f.path===model.config):model?.directory?files.filter(f=>f.path.startsWith(model.directory+'/')):files;
    for(const f of selected.length?selected:files.slice(0,1))if(f.text!==null)s.gap(f,'F03',reason,0,f.text.length,{framework:model?.framework||null,...details});
  }
  if(!Array.isArray(models)){gap('ROUTE_MODEL_REQUEST_INVALID');return;}
  if(!native){gap('PINNED_NATIVE_ROUTE_TOOLS_REQUIRED');return;}
  let captured;
  const configurations=new Map();
  function record(model,engine,version,mode,diagnostics=[],extra={}){
    const representative=s.files.get(model.config)||files.find(f=>f.path.startsWith(model.directory+'/'));
    if(representative)s.native.push({path:representative.path,directory:model.directory,inputScope:'CAPTURED_DIRECTORY_MODEL',engine,version,mode,diagnostics,...extra});
  }
  function namespacePath(config,value){
    const origin=config?.file?path.posix.dirname(config.file.path):'.';
    if(typeof value!=='string'||!value||/[\\:\0]/.test(value)||path.posix.isAbsolute(value))throw Error('FRAMEWORK_PATH_OUTSIDE_CAPTURE');
    const relative=path.posix.normalize(path.posix.join(origin,value));
    if(relative==='..'||relative.startsWith('../'))throw Error('FRAMEWORK_PATH_OUTSIDE_CAPTURE');
    return relative;
  }
  s.nativeRouteFiles=new Set();
  function emit(model,filePath,kind,attributes){
    filePath=filePath.replaceAll('\\','/');
    const file=s.files.get(filePath);if(!file||file.text===null||['dependency','toolchain'].includes(file.role))throw Error('NATIVE_ROUTE_SOURCE_OUTSIDE_CAPTURE');
    const config=configurations.get(model),applied=config?.applied===true,basePath=applied?config.basePath??null:null;
    const row=s.fact(file,'F03',kind,0,file.text.length,s.id('native-route',digest([model,file.path,attributes])),{framework:model.framework,recipe:'native-file-route-model:m8.4-v1',...attributes,basePath,configuration:config?.file?.path??null,...(applied&&model.framework==='next'&&attributes.route?{publicRoute:native.nextPrefix(attributes.route,basePath||'')}:{}) ,reachable:'NOT_ESTABLISHED',effectiveConfig:applied?'NATIVE_VALIDATED_STATIC_SOURCE':config?'SOURCE_DATA_ONLY':'NOT_CAPTURED'},'CONDITIONAL',config?.supports||[]);
    if(!applied)s.gap(file,'F03','EFFECTIVE_FRAMEWORK_CONFIG_REQUIRED',0,file.text.length,{declaration:row.id,framework:model.framework});s.nativeRouteFiles.add(model.framework+'\0'+file.path);
  }
  try{for(const model of models){
    try{
      validate(model,s);
      const config=require('./configuration.cjs').read(s,model);
      if(config){configurations.set(model,config);if(config.missing)gap('FRAMEWORK_CONFIGURATION_UNAVAILABLE',model);}
      if(!files.some(f=>f.path.startsWith(model.directory+'/'))){gap('NATIVE_ROUTE_DIRECTORY_UNAVAILABLE',model);continue;}
      if(model.framework!=='nuxt')captured??=materialize(s);
      if(model.framework==='next'){
        const directory=path.join(captured.root,...model.directory.split('/'));let extensions=model.extensions||['tsx','ts','jsx','js'];
        if(config?.complete){
          const sourceRoot=path.resolve(captured.root,path.posix.dirname(config.file.path)),owned=native.nextDirs(sourceRoot)[model.mode==='app'?'appDir':'pagesDir'];
          if(!owned||path.relative(captured.root,owned).replaceAll('\\','/')!==model.directory){s.gap(config.file,'F10','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');throw Error('FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');}
          const projected={...config.value};for(const key of ['rewrites','redirects','headers'])if(projected[key]?.staticFunctionResult)projected[key]=async()=>config.value[key].staticFunctionResult;
          const checked=native.nextSchema.safeParse(projected);
          if(!checked.success){config.complete=false;s.gap(config.file,'F10','NATIVE_FRAMEWORK_CONFIG_INVALID');}
          else{const base=config.value.basePath??'';if(typeof base!=='string'||base&&(!base.startsWith('/')||base.endsWith('/')||/[?#\\]/.test(base))){config.complete=false;s.gap(config.file,'F10','NATIVE_FRAMEWORK_CONFIG_INVALID');}else config.basePath=base;
            if(config.complete&&config.value.pageExtensions){if(config.value.pageExtensions.every(e=>typeof e==='string'&&/^[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*$/.test(e)))extensions=config.value.pageExtensions;else{config.complete=false;s.gap(config.file,'F10','NATIVE_FRAMEWORK_CONFIG_INVALID');}}
          }
          config.applied=config.complete;
        }
        const matcher=native.nextMatcher.createValidFileMatcher(extensions,directory);
        const collected=model.mode==='app'?await native.next.collectAppFiles(directory,matcher):{appPaths:await native.next.collectPagesFiles(directory,matcher),layoutPaths:[],defaultPaths:[]};
        // Dynamic metadata invokes a different native compilation recipe. Do not evaluate it here.
        const paths=collected.appPaths.filter(p=>{
          if(model.mode==='app'&&matcher.isMetadataFile(path.join(directory,p))){const f=s.files.get(model.directory+'/'+p.replaceAll('\\','/').replace(/^\//,''));if(f&&f.text!==null)s.gap(f,'F03','NEXT_METADATA_COMPILATION_REQUIRED');return false;}return true;
        });
        const mapping=await native.next.createPagesMapping({isDev:false,pageExtensions:extensions,pagePaths:paths,pagesType:model.mode,pagesDir:model.mode==='pages'?directory:undefined,appDir:model.mode==='app'?directory:undefined});
        const alias=model.mode==='app'?native.nextConstants.APP_DIR_ALIAS:native.nextConstants.PAGES_DIR_ALIAS;
        const selected=new Set();
        for(const [key,value] of Object.entries(mapping)){
          const relative=value.replaceAll('\\','/');if(!relative.startsWith(alias+'/'))continue;
          const file=model.directory+'/'+relative.slice(alias.length+1);if(!s.files.has(file))throw Error('NATIVE_ROUTE_SOURCE_OUTSIDE_CAPTURE');
          selected.add(file);const route=model.mode==='app'?native.nextPaths.normalizeAppPath('/'+key.replace(/^\//,'')):'/'+key.replace(/^\//,'');
          emit(model,file,'NATIVE_ROUTE_DECLARATION',{route,nativeKey:key,role:model.mode==='app'&&/\/route\.[^.]+$/.test(file)?'endpoint':'page',parent:null,index:false});
        }
        for(const relative of paths){const file=s.files.get(model.directory+'/'+relative.replaceAll('\\','/').replace(/^\//,''));if(file&&!selected.has(file.path))s.gap(file,'F03','NATIVE_ROUTE_SOURCE_SHADOWED');}
        for(const [kind,list] of [['NATIVE_ROUTE_LAYOUT',collected.layoutPaths],['NATIVE_ROUTE_DEFAULT',collected.defaultPaths]])for(const relative of list){const file=model.directory+'/'+relative.replaceAll('\\','/').replace(/^\//,'');emit(model,file,kind,{route:null,role:kind==='NATIVE_ROUTE_LAYOUT'?'layout':'default',parent:null,index:false});}
        record(model,'next','15.5.28','native-file-route-model');
      }else if(model.framework==='nuxt'){
        if(s.input.context.nativeNuxtConfig===true&&!s.tools.nuxtConfig)gap('NUXT_CONFIGURATION_SDK_REQUIRED',model);
        if(config?.complete&&s.tools.nuxtConfig&&s.input.context.nativeNuxtConfig===true){
          try{
            const root=path.posix.dirname(config.file.path),raw=config.value;
            const check=value=>{if(typeof value!=='string'||!value||/[\\:\0]/.test(value)||path.posix.isAbsolute(value))throw Error('FRAMEWORK_PATH_OUTSIDE_CAPTURE');const resolved=path.posix.normalize(path.posix.join(root,value));if(resolved==='..'||resolved.startsWith('../'))throw Error('FRAMEWORK_PATH_OUTSIDE_CAPTURE');};
            for(const key of ['rootDir','srcDir','serverDir','buildDir','typesDir','workspaceDir'])if(raw[key]!==undefined)check(raw[key]);
            for(const value of Object.values(raw.dir||{}))check(value);
            for(const value of raw.modulesDir||[])check(value);
            const active=value=>value&&typeof value==='object'?Object.keys(value).length>0:!!value;
            const effects=Object.keys(raw).some(k=>k.startsWith('_'))||['extends','theme','hooks','modules','alias'].some(k=>active(raw[k]));
            if(effects){config.complete=false;s.gap(config.file,'F10','NUXT_CONFIGURATION_EFFECTS_REQUIRED');}
            else if(!['production','development'].includes(s.input.context.conditions?.mode)){config.complete=false;s.gap(config.file,'F10','NUXT_CONFIGURATION_MODE_REQUIRED');}
            else if(raw.app?.baseURL===undefined&&files.some(f=>path.posix.dirname(f.path)===root&&/^\.env(?:\.|$)/.test(path.posix.basename(f.path)))){config.complete=false;s.gap(config.file,'F10','NUXT_CONFIGURATION_ENVIRONMENT_REQUIRED');}
            else{
              captured??=materialize(s);const cwd=path.resolve(captured.root,root),result=s.tools.nuxtConfig.resolve(cwd,raw,s.input.context.conditions.mode,captured.root);
              const pages=path.posix.normalize(path.posix.join(root,result.pagesDir));
              if(pages!==model.directory){config.complete=false;s.gap(config.file,'F10','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');throw Error('FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');}
              if(typeof result.baseURL!=='string'||!result.baseURL.startsWith('/')||/[?#\\]/.test(result.baseURL))throw Error('NUXT_BASE_URL_INVALID');
              config.applied=true;config.basePath=result.baseURL;
              s.fact(config.file,'F10','NATIVE_NUXT_CONFIGURATION',0,config.file.text.length,null,{...result,projection:'native-schema-defaults-without-module-execution',environment:'CAPTURED_MODE_NO_AMBIENT_VALUES',tool:s.tools.nuxtConfig.identity});
              s.native.push({path:config.file.path,engine:'@nuxt/schema',version:'4.6.3',mode:'native-schema-defaults-data-only',diagnostics:[]});
            }
          }catch(error){if(['FRAMEWORK_PATH_OUTSIDE_CAPTURE','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT'].includes(error.message))s.gap(config.file,'F10',error.message);else s.gap(config.file,'F10','NUXT_NATIVE_CONFIGURATION_REQUIRED');config.applied=false;config.complete=false;}
        }
        if(config?.complete&&(config.value?.pages===false||config.value?.pages?.enabled===false)){
          s.fact(config.file,'F03','FRAMEWORK_ROUTE_SELECTION',0,config.file.text.length,null,{framework:'nuxt',selection:'FILE_ROUTES_DISABLED_BY_SOURCE_CONFIG'});
          for(const file of files)if(file.path.startsWith(model.directory+'/'))s.nativeRouteFiles.add('nuxt\0'+file.path);
          continue;
        }
        const input=files.filter(f=>f.path.startsWith(model.directory+'/')&&/\.(?:vue|jsx?|tsx?)$/.test(f.path)&&!f.path.endsWith('.d.ts')).map(f=>({path:f.path,priority:0}));
        let warnings=0;const tree=native.nuxt.buildTree(input,{roots:[model.directory],modes:['client'],warn:()=>warnings++});
        const routes=native.nuxt.toVueRouter4(tree,{attrs:{mode:['client']},onDuplicateRouteName:()=>warnings++});
        function visit(rows,parent=null){for(const route of rows){if(route.file)emit(model,route.file,'NATIVE_ROUTE_DECLARATION',{route:route.path,name:route.name??null,parent,index:route.path==='',role:'page'});visit(route.children||[],route.file||parent);}}visit(routes);
        if(warnings)gap('NATIVE_ROUTE_DIAGNOSTIC',model,{count:warnings});
        record(model,'unrouting','0.3.1','native-file-route-model-before-page-meta-and-hooks',warnings?['NATIVE_ROUTE_DIAGNOSTIC']:[],{consumer:'nuxt:4.6.3'});
      }else if(model.framework==='sveltekit'){
        // Validate *all* source-controlled paths before any native filesystem access.
        function verifyPaths(files){for(const [key,value] of Object.entries(files||{}))if(key==='hooks'&&value&&typeof value==='object')for(const hook of Object.values(value))namespacePath(config,hook);else namespacePath(config,value);}
        if(config?.complete)try{verifyPaths(config.value?.kit?.files);}catch(error){config.complete=false;s.gap(config.file,'F10','FRAMEWORK_PATH_OUTSIDE_CAPTURE');throw error;}
        const priorWarn=console.warn;let kitConfig,warnings=0;
        try{console.warn=()=>warnings++;kitConfig=native.kitConfig(config?.complete?config.value:model.directory==='src/routes'?{}:{kit:{files:{routes:model.directory}}});}finally{console.warn=priorWarn;}
        const nativePath=value=>namespacePath(config?.complete?config:null,typeof value==='string'?value.replaceAll('\\','/'):value);
        if(config?.complete){if(nativePath(kitConfig.kit.files.routes)!==model.directory){config.complete=false;s.gap(config.file,'F10','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');throw Error('FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');}config.basePath=kitConfig.kit.paths.base;}
        if(config?.complete)config.applied=true;
        if(warnings)gap('NATIVE_ROUTE_CONFIG_DIAGNOSTIC',model,{count:warnings});
        // Relocate the native SDK's validated paths into the immutable captured namespace.
        for(const key of Object.keys(kitConfig.kit.files))if(key==='hooks')for(const hook of Object.keys(kitConfig.kit.files.hooks))kitConfig.kit.files.hooks[hook]=path.resolve(captured.root,nativePath(kitConfig.kit.files.hooks[hook]));else kitConfig.kit.files[key]=path.resolve(captured.root,nativePath(kitConfig.kit.files[key]));
        const manifest=native.kit({config:kitConfig,cwd:captured.root});
        for(const route of manifest.routes){
          const attributes={route:route.id,pattern:route.pattern.source,parameters:route.params,parent:route.parent?.id??null,index:false};
          for(const [part,node] of [['page',route.leaf],['layout',route.layout],['error',route.error]])if(node)for(const role of ['component','universal','server'])if(node[role]&&s.files.has(node[role]))emit(model,node[role],part==='page'?'NATIVE_ROUTE_DECLARATION':'NATIVE_ROUTE_'+part.toUpperCase(),{...attributes,role:part+'-'+role});
          if(route.endpoint)emit(model,route.endpoint.file,'NATIVE_ROUTE_DECLARATION',{...attributes,role:'endpoint'});
        }
        for(const [name,file] of Object.entries(manifest.matchers))emit(model,file,'NATIVE_ROUTE_MATCHER',{route:null,name,role:'matcher',parent:null,index:false});
        for(const [name,file] of Object.entries(manifest.hooks))if(file)emit(model,file,'NATIVE_FRAMEWORK_HOOK',{route:null,name,role:'hook',parent:null,index:false});
        record(model,'@sveltejs/kit','2.37.1','native-manifest-data-no-target-module-evaluation');
      }else{
        if(config?.complete&&namespacePath(config,config.value?.appDirectory??'app')!==model.directory){s.gap(config.file,'F10','FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');throw Error('FRAMEWORK_SOURCE_OWNERSHIP_CONFLICT');}
        const declaration=require('./configuration.cjs').read(s,model,'routesConfig');
        if(declaration){configurations.set(model,{...declaration,applied:declaration.complete&&(!config||config.complete),supports:[...(config?.supports||[]),...(declaration.supports||[])]});if(!declaration.complete||!Array.isArray(declaration.value)){gap('NATIVE_ROUTE_CONFIGURATION_UNRESOLVED',model);continue;}}
        const directory=path.join(captured.root,...model.directory.split('/')),prior=Object.getOwnPropertyDescriptor(globalThis,'__reactRouterAppDirectory');
        let routes,warnings=0;const oldWarn=console.warn,oldError=console.error;
        try{
          globalThis.__reactRouterAppDirectory=directory;console.warn=console.error=()=>warnings++;
          // This pinned SDK consumes the global synchronously, before returning its Promise.
          routes=declaration?declaration.value:native.remix.flatRoutes({rootDirectory:model.routesDirectory||'routes'});
        }finally{console.warn=oldWarn;console.error=oldError;if(prior)Object.defineProperty(globalThis,'__reactRouterAppDirectory',prior);else delete globalThis.__reactRouterAppDirectory;}
        function visit(rows,parent=null){for(const row of rows){const file=path.posix.join(model.directory,row.file);emit(model,file,'NATIVE_ROUTE_DECLARATION',{route:row.path??null,index:row.index===true,parent,role:'page',nativeId:row.id??null});visit(row.children||[],file);}}visit(await routes);
        if(warnings)gap('NATIVE_ROUTE_DIAGNOSTIC',model,{count:warnings});
        record(model,declaration?'@react-router/dev':'@react-router/fs-routes','7.9.4',declaration?'native-declaration-helpers':'native-file-route-model-before-route-config',warnings?['NATIVE_ROUTE_DIAGNOSTIC']:[]);
      }
    }catch(error){if(s.onProviderError)s.onProviderError(error);gap('NATIVE_ROUTE_MODEL_UNAVAILABLE',model,{errorKind:error.constructor.name,diagnosticDigest:sha(String(error.message))});}
  }}finally{captured?.remove();}
}
module.exports={load,analyze};
