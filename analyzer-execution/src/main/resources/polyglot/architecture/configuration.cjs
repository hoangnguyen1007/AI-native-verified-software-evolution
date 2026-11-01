'use strict';
// Project configuration is syntax/data here. No target import, eval or function execution.
const {logical}=require('./common.cjs');
const UNKNOWN=Symbol('unresolved configuration');
function read(s,model,field='config'){
  const requested=model[field];if(!requested)return null;
  logical(requested);const file=s.files.get(requested),category=field==='routesConfig'?'F03':'F10';
  if(!file||file.text===null||['dependency','toolchain'].includes(file.role))return {complete:false,missing:true};
  const ts=s.tools.typescript||s.tools.routing.typescript,source=ts.createSourceFile(file.path,file.text,ts.ScriptTarget.Latest,true,ts.getScriptKindFromFileName(file.path));
  const bindings=new Map(),imports=new Map(),seen=new Set(),reported=new Set();let complete=true,effects=false,steps=0,expression;
  const supports=[{path:file.path,start:0,end:file.text.length}];
  function gap(node,reason){complete=false;const start=node?.getStart(source)??0,end=node?.getEnd()??file.text.length,key=reason+':'+start+':'+end;
    if(!reported.has(key)){reported.add(key);s.gap(file,category,reason,start,end);}}
  for(const statement of source.statements){
    if(ts.isImportDeclaration(statement)){
      const from=statement.moduleSpecifier.text,clause=statement.importClause;
      if(!clause||!clause.isTypeOnly&&(!clause.namedBindings||!ts.isNamedImports(clause.namedBindings)||clause.namedBindings.elements.some(e=>!e.isTypeOnly))) {
        const registered=field==='routesConfig'&&from==='@react-router/dev/routes'||model.framework==='nuxt'&&from==='nuxt/config';
        if(!registered)effects=true;
      }
      if(clause?.namedBindings&&ts.isNamedImports(clause.namedBindings))for(const item of clause.namedBindings.elements)if(!item.isTypeOnly&&!clause.isTypeOnly)imports.set(item.name.text,{from,name:item.propertyName?.text||item.name.text});
    }else if(ts.isVariableStatement(statement)){
      if(!(statement.declarationList.flags&ts.NodeFlags.Const))effects=true;
      for(const d of statement.declarationList.declarations){if(ts.isIdentifier(d.name)&&d.initializer)bindings.set(d.name.text,d.initializer);else effects=true;}
    }else if(ts.isExportAssignment(statement)&&!statement.isExportEquals)expression=statement.expression;
    else if(ts.isExpressionStatement(statement)){
      const e=statement.expression;
      if(ts.isBinaryExpression(e)&&e.operatorToken.kind===ts.SyntaxKind.EqualsToken&&ts.isPropertyAccessExpression(e.left)&&e.left.expression.getText(source)==='module'&&e.left.name.text==='exports')expression=e.right;
      else if(!ts.isStringLiteral(e))effects=true;
    }else if(!ts.isTypeAliasDeclaration(statement)&&!ts.isInterfaceDeclaration(statement)&&!ts.isEmptyStatement(statement))effects=true;
  }
  function helper(node){
    if(!ts.isIdentifier(node)||bindings.has(node.text))return null;const imported=imports.get(node.text);
    if(field==='routesConfig'&&imported?.from==='@react-router/dev/routes'&&['route','index','layout','prefix'].includes(imported.name))return s.tools.routing.routerConfig[imported.name];
    if(model.framework==='nuxt'&&(imported?.from==='nuxt/config'&&imported.name==='defineNuxtConfig'||node.text==='defineNuxtConfig'&&!bindings.has(node.text)&&!imported))return value=>value;
    return null;
  }
  function audit(node){
    if(ts.isFunctionLike(node))return;
    if(ts.isCallExpression(node)&&!helper(node.expression)||ts.isNewExpression(node)||ts.isAwaitExpression(node)||ts.isDeleteExpression(node)||ts.isPostfixUnaryExpression(node)||ts.isBinaryExpression(node)&&node.operatorToken.kind>=ts.SyntaxKind.FirstAssignment&&node.operatorToken.kind<=ts.SyntaxKind.LastAssignment)effects=true;
    ts.forEachChild(node,audit);
  }
  for(const initializer of bindings.values())audit(initializer);if(expression)audit(expression);
  function value(node,returnLiteral=false){
    if(!node)return UNKNOWN;
    if(++steps>50000){gap(node,'CONFIGURATION_STATIC_WORK_BOUND');return UNKNOWN;}
    if(ts.isParenthesizedExpression(node)||ts.isAsExpression(node)||ts.isSatisfiesExpression(node)||ts.isTypeAssertionExpression(node))return value(node.expression,returnLiteral);
    if(ts.isStringLiteralLike(node))return node.text;
    if(ts.isNumericLiteral(node)){const n=Number(node.text);if(Number.isFinite(n))return n;}
    if(node.kind===ts.SyntaxKind.TrueKeyword)return true;if(node.kind===ts.SyntaxKind.FalseKeyword)return false;if(node.kind===ts.SyntaxKind.NullKeyword)return null;
    if(ts.isPrefixUnaryExpression(node)&&[ts.SyntaxKind.MinusToken,ts.SyntaxKind.PlusToken].includes(node.operator)){const n=value(node.operand);if(typeof n==='number')return node.operator===ts.SyntaxKind.MinusToken?-n:n;}
    if(ts.isIdentifier(node)&&bindings.has(node.text)){
      if(seen.has(node.text)){gap(node,'CONFIGURATION_BINDING_CYCLE');return UNKNOWN;}
      seen.add(node.text);const result=value(bindings.get(node.text),returnLiteral);seen.delete(node.text);return result;
    }
    if(ts.isArrayLiteralExpression(node)){
      const result=[];for(const element of node.elements){if(ts.isSpreadElement(element)){const spread=value(element.expression);if(!Array.isArray(spread)){gap(element,'CONFIGURATION_SPREAD_UNRESOLVED');return UNKNOWN;}result.push(...spread);}else{const item=value(element);if(item===UNKNOWN)return UNKNOWN;result.push(item);}}return result;
    }
    if(ts.isObjectLiteralExpression(node)){
      const result=Object.create(null);
      for(const property of node.properties){
        if(ts.isSpreadAssignment(property)){const spread=value(property.expression);if(!spread||typeof spread!=='object'||Array.isArray(spread)){gap(property,'CONFIGURATION_SPREAD_UNRESOLVED');continue;}Object.assign(result,spread);continue;}
        const name=property.name&&(ts.isIdentifier(property.name)||ts.isStringLiteralLike(property.name)?property.name.text:null);
        if(!name||['__proto__','constructor','prototype'].includes(name)){gap(property,'CONFIGURATION_PROPERTY_UNSUPPORTED');continue;}
        let item;
        if(ts.isPropertyAssignment(property))item=value(property.initializer,['rewrites','redirects','headers'].includes(name)&&model.framework==='next');
        else if(ts.isShorthandPropertyAssignment(property))item=value(property.name);
        else if(ts.isMethodDeclaration(property)&&model.framework==='next'&&['rewrites','redirects','headers'].includes(name))item=literalReturn(property);
        else item=UNKNOWN;
        if(item===UNKNOWN)gap(property,'CONFIGURATION_EXPRESSION_UNSUPPORTED');else result[name]=item;
      }return result;
    }
    if(returnLiteral&&(ts.isArrowFunction(node)||ts.isFunctionExpression(node)))return literalReturn(node);
    if(ts.isCallExpression(node)){
      const fn=helper(node.expression),args=node.arguments.map(a=>value(a));
      if(fn&&args.every(a=>a!==UNKNOWN))try{return fn(...args);}catch{gap(node,'NATIVE_ROUTE_DECLARATION_INVALID');return UNKNOWN;}
    }
    gap(node,'CONFIGURATION_EXPRESSION_UNSUPPORTED');return UNKNOWN;
  }
  function literalReturn(node){
    if(node.parameters.length||!node.body)return UNKNOWN;
    if(ts.isBlock(node.body)){if(node.body.statements.length!==1||!ts.isReturnStatement(node.body.statements[0]))return UNKNOWN;return {staticFunctionResult:value(node.body.statements[0].expression)};}
    return {staticFunctionResult:value(node.body)};
  }
  for(const error of source.parseDiagnostics)gap(error.start===undefined?source:{getStart:()=>error.start,getEnd:()=>error.start+(error.length||0)},'CONFIGURATION_PARSE_ERROR');
  const data=value(expression);if(data===UNKNOWN)gap(expression,'CONFIGURATION_EXPORT_UNAVAILABLE');
  if(bindings.has('module'))effects=true;
  if(effects)gap(source,'CONFIGURATION_EFFECTS_UNMODELED');
  const result={file,value:data===UNKNOWN?null:data,complete,supports,expression};
  s.fact(file,category,field==='routesConfig'?'FRAMEWORK_ROUTE_CONFIGURATION':'FRAMEWORK_CONFIGURATION',expression?.getStart(source)??0,expression?.getEnd()??file.text.length,s.id('configuration',model.framework+':'+file.path),{framework:model.framework,projection:'typescript-static-source-data',sourceDigest:file.sha256,complete},complete?'RESOLVED':'PARTIAL');
  s.native.push({path:file.path,engine:'typescript',version:'5.9.3',mode:'static-configuration-no-target-evaluation',diagnostics:[...reported].map(k=>k.split(':')[0]).sort()});
  return result;
}
module.exports={read};
