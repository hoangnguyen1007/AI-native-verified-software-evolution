'use strict';
const {sha,digest}=require('./common.cjs');
const path=require('node:path').posix;
const resources=require('./resources.cjs');
const profiles={html:'html5',react:'19',vue:'3.5',angular:'20.3',svelte:'5',astro:'2.13',next:'15',nuxt:'4',sveltekit:'2',remix:'7'};
function validate(s){for(const p of [...s.profiles.values()])if(!(p.id in profiles)||p.version!==profiles[p.id]){
  s.profiles.delete(p.id);for(const file of s.files.values())if(!['dependency','toolchain'].includes(file.role))s.gap(file,'F12','FRAMEWORK_PROFILE_UNAVAILABLE',0,file.text?.length??0,{requestedProfile:p.id,requestedVersion:p.version});
}}
async function scriptInputs(s){
  const views=new Map();
  function add(file,segment,code,start,language){
    if(!['js','ts','jsx','tsx'].includes(language)){s.gap(file,'F12','EMBEDDED_SCRIPT_DIALECT_REQUIRED');return;}
    if(!Number.isSafeInteger(start)||file.text.slice(start,start+code.length)!==code){s.gap(file,'F12','ORIGINAL_SCRIPT_SPAN_UNAVAILABLE');return;}
    let name=file.path+'.__architecture_'+segment+'.'+language,index=0;
    while(s.files.has(name)||views.has('/'+name))name=file.path+'.__architecture_'+segment+'_'+(++index)+'.'+language;
    views.set('/'+name,{...file,path:name,text:code,originalFile:file,offset:start,segment,
      sourceProjection:{schema:'source-excerpt:m8.4-v1',language,start,end:start+code.length,digest:sha(Buffer.from(code,'utf8'))}});
  }
  for(const file of s.files.values()){
    if(file.text===null||['dependency','toolchain'].includes(file.role))continue;
    try{
      if(/\.html?$/.test(file.path)){
        const ast=s.tools.parse5.parse(file.text,{sourceCodeLocationInfo:true});let index=0,scripts=0,external=false;
        function walk(node){if(node.tagName==='script'){
          scripts++;const attrs=new Map((node.attrs||[]).map(a=>[a.name,a.value]));if(attrs.has('src'))external=true;
          else{
            const type=(attrs.get('type')||'').toLowerCase();const code=node.childNodes?.find(n=>n.nodeName==='#text'),location=code?.sourceCodeLocation;
            if(location&&s.htmlBaseExplicit.has(file.path))s.gap(file,'F12','EMBEDDED_DOCUMENT_BASE_CONTEXT_REQUIRED',location.startOffset,location.endOffset);
            else if(location&&['','module','text/javascript','application/javascript','text/ecmascript','application/ecmascript'].includes(type))add(file,'html-script-'+index++,file.text.slice(location.startOffset,location.endOffset),location.startOffset,'js');
            else if(location&&type!=='importmap')s.gap(file,'F12','EMBEDDED_SCRIPT_DIALECT_REQUIRED',location.startOffset,location.endOffset);
          }
        }for(const child of node.childNodes||[])walk(child);if(node.content)walk(node.content);}walk(ast);
        if(scripts>1||external)s.gap(file,'F10','EMBEDDED_DOCUMENT_SCRIPT_ORDER_REQUIRED');
      }else if(file.path.endsWith('.vue')&&(s.profiles.has('vue')||s.profiles.has('nuxt'))){
        const parsed=s.tools['@vue/compiler-sfc'].parse(file.text,{filename:file.path});if(parsed.errors.length)continue;
        for(const [segment,script] of [['vue-script',parsed.descriptor.script],['vue-setup',parsed.descriptor.scriptSetup]])if(script&&!script.src)add(file,segment,script.content,script.loc.start.offset,script.lang||'js');
      }else if(file.path.endsWith('.svelte')&&(s.profiles.has('svelte')||s.profiles.has('sveltekit'))){
        const ast=s.tools.svelte.parse(file.text,{modern:true});for(const [segment,script] of [['svelte-instance',ast.instance],['svelte-module',ast.module]])if(script){
          const lang=script.attributes.find(a=>a.name==='lang');const language=!lang?'js':lang.value?.length===1&&lang.value[0].type==='Text'?lang.value[0].data:null;
          add(file,segment,file.text.slice(script.content.start,script.content.end),script.content.start,language);
        }
      }else if(file.path.endsWith('.astro')&&s.profiles.has('astro')){
        const parsed=await s.tools['@astrojs/compiler'].parse(file.text,{position:true});let index=0;
        for(const node of parsed.ast.children||[])if(node.type==='frontmatter'){
          const start=file.text.indexOf(node.value,node.position?.start?.offset??0);add(file,'astro-frontmatter-'+index++,node.value,start,'ts');
        }
      }
    }catch{s.gap(file,'F12','NATIVE_SCRIPT_INPUT_UNAVAILABLE');}
  }
  return views;
}
function imports(s,file,text,offset){
  const ts=s.tools.typescript,source=ts.createSourceFile(file.path+'.ts',text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS),bindings=new Map();
  for(const stmt of source.statements)if(ts.isImportDeclaration(stmt)&&ts.isStringLiteral(stmt.moduleSpecifier)&&!stmt.importClause?.isTypeOnly){const clause=stmt.importClause;let target=null;
    if(stmt.moduleSpecifier.text.startsWith('.')){const candidates=[path.normalize(path.join(path.dirname(file.path),stmt.moduleSpecifier.text))];target=candidates.find(p=>s.files.has(p))||null;}
    const start=offset+stmt.moduleSpecifier.getStart(source),end=offset+stmt.moduleSpecifier.getEnd();
    const native=s.facts.filter(f=>f.path===file.path&&f.kind==='MODULE_REFERENCE'&&f.status==='RESOLVED'&&f.span?.start===start&&f.span.end===end&&s.files.has(f.target));
    const nativeTargets=new Set(native.map(f=>f.target));if(nativeTargets.size===1)target=native[0].target;
    for(const name of [clause?.name,...(clause?.namedBindings&&ts.isNamedImports(clause.namedBindings)?clause.namedBindings.elements.filter(e=>!e.isTypeOnly).map(e=>e.name):[])].filter(Boolean))bindings.set(name.text,{target,source:stmt.moduleSpecifier.text,support:[offset+stmt.getStart(source),offset+stmt.getEnd()]});
    if(nativeTargets.size===1)s.fact(file,'F01','SFC_MODULE_REFERENCE',start+1,end-1,target,{typeOnly:false,binding:'native-module-reference',nativeReference:native[0].id});
    else s.reference(file,'F01','SFC_MODULE_REFERENCE',start+1,end-1,stmt.moduleSpecifier.text,{typeOnly:false});
  }
  return bindings;
}
function composition(s,file,name,start,end,bindings,attributes={}){
  const binding=bindings.get(name);if(binding?.target)s.fact(file,'F02','COMPONENT_USE',start,end,binding.target,{...attributes,name,binding:'native-template-and-captured-import'},'RESOLVED',[{path:file.path,start:binding.support[0],end:binding.support[1]}]);
  else{s.fact(file,'F02','COMPONENT_USE',start,end,null,{...attributes,name},'UNRESOLVED');s.gap(file,'F02','TEMPLATE_COMPONENT_BINDING_REQUIRED',start,end);}
}
function vue(s,file){
  const v=s.tools['@vue/compiler-sfc'],parsed=v.parse(file.text,{filename:file.path});
  if(parsed.errors.length){s.gap(file,'F12','VUE_SFC_PARSE_ERROR');return;}
  const d=parsed.descriptor;let bindings=new Map();
  for(const script of [d.script,d.scriptSetup].filter(Boolean)){
    if(script.src){s.gap(file,'F02','EXTERNAL_SFC_SCRIPT_REQUIRED',script.loc.start.offset,script.loc.end.offset);continue;}
    if(script.lang&&!['js','ts','jsx','tsx'].includes(script.lang)){s.gap(file,'F12','SFC_PREPROCESSOR_REQUIRED',script.loc.start.offset,script.loc.end.offset);continue;}
    bindings=new Map([...bindings,...imports(s,file,script.content,script.loc.start.offset)]);
  }
  const generated=[];let bindingMetadata={};
  try{if(d.script||d.scriptSetup){const script=v.compileScript(d,{id:digest([file.path,file.sha256]),fs:{fileExists:p=>s.files.has(p),readFile:p=>s.files.get(p)?.text}});bindingMetadata=script.bindings;generated.push({kind:'vue-script',artifact:s.artifact(file,'vue-script',script.content),mapping:script.map?s.artifact(file,'vue-script-map',script.map):null});}}
  catch{s.gap(file,'F12','VUE_SCRIPT_COMPILATION_ERROR');}
  if(d.template){
    if(d.template.lang&&d.template.lang!=='html'||d.template.src)s.gap(file,'F12','TEMPLATE_PREPROCESSOR_REQUIRED');
    else{
      const result=v.compileTemplate({source:d.template.content,filename:file.path,id:digest(file.path),compilerOptions:{bindingMetadata}});
      if(result.errors.length)s.gap(file,'F12','VUE_TEMPLATE_COMPILATION_ERROR');else generated.push({kind:'vue-render',artifact:s.artifact(file,'vue-render',result.code),mapping:result.map?s.artifact(file,'vue-render-map',result.map):null});
      const localScopes=[];
      function pattern(text){const ts=s.tools.typescript,source=ts.createSourceFile('binding.ts','const '+text+' = null;',ts.ScriptTarget.Latest,true);const names=new Set();
        function collect(n){if(ts.isIdentifier(n))names.add(n.text);else if(ts.isObjectBindingPattern(n)||ts.isArrayBindingPattern(n))for(const element of n.elements)if(ts.isBindingElement(element))collect(element.name);}
        const name=source.statements[0]?.declarationList?.declarations[0]?.name;if(name)collect(name);return names.size?names:new Set(['*']);
      }
      function scopes(n){if(!n||typeof n!=='object')return;
        if(n.type===11){const names=new Set();for(const alias of [n.valueAlias,n.keyAlias,n.indexAlias].filter(Boolean))for(const name of pattern(alias.content))names.add(name);const ranges=[n.loc,...(n.children||[]).map(child=>child.loc).filter(Boolean)];localScopes.push({start:d.template.loc.start.offset+Math.min(...ranges.map(loc=>loc.start.offset)),end:d.template.loc.start.offset+Math.max(...ranges.map(loc=>loc.end.offset)),names});}
        for(const child of n.children||[])scopes(child);for(const branch of n.branches||[])scopes(branch);
      }scopes(result.ast);
      // The parse AST locations already reference original SFC bytes, unlike transformed helpers.
      function walk(n){
        if(n.type===1){const start=n.loc.start.offset,end=n.loc.end.offset;
          if(n.tagType===1){const shadow=localScopes.some(scope=>scope.start<=start&&scope.end>=end&&(scope.names.has(n.tag.split('.')[0])||scope.names.has('*')));
            if(shadow)s.gap(file,'F02','TEMPLATE_LOCAL_COMPONENT_BINDING',start,end);else composition(s,file,n.tag,start,end,bindings);}
          for(const p of n.props||[])if(p.type===7){const a=p.loc.start.offset,b=p.loc.end.offset;
            s.fact(file,p.name==='on'?'F04':'F02',p.name==='on'?'EVENT_BINDING':'TEMPLATE_DIRECTIVE',a,b,null,{name:p.name,argument:p.arg?.isStatic?p.arg.content:null},p.arg&&!p.arg.isStatic?'CONDITIONAL':'PARTIAL');
            if(['if','else-if','for','is'].includes(p.name))s.gap(file,'F10','DYNAMIC_TEMPLATE_SELECTION',a,b);
          }
        }
        for(const child of n.children||[])walk(child);
      }walk(d.template.ast);
    }
  }
  for(const style of d.styles)if(style.src)s.reference(file,'F07','SFC_STYLE',style.loc.start.offset,style.loc.end.offset,style.src);else{
    if(style.lang&&style.lang!=='css')s.gap(file,'F07','STYLE_PREPROCESSOR_REQUIRED',style.loc.start.offset,style.loc.end.offset);
    if(style.module)s.gap(file,'F07','CSS_MODULE_RESOLUTION_REQUIRED',style.loc.start.offset,style.loc.end.offset);
    resources.css(s,file,style.content,style.loc.start.offset);
  }
  s.fact(file,'F02','COMPONENT_DECLARATION',0,file.text.length,s.id('component',file.path),{framework:'vue',version:'3.5',generated});
  s.native.push({path:file.path,engine:'@vue/compiler-sfc',mode:'parse-script-template-compile',diagnostics:[]});
}
function svelte(s,file){
  const compiler=s.tools.svelte;let ast,compiled;
  try{ast=compiler.parse(file.text,{modern:true});compiled=compiler.compile(file.text,{filename:file.path,generate:'server'});}catch{s.gap(file,'F12','SVELTE_COMPILATION_ERROR');return;}
  const bindings=new Map();for(const script of [ast.instance,ast.module].filter(Boolean)){
    const content=script.content;for(const [name,value] of imports(s,file,file.text.slice(content.start,content.end),content.start))bindings.set(name,value);
  }
  function walk(n,locals=new Set()){if(!n||typeof n!=='object')return;
    if(n.type==='EachBlock'){
      const names=new Set(locals);function bindings(pattern){if(!pattern||typeof pattern!=='object')return;if(pattern.type==='Identifier')names.add(pattern.name);else if(pattern.type==='ObjectPattern')for(const property of pattern.properties)bindings(property.value||property.argument);else if(pattern.type==='ArrayPattern')for(const element of pattern.elements)bindings(element);else if(pattern.type==='AssignmentPattern')bindings(pattern.left);else if(pattern.type==='RestElement')bindings(pattern.argument);}
      bindings(n.context);if(n.index)names.add(typeof n.index==='string'?n.index:n.index.name);locals=names;
    }
    if(['Component','SvelteComponent','SvelteSelf'].includes(n.type)){
      if(n.type==='Component'&&!locals.has(n.name.split('.')[0]))composition(s,file,n.name,n.start,n.end,bindings);else s.gap(file,'F02',n.type==='Component'?'TEMPLATE_LOCAL_COMPONENT_BINDING':'DYNAMIC_COMPONENT_SELECTION',n.start,n.end);
    }
    if(['IfBlock','EachBlock','AwaitBlock','KeyBlock'].includes(n.type))s.fact(file,'F10','TEMPLATE_GUARD',n.start,n.end,null,{kind:n.type,truth:'UNKNOWN'},'CONDITIONAL');
    if(n.type==='OnDirective')s.fact(file,'F04','EVENT_BINDING',n.start,n.end,null,{event:n.name},'PARTIAL');
    if(n.type==='Attribute'&&n.name?.startsWith('on'))s.fact(file,'F04','EVENT_BINDING',n.start,n.end,null,{event:n.name},'PARTIAL');
    for(const [key,value] of Object.entries(n))if(!['loc','metadata'].includes(key))if(Array.isArray(value))value.forEach(child=>walk(child,locals));else if(value&&typeof value==='object')walk(value,locals);
  }walk(ast.fragment);
  if(ast.css?.content){const content=ast.css.content;resources.css(s,file,file.text.slice(content.start,content.end),content.start);}
  const style=compiled.css?{artifact:s.artifact(file,'svelte-css',compiled.css.code),mapping:compiled.css.map?s.artifact(file,'svelte-css-map',compiled.css.map):null}:null;
  s.fact(file,'F02','COMPONENT_DECLARATION',0,file.text.length,s.id('component',file.path),{framework:'svelte',version:'5',generated:{artifact:s.artifact(file,'svelte-server',compiled.js.code),mapping:s.artifact(file,'svelte-server-map',compiled.js.map),style}});
  s.native.push({path:file.path,engine:'svelte/compiler',mode:'native-server-compile',diagnostics:compiled.warnings.map(w=>w.code)});
}
async function astro(s,file){
  const compiler=s.tools['@astrojs/compiler'];let parsed,compiled;try{parsed=await compiler.parse(file.text,{position:true});compiled=await compiler.transform(file.text,{filename:file.path,sourcemap:'external'});}catch{s.gap(file,'F12','ASTRO_COMPILATION_ERROR');return;}
  const bindings=new Map();for(const n of parsed.ast.children||[])if(n.type==='frontmatter'){
    // Validate this native content against the exact captured source before translating coordinates.
    const begin=file.text.indexOf(n.value,n.position?.start?.offset??0);if(begin<0){s.gap(file,'F12','ASTRO_SOURCE_MAPPING_UNAVAILABLE');continue;}
    for(const [name,value] of imports(s,file,n.value,begin))bindings.set(name,value);
  }
  function walk(n){
    if(n.type==='element'&&n.name==='style')for(const child of n.children||[])if(child.type==='text'){
      const start=child.position?.start?.offset;
      if(Number.isSafeInteger(start)&&file.text.slice(start,start+child.value.length)===child.value)resources.css(s,file,child.value,start);
      else s.gap(file,'F07','ASTRO_STYLE_MAPPING_UNAVAILABLE');
    }
    if(n.type==='component'){
      const start=n.position?.start?.offset;if(Number.isSafeInteger(start)&&file.text.slice(start,start+n.name.length)===n.name)composition(s,file,n.name,start,start+n.name.length,bindings);
      else s.gap(file,'F12','ASTRO_SOURCE_MAPPING_UNAVAILABLE');
      for(const a of n.attributes||[])if(a.name.startsWith('client:')){
        const offset=a.position?.start?.offset;if(Number.isSafeInteger(offset)&&file.text.slice(offset,offset+a.name.length)===a.name)s.fact(file,'F06','HYDRATION_BOUNDARY',offset,offset+a.name.length,null,{directive:a.name,side:'browser',declared:true});else s.gap(file,'F12','ASTRO_SOURCE_MAPPING_UNAVAILABLE');
      }
    }
    for(const child of n.children||[])walk(child);
  }walk(parsed.ast);
  s.fact(file,'F02','COMPONENT_DECLARATION',0,file.text.length,s.id('component',file.path),{framework:'astro',version:'2.13',generated:{artifact:s.artifact(file,'astro-server',compiled.code),mapping:compiled.map?s.artifact(file,'astro-server-map',compiled.map):null}});
  s.native.push({path:file.path,engine:'@astrojs/compiler',mode:'native-transform',diagnostics:(compiled.diagnostics||[]).map(d=>d.code)});
}
function angularAot(s){
  if(!s.profiles.has('angular')||!s.program)return;
  const configured=s.input.context.angularCompilerOptions;
  if(!configured){s.angularChecked=false;return;}
  const allowed=new Set(['strictTemplates','fullTemplateTypeCheck','strictInjectionParameters','strictInputAccessModifiers','compilationMode','strictInputTypes','strictNullInputTypes']);
  if(typeof configured!=='object'||Array.isArray(configured)||Object.keys(configured).some(k=>!allowed.has(k))){
    for(const file of s.files.values())if(file.text!==null&&!['dependency','toolchain'].includes(file.role)&&/\.[cm]?[jt]sx?$/.test(file.path))s.gap(file,'F12','ANGULAR_COMPILER_OPTION_UNREGISTERED');
    s.angularChecked=false;return;
  }
  const host={...s.compilerHost,readResource:p=>s.compilerHost.readFile(p),resourceNameToFileName:(url,from)=>{const p=path.resolve(path.dirname(from),url);return s.compilerHost.fileExists(p)?p:null;}};
  const cli=s.tools['@angular/compiler-cli'],priorFs=cli.getFileSystem(),forbidden=()=>{throw Error('ANGULAR_FILE_WRITE_FORBIDDEN');};
  const vfs={...path,pwd:()=> '/',chdir:forbidden,isCaseSensitive:()=>true,isRoot:p=>p==='/',isRooted:p=>p.startsWith('/'),
    exists:p=>host.fileExists(p)||host.directoryExists(p),readFile:p=>{const text=host.readFile(p);if(text===undefined)throw Error('ANGULAR_CAPTURED_FILE_UNAVAILABLE');return text;},
    readFileBuffer:p=>Buffer.from(vfs.readFile(p)),readdir:p=>[...new Set([...s.files.keys()].filter(k=>('/'+k).startsWith(p.replace(/\/$/,'')+'/')).map(k=>('/'+k).slice(p.replace(/\/$/,'').length+1).split('/')[0]))],
    stat:p=>({isFile:()=>host.fileExists(p),isDirectory:()=>host.directoryExists(p),isSymbolicLink:()=>false}),getDefaultLibLocation:()=>'/toolchain/lib',
    realpath:p=>p,writeFile:forbidden,removeFile:forbidden,symlink:forbidden,copyFile:forbidden,moveFile:forbidden,ensureDir:forbidden,removeDeep:forbidden};vfs.lstat=vfs.stat;
  let ng;
  try{
    cli.setFileSystem(vfs);
    ng=new cli.NgtscProgram(s.rootNames,{...s.compilerOptions,...configured,rootDir:'/',_enableTemplateTypeChecker:true},host);
    const diagnostics=[...ng.getNgOptionDiagnostics(),...ng.getNgStructuralDiagnostics(),...ng.getNgSemanticDiagnostics()];
    for(const d of diagnostics){const file=s.files.get(d.file?.fileName.slice(1));if(file)s.gap(file,'F12','ANGULAR_AOT_DIAGNOSTIC',d.start??0,(d.start??0)+(d.length??0),{code:d.code});}
    s.angularChecked=true;
    const program=ng.getTsProgram(),ts=s.ts,checker=ng.compiler.getTemplateTypeChecker();
    for(const source of program.getSourceFiles()){
      const file=s.files.get(source.fileName.slice(1));if(!file||['dependency','toolchain'].includes(file.role))continue;
      function walk(node){if(ts.isClassDeclaration(node)){
        const template=checker.getTemplate(node);if(template){
          const componentName=node.name?.text||'<anonymous>',seen=new Set();
          function visit(n){if(!n||typeof n!=='object'||seen.has(n))return;seen.add(n);
            if(n.sourceSpan){const sym=checker.getSymbolOfNode(n,node);for(const directive of sym?.directives||[]){const declaration=directive.ref?.node;if(!declaration)continue;
              const raw=n.sourceSpan.start.file.content,start=n.sourceSpan.start.offset,end=n.sourceSpan.end.offset;let original=file,base=0;
              if(raw!==file.text){const exact=[...s.files.values()].filter(f=>f.text===raw);if(exact.length===1)original=exact[0];else{const index=file.text.indexOf(raw);if(index<0||file.text.indexOf(raw,index+1)>=0){s.gap(file,'F12','ANGULAR_TEMPLATE_SOURCE_MAPPING_REQUIRED');continue;}base=index;}}
              s.fact(original,'F02','COMPONENT_USE',base+start,base+end,s.id('angular-component',declaration.getSourceFile().fileName+':'+declaration.name?.text),{framework:'angular',owner:componentName,target:declaration.name?.text||null,binding:'ngtsc-template-checker'});
            }}
            for(const [key,value] of Object.entries(n))if(!['sourceSpan','keySpan','valueSpan','handler','value','i18n'].includes(key))if(Array.isArray(value))value.forEach(visit);else if(value&&typeof value==='object')visit(value);
          }template.forEach(visit);
          s.native.push({path:file.path,engine:'@angular/compiler-cli',mode:configured.strictTemplates?'strictTemplates':'configured-template-check',diagnostics:diagnostics.map(d=>d.code)});
        }
      }ts.forEachChild(node,walk);}walk(source);
    }
  }catch(error){if(s.onProviderError)s.onProviderError(error);s.angularChecked=false;for(const file of s.files.values())if(/\.tsx?$/.test(file.path)&&!['dependency','toolchain'].includes(file.role))s.gap(file,'F12','ANGULAR_AOT_PROVIDER_FAILURE',0,file.text.length,{errorKind:error.constructor.name});}
  finally{cli.setFileSystem(priorFs);}
}
function angular(s){
  if(!s.profiles.has('angular')||!s.program)return;
  const ts=s.ts;
  for(const source of s.program.getSourceFiles()){
    const file=s.files.get(source.fileName.slice(1));if(!file||['dependency','toolchain'].includes(file.role))continue;
    function visit(n){if(ts.isClassDeclaration(n))for(const decorator of ts.getDecorators(n)||[]){
      if(!ts.isCallExpression(decorator.expression))continue;const call=decorator.expression,api=s.binding.api(call.expression);
      if(api?.name!=='@angular/core'||!['Component','Directive','Pipe','Injectable','NgModule'].includes(api.member))continue;
      s.fact(file,'F02','ANGULAR_DECLARATION',n.getStart(source),n.getEnd(),s.binding.identity(n.name),{kind:api.member,version:api.version});
      const options=call.arguments[0];if(!options||!ts.isObjectLiteralExpression(options)){s.gap(file,'F02','DYNAMIC_ANGULAR_METADATA',decorator.getStart(source),decorator.getEnd());continue;}
      for(const property of options.properties){if(!ts.isPropertyAssignment(property))continue;const key=property.name.getText(source).replace(/["']/g,''),value=property.initializer;
        if(['template','templateUrl'].includes(key)&&ts.isStringLiteralLike(value)){
          let template=file,text=value.text,offset=value.getStart(source)+1;
          if(key==='templateUrl'){const target=require('node:path').posix.normalize(require('node:path').posix.join(require('node:path').posix.dirname(file.path),value.text));template=s.files.get(target);text=template?.text;offset=0;if(!template){s.gap(file,'F02','ANGULAR_TEMPLATE_UNAVAILABLE',value.getStart(source),value.getEnd());continue;}}
          if(file.text.slice(offset,offset+text.length)!==text&&template===file){s.gap(file,'F12','ESCAPED_TEMPLATE_SOURCE_MAP_REQUIRED',value.getStart(source),value.getEnd());continue;}
          const parsed=s.tools['@angular/compiler'].parseTemplate(text,template.path,{preserveWhitespaces:true});
          if(parsed.errors?.length){s.gap(template,'F12','ANGULAR_TEMPLATE_PARSE_ERROR',offset,offset+text.length);continue;}
          const seen=new Set();function walk(node){if(!node||typeof node!=='object'||seen.has(node))return;seen.add(node);
            if(node.sourceSpan&&node.name&&node.sourceSpan.start.offset>=0){const start=offset+node.sourceSpan.start.offset,end=offset+node.sourceSpan.end.offset;
              s.fact(template,'F02','ANGULAR_TEMPLATE_BINDING',start,end,null,{name:node.name,owner:s.binding.identity(n.name),kind:node.constructor.name,binding:'syntax-declaration'});}
            for(const [key,value] of Object.entries(node))if(!['sourceSpan','keySpan','valueSpan','handler','value','i18n'].includes(key))if(Array.isArray(value))value.forEach(walk);else if(value&&typeof value==='object')walk(value);
          }parsed.nodes.forEach(walk);s.native.push({path:template.path,engine:'@angular/compiler',mode:'template-parse',diagnostics:[]});
          if(!s.angularChecked)s.gap(template,'F02','ANGULAR_AOT_TEMPLATE_BINDING_REQUIRED',offset,offset+text.length);
        }
      }
    }ts.forEachChild(n,visit);}visit(source);
  }
}
function routes(s){
  for(const file of s.files.values()){
    if(file.text===null||['dependency','toolchain'].includes(file.role))continue;
    for(const id of ['next','nuxt','sveltekit','astro','remix'])if(s.profiles.has(id)){
      if(s.nativeRouteFiles?.has(id+'\0'+file.path))continue;
      let route=null,kind=null;
      if(id==='next'){
        const m=file.path.match(/^(?:src\/)?app\/(.*\/)?(page|layout|route)\.[jt]sx?$/);if(m){route='/'+(m[1]||'').split('/').filter(p=>p&&!/^\(.*\)$/.test(p)).join('/');kind=m[2];}
      }else if(id==='nuxt'){const m=file.path.match(/^(?:app\/)?pages\/(.*)\.vue$/);if(m){route='/'+m[1].replace(/(?:^|\/)index$/,'');kind='page';}}
      else if(id==='sveltekit'){const m=file.path.match(/^src\/routes\/(.*\/)?\+(page|layout|server)(?:\.server)?\.(?:svelte|[jt]s)$/);if(m){route='/'+(m[1]||'').split('/').filter(p=>p&&!/^\(.*\)$/.test(p)).join('/');kind=m[2];}}
      else if(id==='astro'){const m=file.path.match(/^src\/pages\/(.*)\.(?:astro|[jt]s)$/);if(m){route='/'+m[1].replace(/(?:^|\/)index$/,'');kind='page';}}
      else if(id==='remix'){const m=file.path.match(/^app\/routes\/(.*)\.[jt]sx?$/);if(m){route='/'+m[1].replace(/\._index$/,'').replace(/\./g,'/');kind='route';}}
      if(route!==null){
        const row=s.fact(file,'F03','DECLARED_ROUTE',0,file.text.length,s.id('route',id+':'+file.path),{framework:id,route,kind,reachable:'NOT_ESTABLISHED',basePath:'UNKNOWN'},'CONDITIONAL');
        s.gap(file,'F03','NATIVE_ROUTE_MANIFEST_REQUIRED',0,file.text.length,{declaration:row.id});
      }
    }
  }
}
async function analyze(s){for(const file of s.files.values()){
  if(file.text===null||['dependency','toolchain'].includes(file.role))continue;
  if(file.path.endsWith('.vue')){if(s.profiles.has('vue')||s.profiles.has('nuxt'))vue(s,file);else s.gap(file,'F12','VUE_PROFILE_REQUIRED');}
  if(file.path.endsWith('.svelte')){if(s.profiles.has('svelte')||s.profiles.has('sveltekit'))svelte(s,file);else s.gap(file,'F12','SVELTE_PROFILE_REQUIRED');}
  if(file.path.endsWith('.astro')){if(s.profiles.has('astro'))await astro(s,file);else s.gap(file,'F12','ASTRO_PROFILE_REQUIRED');}
  }angularAot(s);angular(s);await require('./routing.cjs').analyze(s);routes(s);
}
module.exports={analyze,profiles,validate,scriptInputs};
