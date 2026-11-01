'use strict';
const posix=require('node:path').posix;
function captureHost(ts,lookup,names,options){
  return {getSourceFile(n,v){const f=lookup(n);return f&&f.text!==null?ts.createSourceFile(n,f.text,v,true,ts.getScriptKindFromFileName(n)):undefined;},
    getDefaultLibFileName(){return '/toolchain/lib/'+ts.getDefaultLibFileName(options);},writeFile(){throw Error('EMIT_FORBIDDEN');},getCurrentDirectory(){return '/';},
    getDirectories(n){const prefix=posix.normalize(n).replace(/\/$/,'')+'/';return [...new Set(names.filter(p=>p.startsWith(prefix)&&p.slice(prefix.length).includes('/')).map(p=>p.slice(prefix.length).split('/')[0]))].sort();},
    fileExists:n=>!!lookup(n),readFile:n=>lookup(n)?.text,directoryExists(n){const p=posix.normalize(n).replace(/\/$/,'')+'/';return names.some(f=>f.startsWith(p));},realpath:n=>posix.normalize(n),getCanonicalFileName:n=>n,useCaseSensitiveFileNames:()=>true,getNewLine:()=> '\n'};
}
function compile(s,views,excerptOnly){
  const ts=s.tools.typescript;
  const sources=new Map([...s.files].filter(([name,f])=>f.text!==null&&/\.[cm]?[jt]sx?$/.test(name)).map(([name,f])=>['/'+name,f]));
  for(const [name,view] of views)sources.set(name,view);
  const roots=[...sources].filter(([name,f])=>!['dependency','toolchain'].includes(f.role)&&(!excerptOnly||views.has(name))).map(([name])=>name).sort();
  if(!roots.length)return;
  const configured=s.input.context.compilerOptions||{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',jsx:'preserve',allowJs:true,checkJs:true,strict:true};
  if(['plugins','outDir','outFile'].some(k=>k in configured))throw Error('EXECUTION_OR_EMIT_OPTION_FORBIDDEN');
  const parsed=ts.convertCompilerOptionsFromJson(configured,'/');if(parsed.errors.length)throw Error('COMPILER_OPTIONS_INVALID');
  const lookup=n=>sources.get(posix.normalize(n))||s.files.get(posix.normalize(n).slice(1));
  const names=[...new Set([...s.files.keys()].map(n=>'/'+n).concat([...sources.keys()]))];
  const host=captureHost(ts,lookup,names,parsed.options);
  const program=ts.createProgram({rootNames:roots,options:{...parsed.options,noEmit:true},host}),checker=program.getTypeChecker();
  const diagnostics=ts.getPreEmitDiagnostics(program);s.program=program;s.checker=checker;s.ts=ts;s.compilerHost=host;s.compilerOptions={...parsed.options,noEmit:true};s.rootNames=roots;
  for(const d of diagnostics)if(!d.file||!roots.includes(d.file.fileName)){
    const view=d.file?sources.get(d.file.fileName):null,original=view?.originalFile||s.files.get(d.file?.fileName.slice(1)),fallback=sources.get(roots[0]),file=original||fallback.originalFile||fallback,offset=view?.offset||0;
    s.gap(file,'F12','NATIVE_CONTEXT_DIAGNOSTIC',original?offset+(d.start??0):0,original?offset+(d.start??0)+(d.length??0):file.text.length,{code:d.code});
  }
  function symbol(node){let sym=checker.getSymbolAtLocation(node);if(sym?.flags&ts.SymbolFlags.Alias)sym=checker.getAliasedSymbol(sym);return sym;}
  function declaration(node){const sym=symbol(node);return sym?.declarations?.[0];}
  function runtimeIdentity(node){
    if(ts.isPropertyAccessExpression(node)&&!runtimeIdentity(node.expression))return null;
    const written=checker.getSymbolAtLocation(node);
    if(written?.flags&ts.SymbolFlags.Alias)for(const declaration of written.declarations||[]){for(let parent=declaration;parent&&!ts.isSourceFile(parent);parent=parent.parent)if((ts.isImportSpecifier(parent)||ts.isImportClause(parent)||ts.isExportSpecifier(parent))&&parent.isTypeOnly)return null;}
    const target=symbol(node);return target?.flags&ts.SymbolFlags.Value?identity(node):null;
  }
  function sourceKey(source){const view=sources.get(source.fileName);return view?.originalFile?'/'+view.originalFile.path+'#'+view.segment:source.fileName;}
  function support(source,start,end){const view=sources.get(source.fileName);return {path:view?.originalFile?.path||source.fileName.slice(1),start:start+(view?.offset||0),end:end+(view?.offset||0)};}
  function symbolIdentity(sym){const d=sym?.declarations?.[0];if(!d)return null;const source=d.getSourceFile();let qualified=checker.getFullyQualifiedName(sym);
    if(sources.get(source.fileName)?.originalFile){const module=checker.getSymbolAtLocation(source),prefix=module?checker.getFullyQualifiedName(module):null;if(prefix&&qualified.startsWith(prefix+'.'))qualified=qualified.slice(prefix.length+1);}
    return s.id('symbol',sourceKey(source)+':'+qualified+':'+ts.SyntaxKind[d.kind]);}
  function identity(node){return node?symbolIdentity(symbol(node)):null;}
  function packageOf(node){const d=declaration(node);if(!d)return null;const p=d.getSourceFile().fileName,index=p.lastIndexOf('/node_modules/');if(index<0)return null;const match=p.slice(index).match(/^\/node_modules\/((?:@[^/]+\/)?[^/]+)\//);if(!match)return null;
    const meta=s.files.get(p.slice(1,index+1)+'node_modules/'+match[1]+'/package.json');if(!meta)return null;try{const value=JSON.parse(meta.text).version;return {name:match[1],version:typeof value==='string'&&/^\d+\.\d+\.\d+(?:-[\w.-]+)?(?:\+[\w.-]+)?$/.test(value)?value:null,path:p.slice(1)};}catch{return null;}}
  function api(node){let seen=new Set();for(let step=0;step<32;step++){
    const d=declaration(node);if(!d||seen.has(d))return null;seen.add(d);const p=packageOf(node);if(p)return {...p,member:symbol(node)?.getName()};
    if(ts.isVariableDeclaration(d)&&d.initializer&&(ts.isIdentifier(d.initializer)||ts.isPropertyAccessExpression(d.initializer))){node=d.initializer;continue;}
    if(d.getSourceFile().fileName.startsWith('/toolchain/lib/')&&d.getSourceFile().fileName.endsWith('lib.dom.d.ts'))return {name:'browser',version:'dom-ts5.9.3',member:d.name?.getText(),containingInterface:ts.isInterfaceDeclaration(d.parent)?d.parent.name.text:null};
    return null;
  }return null;}
  function refineCall(call,env=new Map(),seen=new Set(),supports=[]){
    const substitute=n=>ts.isIdentifier(n)&&env.has(n.text)?env.get(n.text):n;
    const value=api(call.expression);if(value)return {value,args:[...(call.arguments||[])].map(substitute),supports};
    let d=declaration(call.expression);if(d&&ts.isVariableDeclaration(d))d=d.initializer;
    if(!d||!ts.isFunctionLike(d)||!d.body||seen.has(d)||seen.size>=32)return null;
    const body=ts.isBlock(d.body)?d.body.statements.length===1&&ts.isReturnStatement(d.body.statements[0])?d.body.statements[0].expression:null:d.body;
    if(!body||!ts.isCallExpression(body)||d.parameters.some(p=>!ts.isIdentifier(p.name)||p.initializer||p.dotDotDotToken))return null;
    const bindings=new Map(d.parameters.map((p,i)=>[p.name.text,substitute(call.arguments?.[i]||p.name)]));
    const source=d.getSourceFile();return refineCall(body,bindings,new Set([...seen,d]),[...supports,support(source,d.getStart(source),d.getEnd())]);
  }
  function js(view,source){
    const file=view.originalFile||view,offset=view.offset||0;
    const owned=diagnostics.filter(d=>d.file===source);s.native.push({path:file.path,engine:'typescript',mode:view.sourceProjection?'checker-source-excerpt-noEmit':'checker-noEmit',diagnostics:owned.map(d=>d.code),...(view.originalFile&&s.vueTypechecked?.has(file.path)?{diagnosticMeaning:'BINDING_EXCERPT_ONLY_FULL_SFC_CHECK_SEPARATE'}:{}),...(view.sourceProjection?{sourceProjection:view.sourceProjection}:{})});
    for(const d of owned)if(d.category===ts.DiagnosticCategory.Error&&!(view.originalFile&&s.vueTypechecked?.has(file.path)))s.gap(file,'F12','NATIVE_TYPECHECK_ERROR',offset+(d.start??0),offset+(d.start??0)+(d.length??0),{code:d.code});
    const start=n=>n.getStart(source)+offset,end=n=>n.getEnd()+offset;
    const fact=(category,kind,node,target,attributes={},status='RESOLVED')=>s.fact(file,category,kind,start(node),end(node),target,attributes,status);
    const contextOwner=node=>{for(let p=node.parent;p;p=p.parent)if(ts.isFunctionLike(p))return s.id('callable',sourceKey(source)+':'+(p.getStart(source)+offset));return s.id('document',file.path);};
    const defaultSymbol=node=>{const module=checker.getSymbolAtLocation(source);return module?checker.getExportsOfModule(module).find(symbol=>symbol.getName()==='default'&&symbol.declarations?.some(d=>d===node||d===node.parent)):null;};
    function reactType(type,names){const variants=type?.isUnion?.()?type.types:[type];return variants.some(type=>{
      const symbol=type?.aliasSymbol||type?.symbol,decl=symbol?.declarations?.[0],origin=decl?.name?packageOf(decl.name):null;
      return names.includes(symbol?.getName())&&['react','@types/react'].includes(origin?.name)&&/^19\./.test(origin.version||'');
    });}
    function walk(n){
      if((ts.isImportDeclaration(n)||ts.isExportDeclaration(n))&&n.moduleSpecifier&&ts.isStringLiteral(n.moduleSpecifier)){
        const candidate=n.moduleSpecifier.text.match(/^((?:@[^/]+\/)?[^/]+)/)?.[1];
        if(['angular','jquery','backbone','solid-js','@builder.io/qwik','electron','react-native'].includes(candidate)){
          fact('F12','FRAMEWORK_CANDIDATE',n.moduleSpecifier,null,{package:candidate,detection:'import-specifier-only',frameworkUse:'NOT_ESTABLISHED'},'CONDITIONAL');
          s.gap(file,'F12','FRAMEWORK_EXTENSION_PROFILE_REQUIRED',start(n.moduleSpecifier),end(n.moduleSpecifier),{package:candidate});
        }
        const ref=declaration(n.moduleSpecifier),target=ref?support(ref.getSourceFile(),0,0).path:null;const typeOnly=!!n.importClause?.isTypeOnly||!!n.isTypeOnly;
        if(target)fact('F01','MODULE_REFERENCE',n.moduleSpecifier,target,{typeOnly,package:packageOf(n.moduleSpecifier)?.name||null});
        else if(n.moduleSpecifier.text.startsWith('.'))s.reference(file,'F01','MODULE_REFERENCE',start(n.moduleSpecifier)+1,end(n.moduleSpecifier)-1,n.moduleSpecifier.text,{typeOnly});
        else s.gap(file,'F01','PACKAGE_BINDING_UNAVAILABLE',start(n.moduleSpecifier),end(n.moduleSpecifier));
      }
      if(ts.isFunctionDeclaration(n)||ts.isClassDeclaration(n))if(n.name)fact('F01','DECLARATION',n.name,identity(n.name),{name:n.name.text,exported:n.modifiers?.some(m=>m.kind===ts.SyntaxKind.ExportKeyword)||false});
      if(ts.isFunctionLike(n)&&n.body){
        const assigned=ts.isVariableDeclaration(n.parent)?n.parent.name:null,name=assigned||n.name;
        const semantic=name?identity(name):symbolIdentity(defaultSymbol(n));
        let container=null;for(let parent=n.parent;parent;parent=parent.parent)if(ts.isClassDeclaration(parent)){container=parent.name?identity(parent.name):symbolIdentity(defaultSymbol(parent));break;}
        const callable=s.id('callable',sourceKey(source)+':'+start(n));
        fact('F01','CALLABLE_OWNER',n,callable,{symbol:semantic,name:name&&ts.isIdentifier(name)?name.text:semantic&&defaultSymbol(n)?'default':null,parent:contextOwner(n),container,kind:ts.SyntaxKind[n.kind]});
        const factory=ts.isFunctionDeclaration(n)||(ts.isArrowFunction(n)||ts.isFunctionExpression(n))&&!!assigned;
        if(factory&&semantic&&s.profiles.has('react')){const signature=checker.getSignatureFromDeclaration(n),returns=signature?checker.getReturnTypeOfSignature(signature):null;
          if(reactType(returns,['Element','ReactElement']))fact('F02','COMPONENT_DECLARATION',n,semantic,{framework:'react',version:'19',meaning:'native-react-element-return'});
        }
      }
      if(ts.isClassDeclaration(n)&&s.profiles.has('react')){
        const bases=checker.getTypeAtLocation(n).getBaseTypes?.()||[],semantic=n.name?identity(n.name):symbolIdentity(defaultSymbol(n));
        if(semantic&&bases.some(type=>reactType(type,['Component','PureComponent'])))fact('F02','COMPONENT_DECLARATION',n,semantic,{framework:'react',version:'19',meaning:'native-react-base-type'});
      }
      if(ts.isJsxOpeningElement(n)||ts.isJsxSelfClosingElement(n)){
        const name=n.tagName,tag=name.getText(source);if(ts.isPropertyAccessExpression(name)||ts.isIdentifier(name)&&!ts.isIntrinsicJsxName(name.text)){
          const target=runtimeIdentity(name),decl=declaration(name),dynamic=decl&&(ts.isPropertySignature(decl)||ts.isPropertyDeclaration(decl)||ts.isParameter(decl));
          if(target)fact('F02','COMPONENT_USE',name,target,{tag,binding:'typescript-symbol'},dynamic?'CONDITIONAL':'RESOLVED');else s.gap(file,'F02','COMPONENT_BINDING_UNAVAILABLE',start(name),end(name));
          if(target&&dynamic)s.gap(file,'F02','COMPONENT_VALUE_BINDING_PARTIAL',start(name),end(name));
        }
        for(const a of n.attributes.properties){if(ts.isJsxAttribute(a)){const key=a.name.getText(source);if(/^on[A-Z]/.test(key))fact('F04','EVENT_BINDING',a,identity(a.initializer?.expression||a.name),{event:key},'PARTIAL');if(a.initializer)fact('F02','COMPONENT_PROP',a,null,{name:key,component:tag},'PARTIAL');}}
      }
      if(ts.isCallExpression(n)||ts.isNewExpression(n)){
        const refined=refineCall(n),value=refined?.value||api(n.expression),owner=contextOwner(n),args=refined?.args||n.arguments||[];
        const imported=value&&{'@types/react':['useState','useReducer','useContext','createContext','useEffect','useSyncExternalStore','lazy','setState'],react:['useState','useReducer','useContext','createContext','useEffect','lazy','setState'],vue:['ref','reactive','computed','provide','inject'],'@vue/reactivity':['ref','reactive','computed'],'@vue/runtime-core':['provide','inject','computed'],pinia:['defineStore'],zustand:['create'],redux:['createStore','configureStore','createSlice'],'@angular/core':['inject','Injectable','signal','computed'],svelte:['writable','readable','derived'],'svelte/store':['writable','readable','derived']}[value.name]?.includes(value.member);
        if(imported){const family={'@types/react':'react',react:'react',vue:'vue','@vue/reactivity':'vue','@vue/runtime-core':'vue','@angular/core':'angular',svelte:'svelte','svelte/store':'svelte'}[value.name];
          const active=family&&(s.profiles.has(family)||family==='vue'&&s.profiles.has('nuxt')||family==='svelte'&&s.profiles.has('sveltekit'));
          const supported=active&&typeof value.version==='string'&&{react:/^19\./,vue:/^3\.5\./,angular:/^20\.3\./,svelte:/^5\./}[family]?.test(value.version);
          fact('F04','STATE_SERVICE_USE',n,identity(n.expression),{api:value.member,package:value.name,packageVersion:value.version,owner},supported?'RESOLVED':'CONDITIONAL');
          if(!supported)s.gap(file,'F04','STATE_SERVICE_PROFILE_REQUIRED',start(n),end(n));
        }
        if(value?.name==='browser'&&value.containingInterface==='XMLHttpRequest'&&value.member==='open'){
          const endpoint=args[1],method=args[0];
          if(endpoint&&ts.isStringLiteralLike(endpoint))fact('F05','CLIENT_API_USE',endpoint.getSourceFile()===source?endpoint:n,null,{protocol:'XMLHttpRequest',api:'open',stage:'REQUEST_CONFIGURATION',owner,endpointDigest:require('./common.cjs').sha(endpoint.text),method:method&&ts.isStringLiteralLike(method)&&/^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)$/i.test(method.text)?method.text.toUpperCase():'UNSPECIFIED'},'CONDITIONAL');
          else s.gap(file,'F05','DYNAMIC_ENDPOINT',start(n),end(n));
        }
        const serviceWorker=value?.name==='browser'&&value.containingInterface==='ServiceWorkerContainer'&&value.member==='register';
        if(value?.name==='browser'&&(['fetch','WebSocket','EventSource','Worker','SharedWorker'].includes(value.member)||serviceWorker)){
          const endpoint=args[0];if(endpoint&&ts.isStringLiteralLike(endpoint)){
            const sameSource=endpoint.getSourceFile()===source,site=sameSource?endpoint:n;
            const worker=value.member.includes('Worker')||serviceWorker;
            const row=fact(worker?'F06':'F05',worker?'WORKER_BOUNDARY':'NETWORK_REQUEST',site,null,{protocol:serviceWorker?'ServiceWorker':value.member,endpointDigest:require('./common.cjs').sha(Buffer.from(endpoint.text)),owner,method:'UNSPECIFIED',wrapperFootprint:refined?.supports||[]},'CONDITIONAL');
          }
          else s.gap(file,value.member.includes('Worker')||serviceWorker?'F06':'F05','DYNAMIC_ENDPOINT',start(n),end(n));
        }
        if(n.expression.kind===ts.SyntaxKind.ImportKeyword){const spec=args[0];if(spec&&ts.isStringLiteralLike(spec))s.reference(file,'F08','LAZY_MODULE',start(spec)+1,end(spec)-1,spec.text,{owner});else s.gap(file,'F08','DYNAMIC_MODULE',start(n),end(n));}
        if(value&&['axios','graphql-request','@apollo/client','@grpc/grpc-js','grpc-web'].includes(value.name))fact('F05','CLIENT_API_USE',n,identity(n.expression),{package:value.name,api:value.member,owner},'CONDITIONAL');
        if(ts.isIdentifier(n.expression)&&n.expression.text==='eval')s.gap(file,'F12','DYNAMIC_CODE',start(n),end(n));
        if(ts.isPropertyAccessExpression(n.expression)&&n.expression.expression.getText(source)==='customElements'&&n.expression.name.text==='define'){
          const d=declaration(n.expression.expression);if(d?.getSourceFile().fileName.startsWith('/toolchain/lib/'))fact('F09','CUSTOM_ELEMENT_REGISTRATION',n,identity(args[1]),{declared:true},'CONDITIONAL');
        }
      }
      if(ts.isIfStatement(n)||ts.isConditionalExpression(n))fact('F10','CONDITIONAL_GUARD',n.expression||n.condition,null,{truth:'UNKNOWN',scope:'source-guard'},'CONDITIONAL');
      if(ts.isExpressionStatement(n)&&ts.isStringLiteral(n.expression)&&['use client','use server'].includes(n.expression.text)){
        const statements=n.parent.statements||[],index=statements.indexOf(n),prologue=index>=0&&statements.slice(0,index).every(p=>ts.isExpressionStatement(p)&&ts.isStringLiteral(p.expression));
        if(prologue){const module=ts.isSourceFile(n.parent),serverAction=n.expression.text==='use server'&&ts.isBlock(n.parent)&&ts.isFunctionLike(n.parent.parent);
          if(module||serverAction){
            const directives=[];for(const statement of statements){if(!ts.isExpressionStatement(statement)||!ts.isStringLiteral(statement.expression))break;directives.push(statement.expression.text);}
            const conflict=module&&directives.includes('use client')&&directives.includes('use server');
            const status=!s.profiles.has('next')||view.originalFile?'PARTIAL':conflict||serverAction?'CONDITIONAL':'RESOLVED';
            fact('F06','EXECUTION_DIRECTIVE',n.expression,null,{side:n.expression.text.slice(4),meaning:'source-directive-declaration',scope:module?'module':'function',activation:'NOT_ESTABLISHED'},status);
            if(conflict)s.gap(file,'F06','CONFLICTING_EXECUTION_DIRECTIVES',start(n),end(n));
            if(serverAction)s.gap(file,'F06','NEXT_SERVER_ACTION_VALIDATION_REQUIRED',start(n),end(n));
          }
        }
      }
      ts.forEachChild(n,walk);
    }
    walk(source);
  }
  for(const name of roots){const source=program.getSourceFile(name),view=sources.get(name);if(source)js(view,source);else s.gap(view.originalFile||view,'F12','CHECKER_DOCUMENT_OMITTED');}
  s.binding={symbol,declaration,identity,packageOf,api};
}
function analyze(s){
  try{compile(s,new Map(),false);}catch(error){
    const reason=['COMPILER_OPTIONS_INVALID','EXECUTION_OR_EMIT_OPTION_FORBIDDEN'].includes(error.message)?error.message:'NATIVE_CHECKER_FAILED';
    for(const file of s.files.values())if(file.text!==null&&!['dependency','toolchain'].includes(file.role)&&/\.[cm]?[jt]sx?$/.test(file.path))s.gap(file,'F12',reason,0,file.text.length,{errorKind:error.constructor.name});
    // A failed context must not lend partially initialized bindings to another provider.
    for(const field of ['program','checker','ts','compilerHost','compilerOptions','rootNames','binding'])delete s[field];
  }
  const fields=['program','checker','ts','compilerHost','compilerOptions','rootNames','binding'],primary=new Map(fields.map(name=>[name,s[name]]));
  for(const [name,view] of s.scriptViews||[]){
    try{compile(s,new Map([[name,view]]),true);s.gap(view.originalFile,'F12',s.vueTypechecked?.has(view.originalFile.path)?'EMBEDDED_BINDING_CONTEXT_PARTIAL':'EMBEDDED_LANGUAGE_CONTEXT_PARTIAL',view.offset,view.offset+view.text.length);}
    catch{s.gap(view.originalFile,'F12','NATIVE_EMBEDDED_CHECKER_FAILURE',view.offset,view.offset+view.text.length);}
    finally{for(const [name,value] of primary)if(value===undefined)delete s[name];else s[name]=value;}
  }
}
module.exports={analyze,captureHost};
