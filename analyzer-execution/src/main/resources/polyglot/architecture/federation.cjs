'use strict';
const path=require('node:path').posix;
const {sha,logical}=require('./common.cjs');
async function analyze(s){
  const models=s.input.context.federationModels;if(!Array.isArray(models)||!models.length)return;
  const inputs=[...s.files.values()].filter(f=>f.text!==null&&!['dependency','toolchain'].includes(f.role)),remotes=[];
  for(const model of models){
    const file=s.files.get(model?.config);if(!file||file.text===null)continue;
    if(!s.tools.routing?.webpack){s.gap(file,'F09','FEDERATION_NATIVE_TOOL_REQUIRED');continue;}
    try{
      logical(model.config);if(model.profile!=='webpack5'||Object.keys(model).some(k=>!['profile','config'].includes(k)))throw Error('FEDERATION_PROFILE_UNSUPPORTED');
      const config=require('./configuration.cjs').read(s,{...model,framework:'webpack5'});
      if(!config?.complete){s.gap(file,'F09','FEDERATION_OPTIONS_UNRESOLVED');continue;}
      const options=config.value,webpack=s.tools.routing.webpack();
      // Construct the pinned SDK's validator only. Never apply a plugin or compile target code.
      new webpack.container.ModuleFederationPlugin(options);
      s.fact(file,'F09','MODULE_FEDERATION_DECLARATION',0,file.text.length,s.id('federation',file.path),{declaredName:options.name||null,webpackVersion:webpack.version,activation:'NOT_ESTABLISHED'});
      if(Array.isArray(options.exposes))s.gap(file,'F09','FEDERATION_EXPOSE_NORMALIZATION_REQUIRED');
      for(const [name,entry] of Object.entries(Array.isArray(options.exposes)?{}:options.exposes||{})){
        const values=typeof entry==='string'||Array.isArray(entry)?entry:entry?.import;
        for(const request of Array.isArray(values)?values:[values])if(typeof request==='string'){
          if(request.startsWith('.'))s.reference(file,'F09','FEDERATION_EXPOSE',0,file.text.length,request,{name,activation:'DECLARED_ONLY'});
          else{s.fact(file,'F09','FEDERATION_EXPOSE',0,file.text.length,null,{name,requestDigest:sha(request),activation:'NOT_ESTABLISHED'},'CONDITIONAL');s.gap(file,'F09','NATIVE_BUNDLER_MODULE_RESOLUTION_REQUIRED');}
        }
      }
      if(Array.isArray(options.remotes))s.gap(file,'F09','FEDERATION_REMOTE_NORMALIZATION_REQUIRED');
      else for(const [name,entry] of Object.entries(options.remotes||{})){
        const requests=typeof entry==='string'||Array.isArray(entry)?entry:entry?.external;
        for(const request of Array.isArray(requests)?requests:[requests])if(typeof request==='string'){
          const id=s.id('federation-remote',file.path+':'+name);if(!remotes.some(r=>r.id===id))remotes.push({name,id,file});
          s.fact(file,'F09','FEDERATION_REMOTE_DECLARATION',0,file.text.length,id,{name,requestDigest:sha(request),deployedContent:'NOT_CAPTURED'},'CONDITIONAL');
        }
      }
      const shared=Array.isArray(options.shared)?Object.fromEntries(options.shared.filter(v=>typeof v==='string').map(v=>[v,{}])):options.shared||{};
      if(Array.isArray(options.shared)&&options.shared.some(v=>typeof v!=='string'))s.gap(file,'F09','FEDERATION_SHARED_NORMALIZATION_REQUIRED');
      for(const [name,settings] of Object.entries(shared))s.fact(file,'F09','FEDERATION_SHARED_DEPENDENCY',0,file.text.length,null,{name,singleton:typeof settings?.singleton==='boolean'?settings.singleton:null,requiredVersionDigest:typeof settings?.requiredVersion==='string'?sha(settings.requiredVersion):null,runtimeInstance:'NOT_ESTABLISHED'},'CONDITIONAL');
      s.native.push({path:file.path,engine:'webpack.ModuleFederationPlugin',version:webpack.version,mode:'native-options-schema-no-plugin-application',diagnostics:[]});
    }catch(error){s.gap(file,'F09','FEDERATION_OPTIONS_INVALID',0,file.text.length,{errorKind:error.constructor.name});}
  }
  const ts=s.tools.typescript||s.tools.routing?.typescript;if(!ts)return;
  for(const file of inputs.filter(f=>/\.[cm]?[jt]sx?$/.test(f.path))){
    const ast=ts.createSourceFile(file.path,file.text,ts.ScriptTarget.Latest,true,ts.getScriptKindFromFileName(file.path));
    function visit(node){
      const spec=ts.isImportDeclaration(node)||ts.isExportDeclaration(node)?node.moduleSpecifier:ts.isCallExpression(node)&&node.expression.kind===ts.SyntaxKind.ImportKeyword?node.arguments[0]:null;
      if(spec&&ts.isStringLiteralLike(spec)){
        const split=spec.text.indexOf('/'),name=split<0?spec.text:spec.text.slice(0,split),matches=remotes.filter(r=>r.name===name&&!path.relative(path.dirname(r.file.path),file.path).startsWith('../'));
        const clause=node.importClause,typeOnly=!!clause?.isTypeOnly||!!node.isTypeOnly||!!(clause&&!clause.name&&clause.namedBindings&&ts.isNamedImports(clause.namedBindings)&&clause.namedBindings.elements.length&&clause.namedBindings.elements.every(e=>e.isTypeOnly));
        for(const remote of matches)s.fact(file,'F09','FEDERATED_MODULE_REFERENCE',spec.getStart(ast),spec.getEnd(),remote.id,{remote:name,moduleDigest:sha(spec.text),typeOnly,deployedContent:'NOT_CAPTURED'},matches.length===1?'CONDITIONAL':'CANDIDATES',[{path:remote.file.path,start:0,end:remote.file.text.length}]);
      }
      ts.forEachChild(node,visit);
    }visit(ast);
  }
}
module.exports={analyze};
