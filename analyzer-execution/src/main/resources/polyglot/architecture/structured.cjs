'use strict';
const {sha}=require('./common.cjs');
const path=require('node:path').posix;
const family=file=>/\.proto$/.test(file.path)?'protobuf':/\.(?:graphql|gql)$/.test(file.path)?'graphql':/\.sql$/.test(file.path)?'sql':/\.(?:xml|xsd|jsp|jspx)$/.test(file.path)?'xml':/\.toml$/.test(file.path)?'toml':/\.(?:json|jsonc)$/.test(file.path)?'json':/\.ya?ml$/.test(file.path)?'yaml':/(?:^|\/)(?:\.env(?:\..*)?|.*\.properties)$/.test(file.path)?'properties':null;
function parse(s,file,type){
  if(type==='json'){
    const errors=[],tree=s.tools['jsonc-parser'].parseTree(file.text,errors,{allowTrailingComma:file.path.endsWith('.jsonc'),disallowComments:!file.path.endsWith('.jsonc')});
    if(errors.length||!tree)throw Error('STRUCTURED_PARSE_ERROR');
    function unique(node){if(node.type==='object'){const names=new Set();for(const p of node.children||[]){const key=p.children[0].value;if(names.has(key))throw Error('DUPLICATE_CONFIG_KEY');names.add(key);}}for(const child of node.children||[])unique(child);}unique(tree);
    return {value:s.tools['jsonc-parser'].getNodeValue(tree),tree};
  }
  if(type==='yaml'){
    const doc=s.tools.yaml.parseDocument(file.text,{uniqueKeys:true,merge:false,keepSourceTokens:true});if(doc.errors.length)throw Error('STRUCTURED_PARSE_ERROR');
    const value=doc.toJS({maxAliasCount:50});return {value,tree:doc.contents};
  }
  if(type==='toml')return {value:s.tools['@iarna/toml'].parse(file.text)};
}
function position(s,file,tree,pointer,type){
  if(type==='json'){const n=s.tools['jsonc-parser'].findNodeAtLocation(tree,pointer);if(n)return [n.offset,n.offset+n.length];}
  if(type==='yaml'){let n=tree;for(const key of pointer)n=n?.get?.(key,true);if(n?.range)return [n.range[0],n.range[1]];}
  return [0,file.text.length];
}
function structured(s){
  const configs=new Map(),selectedGraphql=new Set();
  for(const selection of Object.values(s.input.context.graphqlContexts||{}))if(selection&&typeof selection==='object')for(const names of [selection.schema,selection.fragments])if(Array.isArray(names))for(const name of names)if(typeof name==='string')selectedGraphql.add(name);
  for(const file of s.files.values()){
    const type=family(file)||(selectedGraphql.has(file.path)?'graphql':null);if(file.text===null||!type||['dependency','toolchain'].includes(file.role))continue;
    try{
      if(['json','yaml','toml'].includes(type)){
        const parsed=parse(s,file,type);configs.set(file.path,{...parsed,file,type});s.native.push({path:file.path,engine:type==='json'?'jsonc-parser':type==='yaml'?'yaml':'@iarna/toml',mode:'data-only',diagnostics:[]});
      }else if(type==='xml')xml(s,file);
      else if(type==='graphql')graphql(s,file);
      else if(type==='sql')sql(s,file);
      else if(type==='protobuf')protobuf(s,file);
      else properties(s,file);
    }catch(error){s.gap(file,'F12',error.message==='DUPLICATE_CONFIG_KEY'?error.message:'STRUCTURED_PARSE_ERROR',0,file.text.length,{family:type});}
  }
  const bases=new Map(),ambiguousBases=new Set();
  for(const [name,c] of configs){
    const id=c.value?.$id;if(typeof id==='string'){try{const url=new URL(id,'https://captured.invalid/'+name).href;if(ambiguousBases.has(url))s.gap(c.file,'F10','SCHEMA_BASE_URI_CONFLICT');else if(bases.has(url)){s.gap(c.file,'F10','SCHEMA_BASE_URI_CONFLICT');s.gap(bases.get(url).file,'F10','SCHEMA_BASE_URI_CONFLICT');bases.delete(url);ambiguousBases.add(url);}else bases.set(url,c);}catch{s.gap(c.file,'F10','SCHEMA_BASE_URI_INVALID');}}
  }
  for(const [name,c] of configs){
    const {file,type,tree}=c;const at=pointer=>position(s,file,tree,pointer,type);let dialect='config',unsupportedDialect=false;
    const openapi=typeof c.value?.openapi==='string'&&/^3\.[01]\.\d+$/.test(c.value.openapi)?c.value.openapi:null;
    if(c.value?.openapi!==undefined){if(openapi)dialect=openapi;else{dialect='UNSUPPORTED_OPENAPI';unsupportedDialect=true;s.gap(file,'F05','OPENAPI_VERSION_UNSUPPORTED');}}
    if(c.value?.$schema!==undefined){if(['https://json-schema.org/draft/2020-12/schema','https://json-schema.org/draft/2019-09/schema','http://json-schema.org/draft-07/schema#'].includes(c.value.$schema))dialect=c.value.$schema;
      else{dialect='UNSUPPORTED_JSON_SCHEMA';unsupportedDialect=true;s.gap(file,'F10','JSON_SCHEMA_DIALECT_UNSUPPORTED');}}
    s.fact(file,'F10','CONFIG_DOCUMENT',0,file.text.length,null,{family:type,dialect,...(unsupportedDialect?{unsupportedDialectSource:file.sha256}:{})});
    function walk(value,pointer=[],base='https://captured.invalid/'+name,ancestors=new Set()){
      if(!value||typeof value!=='object')return;
      if(ancestors.has(value)){s.gap(file,'F10','CYCLIC_CONFIG_ALIAS',...at(pointer));return;}
      ancestors=new Set([...ancestors,value]);
      if(typeof value.$id==='string')try{base=new URL(value.$id,base).href;}catch{}
      if(typeof value.$ref==='string'){
        const [start,end]=at([...pointer,'$ref']);let target=null,reason='SCHEMA_REFERENCE_UNAVAILABLE';
        try{
          const resolved=new URL(value.$ref,base),fragment=decodeURIComponent(resolved.hash.slice(1));resolved.hash='';
          if(ambiguousBases.has(resolved.href))reason='SCHEMA_BASE_URI_AMBIGUOUS';
          let other=ambiguousBases.has(resolved.href)?null:resolved.origin==='https://captured.invalid'?configs.get(decodeURIComponent(resolved.pathname.slice(1))):bases.get(resolved.href);
          if(other){let current=other.value;if(fragment.startsWith('/'))for(const segment of fragment.slice(1).split('/')){const key=segment.replace(/~1/g,'/').replace(/~0/g,'~');current=current&&Object.hasOwn(current,key)?current[key]:undefined;}
            else if(fragment){const matches=[];function anchors(v,p=[]){if(v&&typeof v==='object'){if(v.$anchor===fragment||v.$dynamicAnchor===fragment)matches.push(p);for(const [k,x] of Object.entries(v))anchors(x,[...p,k]);}}anchors(current);if(matches.length!==1){current=undefined;reason=matches.length?'SCHEMA_ANCHOR_AMBIGUOUS':'SCHEMA_ANCHOR_UNAVAILABLE';}}
            if(current!==undefined)target=other.file.path+'#'+fragment;
          }
        }catch{reason='SCHEMA_REFERENCE_INVALID';}
        s.fact(file,'F05','SCHEMA_REFERENCE',start,end,target,{dialect,referenceDigest:sha(Buffer.from(value.$ref)),resolution:'captured-resources-only'},target?'RESOLVED':'UNRESOLVED');
        if(!target)s.gap(file,'F05',reason,start,end);
      }
      for(const [key,member] of Object.entries(value)){
        const p=[...pointer,key],[start,end]=at(p);
        s.fact(file,'F10','CONFIG_KEY',start,end,null,{keyPath:p.map(String),valueKind:Array.isArray(member)?'array':member===null?'null':typeof member});
        if(pointer.length===2&&pointer[0]==='paths'&&/^(get|put|post|delete|options|head|patch|trace)$/.test(key)&&openapi)s.fact(file,'F05','API_OPERATION',start,end,s.id('operation',name+':'+pointer[1]+':'+key),{method:key.toUpperCase(),route:pointer[1],declared:true,version:openapi});
        if(['imports','include','includes','extends'].includes(key))for(const ref of Array.isArray(member)?member:[member])if(typeof ref==='string')s.reference(file,'F10','CONFIG_INCLUDE',start,end,ref);
        walk(member,p,base,ancestors);
      }
    }
    walk(c.value);
    require('./contract-models.cjs').openapi(s,c,at);
    declaredInfrastructure(s,c,at);
  }
  require('./contract-models.cjs').packages(s,configs);
  require('./schema-models.cjs').migrations(s);
}
function declaredInfrastructure(s,c,at){
  const {file,value}=c;if(!value||typeof value!=='object')return;
  if(value.services&&typeof value.services==='object')for(const [name,service] of Object.entries(value.services)){
    const [start,end]=at(['services',name]);s.fact(file,'F09','DECLARED_SERVICE',start,end,s.id('service',file.path+':'+name),{name,deploymentTruth:'NOT_ESTABLISHED'});
    if(service&&typeof service==='object')for(const field of ['image','ports','volumes','build','depends_on','networks','env_file','configs','secrets'])if(field in service){
      const [start,end]=at(['services',name,field]);
      s.fact(file,'F10','SERVICE_CONFIG_REFERENCE',start,end,null,{service:name,field,declared:true,rawSupportDigest:sha(Buffer.from(file.text.slice(start,end),'utf8'))});
    }
  }
  if(value.apiVersion&&value.kind){s.fact(file,'F09','DECLARED_MANIFEST',0,file.text.length,null,{apiVersion:value.apiVersion,kind:value.kind,name:value.metadata?.name||null});if(file.text.includes('{{'))s.gap(file,'F10','NATIVE_TEMPLATE_RENDER_REQUIRED');}
  if(value.jobs&&typeof value.jobs==='object')for(const [name,job] of Object.entries(value.jobs)){s.fact(file,'F08','DECLARED_WORKFLOW_JOB',...at(['jobs',name]),s.id('workflow-job',file.path+':'+name),{name,needs:job?.needs||[],execution:'NOT_EXECUTED'});}
  if(value.asyncapi)s.fact(file,'F05','EVENT_CONTRACT',0,file.text.length,null,{version:value.asyncapi,channels:Object.keys(value.channels||{}),declared:true});
  if(value.type==='record'&&value.name){
    try{const type=s.tools.avsc.Type.forSchema(value);s.fact(file,'F05','AVRO_SCHEMA',0,file.text.length,s.id('avro',file.path+':'+type.getName()),{name:type.getName(),fields:type.getFields().map(f=>f.name),meaning:'validated-schema-declaration'});s.native.push({path:file.path,engine:'avsc',mode:'schema-validation',diagnostics:[]});}
    catch{s.gap(file,'F05','AVRO_SCHEMA_REFERENCE_OR_DIALECT_UNSUPPORTED');}
  }
}
function properties(s,file){
  if(file.path.endsWith('.properties')){
    const lines=[...file.text.matchAll(/[^\r\n]*(?:\r\n|\r|\n|$)/g)].filter(m=>m[0].length),entries=[];
    for(let index=0;index<lines.length;index++){
      const first=lines[index],body=first[0].replace(/[\r\n]+$/,''),leading=body.match(/^[ \t\f]*/)[0].length;if(leading===body.length||['#','!'].includes(body[leading]))continue;
      const fragments=[];let size=0;
      while(true){const line=lines[index],raw=line[0].replace(/[\r\n]+$/,''),skip=fragments.length?raw.match(/^[ \t\f]*/)[0].length:leading,slashes=raw.match(/\\+$/)?.[0].length||0,continued=slashes%2===1&&/[\r\n]$/.test(line[0]);
        const text=raw.slice(skip,continued?-1:undefined);fragments.push({text,start:line.index+skip,logical:size});size+=text.length;
        if(!continued||index+1===lines.length)break;index++;
      }
      const logicalLine=fragments.map(f=>f.text).join('');if(!logicalLine)continue;
      let end=0,escaped=false;for(;end<logicalLine.length;end++){const char=logicalLine[end];if(!escaped&&/[=: \t\f]/.test(char))break;if(char==='\\')escaped=!escaped;else escaped=false;}
      const position=offset=>{for(let i=fragments.length-1;i>=0;i--)if(offset>=fragments[i].logical)return fragments[i].start+offset-fragments[i].logical;return fragments[0].start;};
      const rawKey=logicalLine.slice(0,end);let key='',valid=true;
      for(let i=0;i<rawKey.length;i++){
        const char=rawKey[i];if(char!=='\\'){key+=char;continue;}
        if(++i===rawKey.length){valid=false;break;}
        const escaped=rawKey[i];if(escaped==='u'){const hex=rawKey.slice(i+1,i+5);if(!/^[\da-fA-F]{4}$/.test(hex)){valid=false;break;}key+=String.fromCharCode(parseInt(hex,16));i+=4;}
        else key+=({t:'\t',r:'\r',n:'\n',f:'\f'})[escaped]??escaped;
      }
      if(!valid||!key.isWellFormed()){s.gap(file,'F10','PROPERTIES_KEY_ESCAPE_INVALID',position(0),position(end));continue;}
      entries.push({key,start:position(0),end:position(end)});
    }
    const last=new Map(entries.map((entry,index)=>[entry.key,index]));
    entries.forEach((entry,index)=>s.fact(file,'F10','CONFIG_KEY',entry.start,entry.end,null,{keyPath:[entry.key],valueKind:'protected-string',effectiveWithinDocument:last.get(entry.key)===index,meaning:'reader-syntax-key-declaration'}));return;
  }
  let offset=0;for(const line of file.text.split(/(?<=\n)/)){
    const match=line.match(/^\s*(?:export\s+)?([^#!\s=:]+)\s*[=:]/);if(match){const start=offset+line.indexOf(match[1]);s.fact(file,'F10','CONFIG_KEY',start,start+match[1].length,null,{keyPath:[match[1]],valueKind:'protected-string'});if(/\\\r?\n$/.test(line))s.gap(file,'F10','PROPERTIES_CONTINUATION_REQUIRED',offset,offset+line.length);}
    offset+=line.length;
  }
}
function xml(s,file){
  if(/<!DOCTYPE|<!ENTITY/i.test(file.text)){s.gap(file,'F10','XML_EXTERNAL_OR_INTERNAL_ENTITY_FORBIDDEN');return;}
  const parser=new s.tools['@xmldom/xmldom'].DOMParser({onError:()=>{throw Error('XML_PARSE_ERROR');}});const doc=parser.parseFromString(file.text,'application/xml');
  function walk(node){if(node.nodeType===1){s.fact(file,'F10','XML_ELEMENT',0,file.text.length,null,{namespace:node.namespaceURI||'',localName:node.localName},'RESOLVED');
    for(const attr of Array.from(node.attributes||[]))if(['schemaLocation','include','href','src','resource','import'].includes(attr.localName))s.reference(file,'F07','XML_RESOURCE_REFERENCE',0,file.text.length,attr.value,{namespace:attr.namespaceURI||''});
    if(node.localName==='bean')s.fact(file,'F04','DECLARED_XML_BEAN',0,file.text.length,null,{namespace:node.namespaceURI||'',activation:'NOT_ESTABLISHED'},'PARTIAL');}
    for(let child=node.firstChild;child;child=child.nextSibling)walk(child);
  }walk(doc);require('./schema-models.cjs').orm(s,file,doc);s.native.push({path:file.path,engine:'@xmldom/xmldom',mode:'no-dtd',diagnostics:[]});
}
function graphql(s,file){
  const g=s.tools.graphql;
  const parse=f=>g.parse(new g.Source(f.text,f.path));
  const ast=parse(file),names=new Set(),fragments=new Map();
  require('./contract-models.cjs').graphqlFields(s,file,ast);
  const executable=n=>['OperationDefinition','FragmentDefinition'].includes(n.kind);
  const key=node=>(node.kind==='OperationDefinition'?'operation':node.kind==='FragmentDefinition'?'fragment':node.kind==='DirectiveDefinition'?'directive':'type')+':'+(node.name?.value??'anonymous@'+node.loc.start);
  for(const node of ast.definitions){const name=node.name?.value??null,namespace=key(node);
    if(!node.kind.endsWith('Extension')){if(names.has(namespace))s.gap(file,'F05','GRAPHQL_DUPLICATE_DEFINITION',node.loc.start,node.loc.end);names.add(namespace);}
    s.fact(file,'F05',node.kind==='OperationDefinition'?'GRAPHQL_OPERATION':'GRAPHQL_DEFINITION',node.loc.start,node.loc.end,s.id('graphql',file.path+':'+namespace),{name,kind:node.kind,operation:node.operation||null});
  }
  const schemaDocs=new Map(),fragmentDocs=new Map([[file.path,{file,ast}]]);
  const ownSchema=ast.definitions.filter(n=>!executable(n));
  if(ownSchema.length)schemaDocs.set(file.path,{file,nodes:ownSchema});
  const selection=s.input.context.graphqlContexts?.[file.path];let incomplete=false;
  if(selection!==undefined){
    if(!selection||!Array.isArray(selection.schema)||!Array.isArray(selection.fragments??[])){
      incomplete=true;s.gap(file,'F05','GRAPHQL_CONTEXT_INVALID');
    }else for(const [group,paths] of [['schema',selection.schema],['fragments',selection.fragments||[]]])for(const name of paths){
      const selected=typeof name==='string'?s.files.get(name):null;
      if(!selected||selected.text===null){incomplete=true;s.gap(file,'F05',group==='schema'?'GRAPHQL_CAPTURED_SCHEMA_REQUIRED':'GRAPHQL_FRAGMENT_SOURCE_REQUIRED');continue;}
      try{const parsed=parse(selected);
        if(group==='schema')schemaDocs.set(name,{file:selected,nodes:parsed.definitions.filter(n=>!executable(n))});
        else fragmentDocs.set(name,{file:selected,ast:parsed});
      }catch{incomplete=true;s.gap(file,'F05','GRAPHQL_CAPTURED_DEPENDENCY_INVALID');}
    }
  }
  for(const {file:origin,ast:document} of fragmentDocs.values())for(const node of document.definitions)if(node.kind==='FragmentDefinition'){
    const matches=fragments.get(node.name.value)||[];matches.push({node,origin});fragments.set(node.name.value,matches);
  }
  g.visit(ast,{FragmentSpread(node){const matches=fragments.get(node.name.value)||[],status=matches.length===1?(incomplete?'CONDITIONAL':'RESOLVED'):matches.length?'AMBIGUOUS':'UNRESOLVED';
    const match=matches.length===1?matches[0]:null;
    s.fact(file,'F05','GRAPHQL_FRAGMENT_REFERENCE',node.loc.start,node.loc.end,match?s.id('graphql',match.origin.path+':fragment:'+node.name.value):null,{name:node.name.value},status,
      match?[{path:match.origin.path,start:match.node.loc.start,end:match.node.loc.end}]:[]);
    if(!match)s.gap(file,'F05',matches.length?'GRAPHQL_FRAGMENT_AMBIGUOUS':'GRAPHQL_FRAGMENT_UNAVAILABLE',node.loc.start,node.loc.end);
  }});
  const operations=ast.definitions.filter(n=>n.kind==='OperationDefinition');
  if(schemaDocs.size&&!incomplete){try{
    const schema=g.buildASTSchema({kind:'Document',definitions:[...schemaDocs.values()].flatMap(d=>d.nodes)}),errors=g.validateSchema(schema);
    for(const error of errors)s.gap(file,'F05','GRAPHQL_SCHEMA_INVALID');
    if(!errors.length&&operations.length){
      const definitions=[...operations,...[...fragments.values()].flatMap(matches=>matches.map(m=>m.node))];
      const invalid=g.validate(schema,{kind:'Document',definitions});
      for(const error of invalid){const location=error.nodes?.[0]?.loc,origin=s.files.get(location?.source?.name)||file;
        s.gap(origin,'F05','GRAPHQL_OPERATION_INVALID',location?.start??0,location?.end??origin.text.length);}
      if(!invalid.length){const sources=[...schemaDocs.keys()].sort(),supports=sources.map(path=>({path,start:0,end:s.files.get(path).text.length}));
        for(const operation of operations)s.fact(file,'F05','GRAPHQL_SCHEMA_BINDING',operation.loc.start,operation.loc.end,s.id('graphql-schema',JSON.stringify(sources)),{schemaDocuments:sources,validation:'native-graphql-schema-and-operation'},'RESOLVED',supports);
        require('./contract-models.cjs').graphqlReferences(s,file,schema,definitions);
      }
    }
  }catch{s.gap(file,'F05','GRAPHQL_SCHEMA_UNAVAILABLE');}}
  else if(operations.length)s.gap(file,'F05','GRAPHQL_SCHEMA_BINDING_REQUIRED');
  s.native.push({path:file.path,engine:'graphql',mode:'parse-and-schema-validation',diagnostics:[...new Set(s.obligations.filter(o=>o.path===file.path&&o.reason.startsWith('GRAPHQL_')).map(o=>o.reason))].sort()});
}
function protobuf(s,file){
  const p=s.tools.protobufjs,root=new p.Root(),owners=new Map(),loaded=new Set();let failed=false;
  const roots=s.input.context.protoImportRoots||['.'];if(!Array.isArray(roots)||roots.some(r=>typeof r!=='string'||r.startsWith('/')||r.includes('..')||r.includes('\\')))throw Error('PROTO_IMPORT_ROOT_INVALID');
  function mark(node,origin){if(!owners.has(node))owners.set(node,origin);for(const item of Object.values(node.nested||{}))mark(item,origin);for(const field of Object.values(node.fields||{}))if(!owners.has(field))owners.set(field,origin);for(const method of Object.values(node.methods||{}))if(!owners.has(method))owners.set(method,origin);}
  function load(source){if(loaded.has(source.path))return;loaded.add(source.path);
    let parsed;try{parsed=p.parse(source.text,root,{keepCase:true});}catch{s.gap(source,'F05','PROTO_DECLARATION_CONFLICT_OR_PARSE_FAILURE');failed=true;return;}mark(root,source.path);
    for(const ref of parsed.imports||[]){const choices=[...new Set(roots.map(r=>path.normalize(path.join(r,ref))).filter(candidate=>s.files.get(candidate)?.text!==null&&s.files.has(candidate)))];
      if(source.path===file.path){if(choices.length===1)s.fact(file,'F05','PROTO_IMPORT',0,file.text.length,choices[0],{referenceDigest:sha(Buffer.from(ref))});else s.gap(file,'F05',choices.length?'PROTO_IMPORT_AMBIGUOUS':'PROTO_IMPORT_UNAVAILABLE');}
      if(choices.length===1)load(s.files.get(choices[0]));else failed=true;
    }
  }load(file);
  function reference(type,kind,owner){
    try{type.resolve();const resolved=kind==='field'?type.resolvedType:null;
      const targets=kind==='field'?[resolved]:[type.resolvedRequestType,type.resolvedResponseType];
      for(const target of targets.filter(Boolean)){const origin=owners.get(target);if(!origin){s.gap(file,'F05','PROTO_TYPE_ORIGIN_UNAVAILABLE');continue;}
        s.fact(file,'F05','PROTO_TYPE_REFERENCE',0,file.text.length,origin+'#'+target.fullName.replace(/^\./,''),{owner,name:type.name,role:kind});}
    }catch{s.gap(file,'F05','PROTO_TYPE_REFERENCE_UNAVAILABLE');}
  }
  function walk(node){for(const item of Object.values(node.nested||{})){
    if(owners.get(item)===file.path){s.fact(file,'F05','PROTO_DECLARATION',0,file.text.length,s.id('protobuf',file.path+':'+item.fullName),{name:item.fullName,kind:item.constructor.name,fields:Object.keys(item.fields||{}),methods:Object.keys(item.methods||{})});
      for(const field of Object.values(item.fields||{}))reference(field,'field',item.fullName);for(const method of Object.values(item.methods||{}))reference(method,'method',item.fullName);
    }walk(item);
  }}walk(root);
  if(failed)s.gap(file,'F05','PROTO_IMPORT_CLOSURE_INCOMPLETE');
  s.native.push({path:file.path,engine:'protobufjs',mode:'captured-import-closure-no-codegen',diagnostics:[]});
}
function sql(s,file){
  const dialect=s.input.context.sqlDialect;if(!['Postgresql','MySQL','TransactSQL','SQLite'].includes(dialect)){s.gap(file,'F05','SQL_DIALECT_REQUIRED');return;}
  const Parser=s.tools['node-sql-parser'].Parser,parser=new Parser();const parsed=parser.parse(file.text,{database:dialect,parseOptions:{includeLocations:true}});
  for(const table of parsed.tableList||[]){const [operation,schema,name]=table.split('::');s.fact(file,'F05','SQL_TABLE_ACCESS',0,file.text.length,s.id('sql-table',(schema==='null'?'UNKNOWN_SEARCH_PATH':schema)+':'+name),{operation,schema:schema==='null'?null:schema,name,dialect,actualState:'NOT_ESTABLISHED'},schema==='null'?'PARTIAL':'RESOLVED');if(schema==='null')s.gap(file,'F05','SQL_SEARCH_PATH_UNAVAILABLE');}
  for(const ast of Array.isArray(parsed.ast)?parsed.ast:[parsed.ast])if(ast)s.fact(file,'F05','SQL_STATEMENT',0,file.text.length,null,{kind:ast.type,dialect});
  require('./schema-models.cjs').sql(s,file,parsed.ast,dialect);
  s.native.push({path:file.path,engine:'node-sql-parser',mode:dialect,diagnostics:[]});
}
function binary(s,file){
  if(!Array.isArray(s.input.context.protobufDescriptorSets)||!s.input.context.protobufDescriptorSets.includes(file.path))return false;
  (s.binaryProviders??=new Set()).add(file.path);
  const descriptor=s.tools.protobufDescriptor,p=s.tools.protobufjs;
  let decoded;try{decoded=descriptor.FileDescriptorSet.decode(Buffer.from(file.data,'base64'));}catch{s.gap(file,'F05','PROTO_DESCRIPTOR_DECODE_FAILED');return true;}
  const entries=[],names=new Map();
  function requiredNames(node){
    if(!node.name||typeof node.name!=='string')throw Error('PROTO_DESCRIPTOR_IDENTITY_REQUIRED');
    for(const key of ['messageType','nestedType','enumType','enumValue','value','field','extension','service','method','oneofDecl'])for(const child of node[key]||[])requiredNames(child);
  }
  for(const [index,definition] of (decoded.file||[]).entries()){
    try{
      require('./common.cjs').logical(definition.name);requiredNames(definition);
      const syntax=definition.syntax||'proto2';if(!['proto2','proto3','editions'].includes(syntax))throw Error('PROTO_DESCRIPTOR_VERSION_UNSUPPORTED');
      const root=p.Root.fromDescriptor({file:[definition]},{keepCase:true});entries.push({definition,root});names.set(definition.name,(names.get(definition.name)||0)+1);
    }catch(error){s.gap(file,'F05',error.message==='PROTO_DESCRIPTOR_VERSION_UNSUPPORTED'?error.message:'PROTO_DESCRIPTOR_STRUCTURE_UNSUPPORTED',0,0,{descriptorIndex:index});}
  }
  let completeRoot;
  try{completeRoot=p.Root.fromDescriptor({file:entries.map(e=>e.definition)},{keepCase:true});}catch{s.gap(file,'F05','PROTO_DESCRIPTOR_NAMESPACE_CONFLICT');}
  const owners=new Map();
  function visit(node,consume){for(const item of Object.values(node.nested||{})){if(item.fields||item.methods||item.values)consume(item);visit(item,consume);}}
  for(const {definition,root} of entries)visit(root,node=>{const matches=owners.get(node.fullName)||[];matches.push(definition.name);owners.set(node.fullName,matches);});
  s.fact(file,'F05','PROTO_DESCRIPTOR_SET',0,0,null,{files:decoded.file.length,supportedFiles:entries.length,wireFormat:'google.protobuf.FileDescriptorSet',validation:'protobufjs-descriptor-decoder'},entries.length===decoded.file.length?'RESOLVED':'PARTIAL');
  for(const {definition,root} of entries){
    s.fact(file,'F05','PROTO_DESCRIPTOR_FILE',0,0,s.id('protobuf-file',file.path+':'+definition.name),{schemaFile:definition.name,package:definition.package||'',syntax:definition.syntax||'proto2'},names.get(definition.name)===1?'RESOLVED':'AMBIGUOUS');
    for(const dependency of definition.dependency||[])s.fact(file,'F05','PROTO_DESCRIPTOR_IMPORT',0,0,names.get(dependency)===1?s.id('protobuf-file',file.path+':'+dependency):null,{schemaFile:definition.name,dependency},names.get(dependency)===1?'RESOLVED':names.has(dependency)?'AMBIGUOUS':'UNRESOLVED');
    visit(root,node=>{
      const source=completeRoot?.lookup(node.fullName)||node,ambiguous=owners.get(node.fullName).length!==1||names.get(definition.name)!==1,status=ambiguous?'AMBIGUOUS':'RESOLVED';
      const target=s.id('protobuf-descriptor',file.path+':'+definition.name+':'+node.fullName);
      s.fact(file,'F05','PROTO_DESCRIPTOR_DECLARATION',0,0,target,{schemaFile:definition.name,package:definition.package||'',syntax:definition.syntax||'proto2',name:node.fullName,kind:node.constructor.name},status);
      for(const field of Object.values(source.fields||{})){
        let resolved=true;try{field.resolve();}catch{resolved=false;s.gap(file,'F05','PROTO_DESCRIPTOR_TYPE_UNAVAILABLE');}
        s.fact(file,'F05','PROTO_DESCRIPTOR_FIELD',0,0,target,{owner:node.fullName,name:field.name,number:field.id,type:field.resolvedType?.fullName||field.type,repeated:field.repeated,map:field.map,oneof:field.partOf?.name??null},ambiguous?'AMBIGUOUS':resolved?'RESOLVED':'UNRESOLVED');
      }
      for(const method of Object.values(source.methods||{})){
        let resolved=true;try{method.resolve();}catch{resolved=false;s.gap(file,'F05','PROTO_DESCRIPTOR_TYPE_UNAVAILABLE');}
        s.fact(file,'F05','PROTO_DESCRIPTOR_METHOD',0,0,target,{owner:node.fullName,name:method.name,requestType:method.resolvedRequestType?.fullName||method.requestType,responseType:method.resolvedResponseType?.fullName||method.responseType,requestStream:!!method.requestStream,responseStream:!!method.responseStream},ambiguous?'AMBIGUOUS':resolved?'RESOLVED':'UNRESOLVED');
      }
      for(const [name,value] of Object.entries(source.values||{}))s.fact(file,'F05','PROTO_DESCRIPTOR_ENUM_VALUE',0,0,target,{owner:node.fullName,name,value},status);
    });
  }
  s.native.push({path:file.path,engine:'protobufjs',version:'8.8.1',mode:'binary-descriptor-set-no-code-generation',diagnostics:[]});return true;
}
module.exports={analyze:structured,family,binary};
