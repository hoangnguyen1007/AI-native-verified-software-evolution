'use strict';
// Platform-owned extractor. Target files exist only in a captured virtual filesystem.
// Deploy inside a registered semantic worker. Host tests use authored fixtures only.
const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');
const pin = require('./typescript-tool.json');
const sha = bytes => 'sha256:' + crypto.createHash('sha256').update(bytes).digest('hex');
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  if (value === undefined || typeof value === 'number' && !Number.isSafeInteger(value)) throw Error('NON_CANONICAL_VALUE');
  return JSON.stringify(value);
}
const digest = value => sha(Buffer.from(canonical(value), 'utf8'));
function compiler(modulePath) {
  if (sha(fs.readFileSync(modulePath)) !== 'sha256:' + pin.compilerSha256) throw Error('TYPESCRIPT_TOOL_PIN_MISMATCH');
  const ts = require(modulePath);
  if (ts.version !== pin.version) throw Error('TYPESCRIPT_VERSION_MISMATCH');
  return ts;
}
function logical(name) {
  if (typeof name !== 'string' || !name || name.includes('\\') || name.includes('\0') || name.startsWith('/') || name.split('/').some(s => !s || s === '..' || s === '.')) throw Error('INVALID_LOGICAL_PATH');
  return name;
}
function wellFormed(text) {
  for (let i=0;i<text.length;i++) {
    const c=text.charCodeAt(i);
    if (c>=0xd800&&c<=0xdbff) { const low=text.charCodeAt(++i); if (!(low>=0xdc00&&low<=0xdfff)) throw Error('INVALID_SOURCE_UNICODE'); }
    else if(c>=0xdc00&&c<=0xdfff) throw Error('INVALID_SOURCE_UNICODE');
  }
}
function analyze(input, ts) {
  if(input.schema!=='typescript-capture-request:m8.4-v1' || !/^sha256:[0-9a-f]{64}$/.test(input.scope) || !input.repositoryScope || !input.packageScope) throw Error('INVALID_CAPTURE_REQUEST');
  const files=new Map();let inputBytes=0;
  for(const item of input.files) {
    logical(item.path);wellFormed(item.text);const bytes=Buffer.from(item.text,'utf8');inputBytes+=bytes.length;
    if(inputBytes>64*1024*1024) throw Error('COMPILATION_CONTEXT_WORKER_REQUIRED');
    if(sha(bytes)!==item.sha256 || files.has('/'+item.path)) throw Error('CAPTURED_SOURCE_HASH_OR_DUPLICATE');
    files.set('/'+item.path,item);
  }
  const roots=input.projectFiles.map(name=>'/'+logical(name));
  if(new Set(roots).size!==roots.length || roots.some(name=>!files.has(name))) throw Error('PROJECT_MEMBERSHIP_INVALID');
  const rootSet=new Set(roots);
  if(input.options && ('plugins' in input.options || 'outDir' in input.options || 'outFile' in input.options)) throw Error('EXECUTION_OR_EMIT_OPTION_FORBIDDEN');
  const converted=ts.convertCompilerOptionsFromJson(input.options||{},'/');
  if(converted.errors.length) throw Error('COMPILER_OPTIONS_INVALID');
  const options={...converted.options,noEmit:true};
  const host={
    getSourceFile(name,version){const item=files.get(path.posix.normalize(name));return item?ts.createSourceFile(name,item.text,version,true,ts.getScriptKindFromFileName(name)):undefined;},
    getDefaultLibFileName(){return '/toolchain/lib/lib.es2022.full.d.ts';},
    writeFile(){throw Error('EMISSION_FORBIDDEN');},getCurrentDirectory(){return '/';},getDirectories(){return [];},
    fileExists(name){return files.has(path.posix.normalize(name));},readFile(name){return files.get(path.posix.normalize(name))?.text;},
    directoryExists(name){name=path.posix.normalize(name).replace(/\/$/,'')+'/';return [...files.keys()].some(file=>file.startsWith(name));},
    realpath(name){return path.posix.normalize(name);},getCanonicalFileName(name){return name;},useCaseSensitiveFileNames(){return true;},getNewLine(){return '\n';}
  };
  const program=ts.createProgram({rootNames:roots,options,host});const checker=program.getTypeChecker();
  const diagnostics=ts.getPreEmitDiagnostics(program).map(d=>({path:d.file?d.file.fileName.slice(1):null,start:d.start??null,length:d.length??null,code:d.code,category:ts.DiagnosticCategory[d.category],message:ts.flattenDiagnosticMessageText(d.messageText,'\n')}));
  const documents=[],census=[],observations=[];
  const declarationKinds=new Set(['VariableDeclaration','Parameter','FunctionDeclaration','MethodDeclaration','MethodSignature','ClassDeclaration','InterfaceDeclaration','TypeAliasDeclaration','EnumDeclaration','EnumMember','PropertyDeclaration','PropertySignature','FunctionExpression','ArrowFunction','Constructor','ModuleDeclaration','TypeParameter'].map(k=>ts.SyntaxKind[k]));
  function originalRange(source,node) {
    const start=node.getStart(source),end=node.getEnd();const a=source.getLineAndCharacterOfPosition(start),b=source.getLineAndCharacterOfPosition(end);
    return {start,end,startLine:a.line+1,startColumn:a.character+1,endLine:b.line+1,endColumn:b.character+1};
  }
  function symbolInfo(symbol) {
    if(!symbol)return null;
    if(symbol.flags&ts.SymbolFlags.Alias) {try{symbol=checker.getAliasedSymbol(symbol);}catch{return null;}}
    const declarations=symbol.declarations||[];if(!declarations.length)return null;
    const declaration=declarations[0],source=declaration.getSourceFile();
    const owner=[];let parent=declaration.parent;
    while(parent&&!ts.isSourceFile(parent)){if(parent.name&&typeof parent.name.getText==='function')owner.push(parent.name.getText(source));else if(ts.isBlock(parent))owner.push('block@'+parent.pos);parent=parent.parent;}
    const nativeName=checker.getFullyQualifiedName(symbol);
    const signature=ts.SyntaxKind[declaration.kind]+':'+nativeName;
    const id=digest(['language-symbol:m8.4-v1',input.language||'typescript',input.repositoryScope,input.packageScope,source.fileName+':'+owner.reverse().join('/'),signature]);
    return {id,name:symbol.getName(),signature,path:source.fileName.slice(1),origin:rootSet.has(source.fileName)?'PROJECT':source.fileName.startsWith('/toolchain/lib/')?'PLATFORM':'DEPENDENCY',span:originalRange(source,declaration)};
  }
  function ownerInfo(node,source){
    for(let parent=node.parent;parent;parent=parent.parent)if(ts.isFunctionLike(parent)){
      const symbol=parent.name?checker.getSymbolAtLocation(parent.name):parent.symbol;
      const info=symbolInfo(symbol);if(info)return info.id;
      return digest(['lexical-callable:m8.4-v1',input.repositoryScope,input.packageScope,source.fileName,parent.getStart(source),ts.SyntaxKind[parent.kind]]);
    }
    return digest(['module-owner:m8.4-v1',input.repositoryScope,input.packageScope,source.fileName]);
  }
  function sites(source) {
    const result=[];const add=(category,role,node)=>result.push({category,role,node});
    add('A01','source-membership',source);add('A11','diagnostics',source);add('A12','document-accounting',source);
    function walk(node) {
      if(declarationKinds.has(node.kind)){add('A02','declaration',node);add('A04','inferred-declaration-type',node);}
      if(ts.isIdentifier(node)&&!(node.parent?.name===node&&declarationKinds.has(node.parent.kind)))add('A03','reference',node);
      if(ts.isTypeNode(node))add('A04','type-use',node);
      if(ts.isExpressionWithTypeArguments(node))add('A05','heritage',node);
      if(ts.isCallExpression(node)||ts.isNewExpression(node))add('A06',ts.isNewExpression(node)?'construct':'call',node);
      if(ts.isIdentifier(node)&&!(node.parent?.name===node&&declarationKinds.has(node.parent.kind))) {
        let access=node;if(ts.isPropertyAccessExpression(node.parent)&&node.parent.name===node)access=node.parent;
        const parent=access.parent;
        const assignment=ts.isBinaryExpression(parent)&&parent.left===access&&parent.operatorToken.kind>=ts.SyntaxKind.FirstAssignment&&parent.operatorToken.kind<=ts.SyntaxKind.LastAssignment;
        const update=(ts.isPrefixUnaryExpression(parent)||ts.isPostfixUnaryExpression(parent))&&(parent.operator===ts.SyntaxKind.PlusPlusToken||parent.operator===ts.SyntaxKind.MinusMinusToken);
        if(assignment||update)add('A07','write',node);
        if(!assignment||parent.operatorToken.kind!==ts.SyntaxKind.EqualsToken)add('A07','read',node);
      }
      if(ts.isDecorator(node))add('A08','decorator',node);
      if((ts.isImportDeclaration(node)||ts.isExportDeclaration(node))&&node.moduleSpecifier)add('A09','module-boundary',node.moduleSpecifier);
      if(ts.isIfStatement(node)||ts.isConditionalExpression(node))add('A10','guard',node.expression||node.condition);
      ts.forEachChild(node,walk);
    }
    walk(source);
    const present=new Set(result.map(site=>site.category));
    if(present.has('A08'))result.push({category:'A08',role:'implicit-generation-census',node:source});
    for(let i=1;i<=12;i++){const category='A'+String(i).padStart(2,'0');if(!present.has(category))result.push({category,role:'absent-category',node:source});}
    return result;
  }
  function keyOf(source,site) {return source.fileName.slice(1)+':'+String(site.node.getStart(source)).padStart(12,'0')+':'+String(site.node.getEnd()).padStart(12,'0')+':'+site.category+':'+site.role;}
  function obligation(source,site) {return {kind:'CENSUS',key:keyOf(source,site),scope:input.scope,fields:{document:source.fileName.slice(1),category:site.category,start:site.node.getStart(source),end:site.node.getEnd(),role:site.role}};}
  for(const root of [...roots].sort()) {
    const source=program.getSourceFile(root);if(!source)throw Error('PROJECT_DOCUMENT_DROPPED');const item=files.get(root);
    documents.push({kind:'DOCUMENT',key:root.slice(1),scope:input.scope,fields:{content:{digest:item.sha256,size:Buffer.byteLength(item.text,'utf8')},language:input.language||'typescript',role:item.role||'main'}});
    // Census traversal is performed independently of semantic emission, including missing/unsupported bindings.
    for(const site of sites(source))census.push(obligation(source,site));
    for(const site of sites(source)) {
      const node=site.node,expected=obligation(source,site);let targets=[],status='RESOLVED',type=null,reason=null,virtualDispatch=false;
      const problems=diagnostics.filter(d=>d.path===root.slice(1));let nativeTarget;
      if(['absent-category','implicit-generation-census'].includes(site.role)&&site.category==='A08'){status='UNSUPPORTED';reason='NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED';}
      else if(site.role==='absent-category')status='NOT_APPLICABLE';
      else if(site.category==='A11')status=problems.some(d=>d.category==='Error')?'ERROR':'RESOLVED';
      else if(['A01','A12','A10'].includes(site.category)){}
      else {
        if(site.category==='A06') {
          const sig=checker.getResolvedSignature(node),declaration=sig?.getDeclaration();
          nativeTarget=declaration?.name?checker.getSymbolAtLocation(declaration.name):declaration?.symbol;
          virtualDispatch=!!declaration&&(ts.isMethodDeclaration(declaration)||ts.isMethodSignature(declaration))&&!declaration.modifiers?.some(m=>m.kind===ts.SyntaxKind.StaticKeyword)&&!ts.isPrivateIdentifier(declaration.name);
          // Recovery/any signatures without a declaration never certify a target.
          if(!declaration)status='UNRESOLVED';
        }else if(site.category==='A09')nativeTarget=checker.getSymbolAtLocation(node);
        else if(site.category==='A05')nativeTarget=checker.getSymbolAtLocation(node.expression);
        else if(site.category==='A02')nativeTarget=node.name?checker.getSymbolAtLocation(node.name):node.symbol;
        else if(site.category==='A08')nativeTarget=checker.getSymbolAtLocation(ts.isCallExpression(node.expression)?node.expression.expression:node.expression);
        else nativeTarget=checker.getSymbolAtLocation(node);
        const target=symbolInfo(nativeTarget);if(target)targets=[target];
        if(site.category==='A04'){const t=checker.getTypeAtLocation(node);type=checker.typeToString(t);if(t.flags&(ts.TypeFlags.Any|ts.TypeFlags.Unknown))status='UNRESOLVED';}
        else if(!targets.length)status='UNRESOLVED';
        if(site.category==='A07'){
          let valueSymbol=nativeTarget;if(valueSymbol?.flags&ts.SymbolFlags.Alias)valueSymbol=checker.getAliasedSymbol(valueSymbol);
          let erased=false;
          for(let parent=node.parent;parent&&!ts.isSourceFile(parent);parent=parent.parent){
            if(ts.isTypeNode(parent)||ts.isImportDeclaration(parent)||ts.isImportSpecifier(parent)||ts.isExportSpecifier(parent)||ts.isInterfaceDeclaration(parent)||ts.isTypeAliasDeclaration(parent)){erased=true;break;}
            if(ts.isStatement(parent))break;
          }
          if(erased||valueSymbol&&!(valueSymbol.flags&ts.SymbolFlags.Value)){status='NOT_APPLICABLE';targets=[];}
        }
        if(site.category==='A06'&&problems.some(d=>d.category==='Error'&&d.start!==null&&d.start<node.getEnd()&&d.start+(d.length||1)>node.getStart(source))) {status='ERROR';targets=[];}
        if(site.category==='A06'&&status==='RESOLVED'&&virtualDispatch){status='CONDITIONAL';reason='TYPESCRIPT_VIRTUAL_RUNTIME_TARGET_NOT_ESTABLISHED';}
      }
      const fields={obligation:digest(expected),status,reason,targets:targets.map(t=>t.id),symbols:targets,caller:ownerInfo(node,source),type,span:originalRange(source,node),diagnostics:site.category==='A11'?problems:[]};if(site.category==='A10'&&site.role!=='absent-category')fields.truth='UNKNOWN';if(status==='CONDITIONAL')fields.uncertainty=[reason];
      observations.push({kind:'OBSERVATION',key:expected.key,scope:input.scope,fields});
    }
  }
  const sorted=rows=>rows.sort((a,b)=>a.key<b.key?-1:a.key>b.key?1:0);
  return {schema:'typescript-capture-result:m8.4-v2',language:input.language||'typescript',engine:pin.version,tool:'sha256:'+pin.compilerSha256,extractor:sha(fs.readFileSync(__filename)),trust:'PRODUCER_ASSERTION',scope:input.scope,
    diagnostics,documents:sorted(documents),census:sorted(census),observations:sorted(observations)};
}
module.exports={analyze,compiler,canonical,digest,pin};
if(require.main===module){
  try {
    const modulePath=process.argv[2];if(!modulePath)throw Error('PINNED_TYPESCRIPT_MODULE_REQUIRED');
    let bytes=0;const chunks=[];
    process.stdin.on('data',chunk=>{bytes+=chunk.length;if(bytes>80*1024*1024){process.stderr.write('COMPILATION_CONTEXT_WORKER_REQUIRED\n');process.exit(2);}chunks.push(chunk);});
    process.stdin.on('end',()=>{
      try {const input=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks)));const result=analyze(input,compiler(modulePath));
        process.stdout.write(canonical({schema:result.schema,scope:result.scope,language:result.language,engine:result.engine,tool:result.tool,extractor:result.extractor,trust:result.trust})+'\n');
        for(const family of ['documents','census','observations'])for(const row of result[family])process.stdout.write(canonical(row)+'\n');
        process.stdout.write(canonical({schema:'typescript-capture-end:m8.4-v1',scope:result.scope,documents:result.documents.length,census:result.census.length,observations:result.observations.length,diagnostics:result.diagnostics})+'\n');
      } catch(error) {process.stderr.write('TYPESCRIPT_CAPTURE_FAILED\n');process.exitCode=2;}
    });
  }catch(error){process.stderr.write('TYPESCRIPT_CAPTURE_SETUP_FAILED\n');process.exitCode=2;}
}
