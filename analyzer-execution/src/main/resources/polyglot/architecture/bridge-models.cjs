'use strict';
const fs=require('node:fs'),path=require('node:path');
const {createRequire}=require('node:module');
const {sha,digest,canonical}=require('./common.cjs');
function load(root){
  const pin=require('./bridge-toolchain.json');if(sha(fs.readFileSync(path.join(root,'package-lock.json')))!==pin.lock||require('./tool-tree.cjs').tree(path.join(root,'node_modules')).digest!==pin.tree.digest)throw Error('BRIDGE_NATIVE_TOOL_BYTES_CHANGED');
  const tool=createRequire(path.join(root,'package.json'));for(const [name,version] of Object.entries(pin.versions))if(tool(name+'/package.json').version!==version)throw Error('BRIDGE_NATIVE_TOOL_VERSION');
  return {identity:digest(['architecture-bridge-tools:m8.4-v1',pin]),Parser:tool('@asyncapi/parser').Parser,pbjs:tool('protobufjs-cli').pbjs,pbts:tool('protobufjs-cli').pbts,protobuf:tool('protobufjs')};
}
function receipt(s,file,kind,value){return s.artifact(file,kind,canonical({schema:kind+':m8.4-v1',source:{path:file.path,digest:file.sha256},...value}));}
function endpoint(s,file,source,profile,direction,symbol,descriptor,artifacts,candidates=[],status){
  const span=source?.span,start=span?.start??0,end=span?.end??file.text.length;
  return s.fact(file,profile==='B07'?'F06':'F05','BRIDGE_ENDPOINT',start,end,symbol,{profile,direction,descriptor,artifacts:artifacts.map(a=>a.id),candidates,rangePrecision:source?.attributes?.rangePrecision||'DOCUMENT_SUPPORT',nativeObservation:source?.id??null},status||source?.status||'RESOLVED',source?.supports||[]);
}
function sql(s,config,file){
  for(const model of config.sql||[]){
    if(!Array.isArray(model.files)||typeof model.database!=='string'||typeof model.connection!=='string'||model.dialect!==s.input.context.sqlDialect){s.gap(file,'F05','SQL_BRIDGE_CONTEXT_REQUIRED');continue;}
    const database=receipt(s,file,'declared-database',{identity:model.database,dialect:model.dialect}),connection=receipt(s,file,'declared-connection',{database:database.content.digest,identity:model.connection});
    const providers=new Map(),selected=s.facts.filter(f=>model.files.includes(f.path));
    const descriptor=attrs=>({database:database.content.digest,connection:connection.content.digest,schema:attrs.schema,object:attrs.name,dialect:model.dialect,sourcePrecision:'DOCUMENT_SUPPORT',meaning:'DECLARED_SOURCE_ONLY'});
    const valid=attrs=>typeof attrs.schema==='string'&&/^[a-z_][a-z0-9_]*$/.test(attrs.schema)&&typeof attrs.name==='string'&&/^[a-z_][a-z0-9_]*$/.test(attrs.name);
    for(const row of selected.filter(f=>['SQL_TABLE_DECLARATION','SQL_VIEW_DECLARATION'].includes(f.kind))){const attrs=row.attributes;if(!valid(attrs)){s.gap(s.files.get(row.path),'F05','SQL_BRIDGE_IDENTIFIER_NORMALIZATION_REQUIRED');continue;}const desc=descriptor(attrs),key=attrs.schema+'.'+attrs.name;const provider=endpoint(s,s.files.get(row.path),row,'B05','PROVIDES',s.id('database-object',database.content.digest+':'+key),desc,[database,connection]);if(!providers.has(key))providers.set(key,[]);providers.get(key).push(provider.id);}
    for(const row of selected.filter(f=>f.kind==='SQL_TABLE_ACCESS')){const attrs=row.attributes,operation=attrs.operation?.toLowerCase();const access=operation==='select'?'READS':['insert','update','delete','replace'].includes(operation)?'WRITES':null;if(!access)continue;if(!valid(attrs)){s.gap(s.files.get(row.path),'F05','SQL_BRIDGE_IDENTIFIER_NORMALIZATION_REQUIRED');continue;}endpoint(s,s.files.get(row.path),row,'B05','REQUIRES',s.id('declared-query',database.content.digest+':'+row.path+':'+operation),{...descriptor(attrs),access},[database,connection],providers.get(attrs.schema+'.'+attrs.name)||[]);}
  }
}
function graphql(s,config,file){
  const g=s.tools.graphql;
  for(const model of config.graphql||[]){
    if(!Array.isArray(model.schemas)||!Array.isArray(model.documents)||typeof model.namespace!=='string'||typeof model.version!=='string'||model.schemas.some(path=>!s.files.get(path)?.text)){s.gap(file,'F05','GRAPHQL_BRIDGE_CONTEXT_REQUIRED');continue;}
    const closure=model.schemas.map(path=>({path,digest:s.files.get(path).sha256})).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
    try{const schema=g.buildASTSchema(g.parse(model.schemas.map(path=>s.files.get(path).text).join('\n')));if(g.validateSchema(schema).length)throw Error('invalid');}catch{s.gap(file,'F05','GRAPHQL_BRIDGE_SCHEMA_INVALID');continue;}
    const schema=receipt(s,file,'graphql-schema-closure',{inputs:closure,namespace:model.namespace,version:model.version}),providers=new Map(),original=[...s.facts];
    for(const row of original.filter(f=>f.kind==='GRAPHQL_FIELD_DEFINITION'&&model.schemas.includes(f.path))){const attrs=row.attributes;const lineage=receipt(s,s.files.get(row.path),'schema-field-lineage',{tool:s.tools.identity,schema:schema.content.digest,observation:row.id,meaning:'NATIVE_GRAPHQL_SCHEMA_FIELD'});const desc={schema:schema.content.digest,namespace:model.namespace,version:model.version,service:attrs.owner,operation:attrs.owner+'.'+attrs.field,lineage:lineage.content.digest,sourcePrecision:attrs.rangePrecision||'ORIGINAL_SOURCE_RANGE'};const observed=endpoint(s,s.files.get(row.path),row,'B03','PROVIDES',row.target,desc,[schema,lineage]);if(!providers.has(row.target))providers.set(row.target,[]);providers.get(row.target).push({id:observed.id,desc,lineage});}
    for(const row of original.filter(f=>f.kind==='GRAPHQL_FIELD_REFERENCE'&&model.documents.includes(f.path))){const owners=original.filter(f=>f.path===row.path&&['GRAPHQL_OPERATION','GRAPHQL_DEFINITION'].includes(f.kind)&&f.span?.start<=row.span?.start&&f.span?.end>=row.span?.end).sort((a,b)=>(a.span.end-a.span.start)-(b.span.end-b.span.start));const targets=providers.get(row.target)||[];if(!owners[0]?.target||targets.length!==1){s.gap(s.files.get(row.path),'F05','GRAPHQL_BRIDGE_OWNER_OR_SCHEMA_REQUIRED',row.span?.start,row.span?.end);continue;}const target=targets[0];endpoint(s,s.files.get(row.path),row,'B03','REQUIRES',owners[0].target,target.desc,[schema,target.lineage],[target.id]);}
  }
}
// Parser references are restricted to the captured document before the native resolver runs.
function externalReference(value){if(Array.isArray(value))return value.some(externalReference);if(value&&typeof value==='object')return typeof value.$ref==='string'&&!value.$ref.startsWith('#/')||Object.values(value).some(externalReference);return false;}
async function messaging(s,config,binding){
  for(const model of config.messaging||[]){if(!Array.isArray(model.documents)||model.documents.some(name=>typeof name!=='string')){s.gap(binding,'F05','ASYNCAPI_BRIDGE_CONTEXT_REQUIRED');continue;}for(const name of model.documents){const file=s.files.get(name);
    if(!file?.text||typeof model.namespace!=='string'){s.gap(binding,'F05','ASYNCAPI_BRIDGE_CONTEXT_REQUIRED');continue;}
    if(!s.tools.bridgeNative){s.gap(file,'F05','ASYNCAPI_NATIVE_TOOL_REQUIRED');continue;}
    let raw;try{raw=JSON.parse(file.text);}catch{s.gap(file,'F05','ASYNCAPI_CAPTURED_JSON_REQUIRED');continue;}
    if(externalReference(raw)){s.gap(file,'F05','ASYNCAPI_EXTERNAL_REFERENCE_REQUIRES_CAPTURE');continue;}
    if(raw.asyncapi!=='3.0.0'){s.gap(file,'F05','ASYNCAPI_VERSION_UNSUPPORTED');continue;}
    let parsed;try{parsed=await new s.tools.bridgeNative.Parser().parse(file.text);}catch{s.gap(file,'F05','ASYNCAPI_NATIVE_PARSE_ERROR');continue;}
    if(!parsed.document||parsed.diagnostics.some(d=>d.severity===0)){s.gap(file,'F05','ASYNCAPI_NATIVE_VALIDATION_ERROR');continue;}
    const providers=new Map();
    for(const op of parsed.document.operations())for(const channel of op.channels()){
      const servers=[...channel.servers()].map(server=>({protocol:server.protocol(),host:server.host(),pathname:server.pathname()||'',protocolVersion:server.protocolVersion()||null}));
      if(!channel.address()||channel.parameters().length||servers.length!==1||!['kafka','amqp'].includes(servers[0].protocol)){s.gap(file,'F05','ASYNCAPI_STATIC_BROKER_CHANNEL_REQUIRED');continue;}
      const messages=[...op.messages()].map(message=>({payload:message.payload()?.json()??null,headers:message.headers()?.json()??null}));
      if(!messages.length||messages.some(m=>!m.payload)){s.gap(file,'F05','ASYNCAPI_PAYLOAD_SCHEMA_REQUIRED');continue;}
      // Strip parser bookkeeping rather than incorporating object identity in evidence.
      const clean=value=>Array.isArray(value)?value.map(clean):value&&typeof value==='object'?Object.fromEntries(Object.entries(value).filter(([k])=>!k.startsWith('x-parser-')).map(([k,v])=>[k,clean(v)])):value;
      const schema=receipt(s,file,'asyncapi-message-schema',{messages:clean(messages),version:raw.info.version}),routing=receipt(s,file,'declared-broker-routing',{namespace:model.namespace,servers,channel:channel.address(),meaning:'DECLARED_ONLY'});
      const desc={protocol:servers[0].protocol,namespace:model.namespace,channel:channel.address(),schema:schema.content.digest,routing:routing.content.digest,sourcePrecision:'DOCUMENT_SUPPORT'};
      const key=digest(desc);let provider=providers.get(key);if(!provider){provider=endpoint(s,file,null,'B04','PROVIDES',s.id('message-channel',key),desc,[schema,routing]);providers.set(key,provider);}
      endpoint(s,file,null,'B04','REQUIRES',s.id('message-operation',file.path+':'+op.id()),{...desc,bindingRole:op.isSend()?'PUBLISHES':'SUBSCRIBES'},[schema,routing],[provider.id]);
    }
    s.native.push({path:file.path,engine:'@asyncapi/parser',version:'3.6.6',mode:'captured-asyncapi3-no-network',diagnostics:[]});
  }}
}
function workers(s,config,binding){
  if(!(config.workers||[]).length)return;
  const ts=s.ts,program=s.program,checker=s.checker;if(!ts||!program){s.gap(binding,'F06','WORKER_NATIVE_TYPESCRIPT_REQUIRED');return;}
  const library=(check,node,name,lib)=>{const symbol=check.getSymbolAtLocation(node);return symbol?.getName()===name&&symbol.declarations?.some(d=>d.getSourceFile().fileName==='/toolchain/lib/'+lib);};
  const nativeSignature=(check,node,lib)=>check.getResolvedSignature(node)?.declaration?.getSourceFile().fileName==='/toolchain/lib/'+lib;
  const walk=(node,visit)=>{visit(node);ts.forEachChild(node,n=>walk(n,visit));};
  function shape(check,type,seen=new Set()){
    if(type.flags&ts.TypeFlags.StringLike)return {type:'string'};if(type.flags&ts.TypeFlags.NumberLike)return {type:'number'};if(type.flags&ts.TypeFlags.BooleanLike)return {type:'boolean'};if(type.flags&ts.TypeFlags.Null)return {type:'null'};
    if(type.flags&(ts.TypeFlags.Any|ts.TypeFlags.Unknown|ts.TypeFlags.Never|ts.TypeFlags.TypeParameter)||seen.has(type)||seen.size>=32||type.getCallSignatures().length||type.getConstructSignatures().length)throw Error('unsupported');
    if(type.isUnion())return {oneOf:type.types.map(t=>shape(check,t,new Set([...seen,type]))).sort((a,b)=>canonical(a)<canonical(b)?-1:1)};
    if(check.isArrayType(type))return {type:'array',items:shape(check,check.getTypeArguments(type)[0],new Set([...seen,type]))};
    const properties=type.getProperties();if(!properties.length||type.getStringIndexType()||type.getNumberIndexType())throw Error('unsupported');
    return {type:'object',properties:properties.map(property=>{if(!property.valueDeclaration)throw Error('unsupported');return {name:property.name,optional:!!(property.flags&ts.SymbolFlags.Optional),value:shape(check,check.getTypeOfSymbolAtLocation(property,property.valueDeclaration),new Set([...seen,type]))};}).sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0)};
  }
  for(const model of config.workers||[]){const parent=s.files.get(model.parent),worker=s.files.get(model.worker),source=program.getSourceFile('/'+model.parent);
    if(!parent?.text||!worker?.text||!source||!(s.input.context.workerRoots||[]).includes(model.worker)||typeof model.namespace!=='string'||typeof model.channel!=='string'){s.gap(binding,'F06','WORKER_CAPTURED_CONTEXT_REQUIRED');continue;}
    if(ts.getPreEmitDiagnostics(program).some(d=>d.category===ts.DiagnosticCategory.Error&&d.file===source)){s.gap(parent,'F06','WORKER_PARENT_NATIVE_CONTEXT_DIAGNOSTIC');continue;}
    const registrations=[];
    walk(source,node=>{if(!ts.isVariableDeclaration(node)||!ts.isIdentifier(node.name)||!node.initializer||!ts.isNewExpression(node.initializer))return;const call=node.initializer,url=call.arguments?.[0];
      if(!(node.parent.flags&ts.NodeFlags.Const)||!library(checker,call.expression,'Worker','lib.dom.d.ts')||!nativeSignature(checker,call,'lib.dom.d.ts')||!url||!ts.isNewExpression(url)||!library(checker,url.expression,'URL','lib.dom.d.ts')||!nativeSignature(checker,url,'lib.dom.d.ts'))return;
      const [relative,base]=url.arguments||[];if(!relative||!ts.isStringLiteral(relative)||!base||!ts.isPropertyAccessExpression(base)||base.name.text!=='url'||!ts.isMetaProperty(base.expression)||base.expression.keywordToken!==ts.SyntaxKind.ImportKeyword)return;
      const target=path.posix.normalize(path.posix.join(path.posix.dirname(model.parent),relative.text));if(target===model.worker)registrations.push({node,call,symbol:checker.getSymbolAtLocation(node.name)});
    });
    if(registrations.length!==1){s.gap(parent,'F06','WORKER_NATIVE_REGISTRATION_REQUIRED');continue;}
    const workerProgram=ts.createProgram({rootNames:['/'+model.worker],options:{...s.compilerOptions,lib:['lib.es2022.d.ts','lib.webworker.d.ts'],types:[]},host:s.compilerHost}),workerChecker=workerProgram.getTypeChecker(),workerSource=workerProgram.getSourceFile('/'+model.worker);
    if(!workerSource||ts.getPreEmitDiagnostics(workerProgram).some(d=>d.category===ts.DiagnosticCategory.Error)){s.gap(worker,'F06','WORKER_NATIVE_CONTEXT_DIAGNOSTIC');continue;}
    const receivers=[];
    walk(workerSource,node=>{if(!ts.isBinaryExpression(node)||node.operatorToken.kind!==ts.SyntaxKind.EqualsToken||!ts.isPropertyAccessExpression(node.left)||node.left.name.text!=='onmessage'||!library(workerChecker,node.left.expression,'self','lib.webworker.d.ts')||!ts.isFunctionLike(node.right)||node.right.parameters.length!==1)return;
      const parameter=node.right.parameters[0],type=workerChecker.getTypeAtLocation(parameter),data=type.getProperty('data');
      if(!data?.declarations?.some(d=>d.getSourceFile().fileName==='/toolchain/lib/lib.webworker.d.ts'))return;
      try{receivers.push({node,shape:shape(workerChecker,workerChecker.getTypeOfSymbolAtLocation(data,parameter))});}catch{s.gap(worker,'F06','WORKER_PAYLOAD_STRUCTURAL_TYPE_REQUIRED',node.getStart(workerSource),node.getEnd());}
    });
    if(receivers.length!==1){s.gap(worker,'F06','WORKER_NATIVE_RECEIVER_REQUIRED');continue;}
    const registration=registrations[0],receiver=receivers[0];
    const registered=s.fact(parent,'F06','NATIVE_WORKER_REGISTRATION',registration.call.getStart(source),registration.call.getEnd(),s.id('worker-registration',model.parent+':'+registration.call.pos),{worker:model.worker,engine:'typescript',meaning:'STATIC_CONSTRUCTOR_URL'});
    const receiving=s.fact(worker,'F06','NATIVE_WORKER_RECEIVER',receiver.node.getStart(workerSource),receiver.node.getEnd(),s.id('worker-receiver',model.worker+':'+receiver.node.pos),{engine:'typescript',meaning:'NATIVE_MESSAGE_EVENT_DATA_TYPE'});
    const payload=receipt(s,binding,'worker-payload-schema',{shape:receiver.shape,meaning:'STATIC_STRUCTURAL_COMPATIBILITY'}),registrationBytes=receipt(s,binding,'worker-registration',{parent:{path:parent.path,digest:parent.sha256,observation:registered.id},worker:{path:worker.path,digest:worker.sha256,observation:receiving.id},namespace:model.namespace,channel:model.channel});
    const descriptor={protocol:'browser-worker',namespace:model.namespace,channel:model.channel,payload:payload.content.digest,registration:registrationBytes.content.digest,sourcePrecision:'ORIGINAL_SOURCE_RANGE'};
    const provider=endpoint(s,worker,receiving,'B07','PROVIDES',receiving.target,descriptor,[payload,registrationBytes]);
    walk(source,node=>{if(!ts.isCallExpression(node)||!ts.isPropertyAccessExpression(node.expression)||node.expression.name.text!=='postMessage'||checker.getSymbolAtLocation(node.expression.expression)!==registration.symbol||!nativeSignature(checker,node,'lib.dom.d.ts'))return;
      if(node.arguments.length!==1){s.gap(parent,'F06','WORKER_TRANSFER_LIST_PROFILE_REQUIRED',node.getStart(source),node.getEnd());return;}
      let actual;try{actual=shape(checker,checker.getTypeAtLocation(node.arguments[0]));}catch{s.gap(parent,'F06','WORKER_PAYLOAD_STRUCTURAL_TYPE_REQUIRED',node.getStart(source),node.getEnd());return;}
      if(canonical(actual)!==canonical(receiver.shape)){s.gap(parent,'F06','WORKER_PAYLOAD_SHAPE_MISMATCH',node.getStart(source),node.getEnd());return;}
      const send=s.fact(parent,'F06','NATIVE_WORKER_SEND',node.getStart(source),node.getEnd(),s.id('worker-send',model.parent+':'+node.pos),{registration:registered.id,receiver:receiving.id,meaning:'STATIC_POTENTIAL_MESSAGE'});
      endpoint(s,parent,send,'B07','REQUIRES',send.target,descriptor,[payload,registrationBytes],[provider.id]);
    });
    s.native.push({path:worker.path,engine:'typescript',version:s.tools.typescript.version,mode:'separate-webworker-program-noEmit',diagnostics:[]});
  }
}
function protobufGenerated(s,config,binding){
  for(const model of config.protobuf||[]){const source=s.files.get(model.source),receiptFile=s.files.get(model.receipt);if(!source?.text||!receiptFile?.text||typeof model.version!=='string'){s.gap(binding,'F05','PROTOBUF_GENERATION_CONTEXT_REQUIRED');continue;}
    let record,root,descriptor;
    try{record=JSON.parse(receiptFile.text);if(record.schema!=='authored-native-generation:m8.4-v1'||record.source.path!==source.path||record.source.digest!==source.sha256||!record.outputs||!Object.keys(record.outputs).length)throw Error('receipt');
      for(const [name,hash] of Object.entries(record.outputs))if(s.files.get(name)?.sha256!==hash)throw Error('output');
      const bytes=Buffer.from(record.descriptor.data,'base64');if(bytes.toString('base64')!==record.descriptor.data||sha(bytes)!==record.descriptor.digest)throw Error('descriptor');
      descriptor=s.tools.protobufDescriptor.FileDescriptorSet.decode(bytes);root=s.tools.protobufjs.Root.fromDescriptor(descriptor,{keepCase:true});root.resolveAll();
      const parsed=s.tools.protobufjs.parse(source.text,new s.tools.protobufjs.Root(),{keepCase:true});if(parsed.imports?.length)throw Error('imports');parsed.root.resolveAll();
      const signature=method=>{method.resolve();return {request:method.resolvedRequestType.fullName,response:method.resolvedResponseType.fullName,requestStream:!!method.requestStream,responseStream:!!method.responseStream};};
      function compare(node){for(const child of Object.values(node.nested||{})){if(child.methods){const native=root.lookupService(child.fullName);for(const method of child.methodsArray)if(canonical(signature(method))!==canonical(signature(native.methods[method.name])))throw Error('source-descriptor');}compare(child);}}compare(parsed.root);
    }catch{s.gap(source,'F05','PROTOBUF_GENERATION_BYTES_OR_DESCRIPTOR_MISMATCH');continue;}
    const schemaBytes=s.artifact(source,'protobuf-schema-bytes',source.text);
    const evidence=receipt(s,receiptFile,'protobuf-generation-correspondence',{source:source.sha256,outputs:record.outputs,descriptor:record.descriptor.digest,tools:record.generators,client:record.client,meaning:'CAPTURED_GENERATOR_OUTPUT_NO_RUNTIME_ASSURANCE'}),providers=new Map();
    function visit(node){for(const service of Object.values(node.nested||{})){if(service.methods){for(const method of service.methodsArray){const desc={schema:source.sha256,namespace:service.parent.fullName.replace(/^\./,''),version:model.version,service:service.fullName.replace(/^\./,''),operation:method.name,lineage:evidence.content.digest,sourcePrecision:'DOCUMENT_SUPPORT'};const provider=endpoint(s,source,null,'B03','PROVIDES',s.id('protobuf-method',source.path+':'+service.fullName+':'+method.name),desc,[evidence,schemaBytes]);providers.set('/'+service.fullName.replace(/^\./,'')+'/'+method.name,{provider,desc});}}visit(service);}}visit(root);
    // pbts emits native literal RPC path metadata on its callable type. Resolve that
    // declaration and its literal type through TypeScript, never through call spelling.
    if(s.ts&&s.program)for(const file of s.files.values()){if(['toolchain','dependency'].includes(file.role)||file.path in record.outputs)continue;const native=s.program.getSourceFile('/'+file.path);if(!native)continue;
      const ts=s.ts,check=s.checker;const walk=node=>{if(ts.isCallExpression(node)){const declaration=check.getResolvedSignature(node)?.declaration,origin=declaration?.getSourceFile().fileName.slice(1);
        if(origin&&origin.endsWith('.d.ts')&&Object.hasOwn(record.outputs,origin)){
          const type=check.getTypeAtLocation(node.expression),property=type.getProperty('path'),pathType=property?check.getTypeOfSymbolAtLocation(property,node.expression):null;
          if(property?.declarations?.every(d=>d.getSourceFile().fileName.slice(1)===origin)&&pathType?.isStringLiteral()){
            const target=providers.get(pathType.value);if(target){const observed=s.fact(file,'F05','NATIVE_GENERATED_RPC_CONSUMER',node.getStart(native),node.getEnd(),s.id('rpc-consumer',file.path+':'+node.pos),{generated:origin,rpcPath:pathType.value,meaning:'NATIVE_TYPESCRIPT_RESOLVED_GENERATED_SIGNATURE'},'RESOLVED',[{path:origin,start:declaration.getStart(declaration.getSourceFile()),end:declaration.getEnd()}]);endpoint(s,file,observed,'B03','REQUIRES',observed.target,target.desc,[evidence,schemaBytes],[target.provider.id]);}
          }
        }
      }ts.forEachChild(node,walk);};walk(native);
    }
    for(const name of Object.keys(record.outputs))s.artifact(source,'protobuf-generated:'+name,s.files.get(name).text);
  }
}
async function analyze(s){
  const selected=s.input.context.bridgeModels;if(!selected)return;const file=s.files.get(selected);
  if(!file?.text){const fallback=[...s.files.values()][0];if(fallback)s.gap(fallback,'F05','CAPTURED_BRIDGE_BINDINGS_REQUIRED');return;}
  let config;try{config=JSON.parse(file.text);if(config.schema!=='architecture-bridge-bindings:m8.4-v1')throw Error('schema');}catch{s.gap(file,'F05','BRIDGE_BINDINGS_SCHEMA_UNSUPPORTED');return;}
  for(const key of ['sql','graphql','messaging','workers','protobuf'])if(config[key]!==undefined){if(!Array.isArray(config[key])){s.gap(file,'F05','BRIDGE_BINDINGS_SHAPE_INVALID',0,file.text.length,{profile:key});config[key]=[];}else config[key]=config[key].filter(value=>{if(value&&typeof value==='object'&&!Array.isArray(value))return true;s.gap(file,'F05','BRIDGE_BINDINGS_SHAPE_INVALID',0,file.text.length,{profile:key});return false;});}
  sql(s,config,file);graphql(s,config,file);await messaging(s,config,file);workers(s,config,file);protobufGenerated(s,config,file);
}
module.exports={load,analyze};
