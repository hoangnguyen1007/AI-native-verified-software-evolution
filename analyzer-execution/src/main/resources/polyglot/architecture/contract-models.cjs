'use strict';
const path=require('node:path').posix;
const {sha}=require('./common.cjs');
const record=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
function packages(s,configs){
  const manifests=[...configs.values()].filter(c=>path.basename(c.file.path)==='package.json'&&c.value&&typeof c.value==='object').sort((a,b)=>b.file.path.length-a.file.path.length);
  for(const c of manifests){
    const {file,value}=c,root=path.dirname(file.path),owner=s.id('package',root),at=pointer=>{const n=c.type==='json'?s.tools['jsonc-parser'].findNodeAtLocation(c.tree,pointer):null;return n?[n.offset,n.offset+n.length]:[0,file.text.length];};
    s.fact(file,'F01','PACKAGE_DECLARATION',0,file.text.length,owner,{declaredName:typeof value.name==='string'?value.name:null,private:value.private===true,ownership:'captured-manifest'});
    function exported(member,subpath='.',conditions=[],pointer=['exports'],order=[]){
      if(member===null){s.fact(file,'F01','PACKAGE_EXPORT',...at(pointer),null,{owner,subpath,conditions,order,disabled:true});return;}
      if(typeof member==='string'){
        const candidate=member.startsWith('./')&&!member.includes('\\')?path.normalize(path.join(root,member)):null;
        const inside=candidate&&path.relative(root,candidate)!=='..'&&!path.relative(root,candidate).startsWith('../');
        const target=inside&&s.files.has(candidate)?candidate:null;
        s.fact(file,'F01','PACKAGE_EXPORT',...at(pointer),target,{owner,subpath,packageConditions:conditions,order,targetDigest:sha(member),selection:'DECLARED_ALTERNATIVE'},target?'CONDITIONAL':'UNRESOLVED');
        if(!target)s.gap(file,'F01',member.includes('*')?'PACKAGE_EXPORT_PATTERN_CONTEXT_REQUIRED':'PACKAGE_EXPORT_TARGET_UNAVAILABLE',...at(pointer));
      }else if(Array.isArray(member))member.forEach((child,index)=>exported(child,subpath,conditions,[...pointer,index],[...order,index]));
      else if(record(member)){const keys=Object.keys(member);if(keys.some(k=>k.startsWith('.'))&&keys.some(k=>!k.startsWith('.'))){s.gap(file,'F01','PACKAGE_EXPORT_SHAPE_INVALID',...at(pointer));return;}for(const [key,child] of Object.entries(member))exported(child,key.startsWith('.')?key:subpath,key.startsWith('.')?conditions:[...conditions,key],[...pointer,key],order);}
      else s.gap(file,'F01','PACKAGE_EXPORT_SHAPE_INVALID',...at(pointer));
    }
    if('exports' in value)exported(value.exports);
    for(const kind of ['dependencies','devDependencies','peerDependencies','optionalDependencies'])if(value[kind]&&typeof value[kind]==='object')for(const [name,range] of Object.entries(value[kind]))s.fact(file,'F01','DECLARED_PACKAGE_DEPENDENCY',...at([kind,name]),null,{owner,name,kind,rangeDigest:sha(JSON.stringify(range)),resolution:'NOT_ESTABLISHED'});
  }
  for(const file of s.files.values())if(!['dependency','toolchain'].includes(file.role)){
    const owned=manifests.find(c=>{const relative=path.relative(path.dirname(c.file.path),file.path);return relative!=='..'&&!relative.startsWith('../');});
    if(owned)s.fact(file,'F01','PACKAGE_FILE_OWNERSHIP',0,file.text?.length??0,s.id('package',path.dirname(owned.file.path)),{manifest:owned.file.path,rule:'closest-captured-package-manifest'},'RESOLVED',[{path:owned.file.path,start:0,end:owned.file.text.length}]);
  }
}
function openapi(s,c,at){
  const {file,value}=c;if(typeof value?.openapi!=='string'||!/^3\.[01]\.\d+$/.test(value.openapi))return;
  const server=(entry,owner,pointer)=>{if(typeof entry?.url==='string')s.fact(file,'F05','API_SERVER',...at(pointer),owner,{urlDigest:sha(entry.url),variables:Object.keys(entry.variables||{}),activation:'DECLARED_ONLY'},'CONDITIONAL');else s.gap(file,'F05','OPENAPI_SERVER_INVALID',...at(pointer));};
  for(const [route,item] of Object.entries(record(value.paths)?value.paths:{}))if(record(item))for(const [method,operation] of Object.entries(item))if(/^(get|put|post|delete|options|head|patch|trace)$/.test(method)&&record(operation)){
    const pointer=['paths',route,method],owner=s.id('operation',file.path+':'+route+':'+method),parameters=new Map();
    for(const [entries,prefix] of [[item.parameters,['paths',route,'parameters']],[operation.parameters,[...pointer,'parameters']]])if(Array.isArray(entries))entries.forEach((parameter,index)=>{
      const source=[...prefix,index];if(parameter?.$ref){s.fact(file,'F05','API_PARAMETER_REFERENCE',...at(source),owner,{referenceDigest:sha(String(parameter.$ref))},'CONDITIONAL');return;}
      if(typeof parameter?.name!=='string'||!['path','query','header','cookie'].includes(parameter.in)){s.gap(file,'F05','OPENAPI_PARAMETER_INVALID',...at(source));return;}
      parameters.set(parameter.in+':'+parameter.name,{parameter,source});
    });
    for(const {parameter,source} of parameters.values())s.fact(file,'F05','API_PARAMETER',...at(source),owner,{name:parameter.name,location:parameter.in,required:parameter.required===true,schemaDeclared:!!parameter.schema});
    if(record(operation.requestBody))s.fact(file,'F05','API_REQUEST_BODY',...at([...pointer,'requestBody']),owner,{mediaTypes:Object.keys(record(operation.requestBody.content)?operation.requestBody.content:{}),required:operation.requestBody.required===true,referenceDigest:operation.requestBody.$ref?sha(String(operation.requestBody.$ref)):null});
    for(const [status,response] of Object.entries(record(operation.responses)?operation.responses:{})){if(!/^(?:[1-5](?:\d\d|XX)|default)$/.test(status)||!record(response)){s.gap(file,'F05','OPENAPI_RESPONSE_INVALID',...at([...pointer,'responses',status]));continue;}s.fact(file,'F05','API_RESPONSE',...at([...pointer,'responses',status]),owner,{statusCode:status,mediaTypes:Object.keys(record(response.content)?response.content:{}),headers:Object.keys(record(response.headers)?response.headers:{}),referenceDigest:response.$ref?sha(String(response.$ref)):null});}
    const servers=operation.servers??item.servers??value.servers;
    if(Array.isArray(servers))servers.forEach((entry,index)=>server(entry,owner,operation.servers?[...pointer,'servers',index]:item.servers?['paths',route,'servers',index]:['servers',index]));
  }
}
function graphqlFields(s,file,ast){
  const g=s.tools.graphql;
  for(const definition of ast.definitions)for(const field of definition.fields||[]){
    if(!definition.name||!field.name)continue;
    s.fact(file,'F05','GRAPHQL_FIELD_DEFINITION',field.loc.start,field.loc.end,s.id('graphql-field',file.path+':'+definition.name.value+':'+field.name.value),{owner:definition.name.value,field:field.name.value,type:g.print(field.type),arguments:(field.arguments||[]).map(a=>({name:a.name.value,type:g.print(a.type)}))});
  }
}
function graphqlReferences(s,file,schema,definitions){
  const g=s.tools.graphql,info=new g.TypeInfo(schema);
  g.visit({kind:'Document',definitions},g.visitWithTypeInfo(info,{Field(node){
    const parent=info.getParentType(),definition=info.getFieldDef();if(!parent||!definition)return;
    const origin=s.files.get(node.loc?.source?.name)||file;
    const declaration=definition.astNode,source=s.files.get(declaration?.loc?.source?.name);
    if(source)s.fact(origin,'F05','GRAPHQL_FIELD_REFERENCE',node.loc.start,node.loc.end,s.id('graphql-field',source.path+':'+parent.name+':'+definition.name),{parentType:parent.name,field:definition.name,type:String(definition.type)},'RESOLVED',[{path:source.path,start:declaration.loc.start,end:declaration.loc.end}]);
    else s.fact(origin,'F05','GRAPHQL_BUILTIN_FIELD_USE',node.loc.start,node.loc.end,null,{parentType:parent.name,field:definition.name,type:String(definition.type)});
  }}));
}
module.exports={packages,openapi,graphqlFields,graphqlReferences};
