'use strict';
const path=require('node:path').posix;
const {logical}=require('./common.cjs');
const identifier=value=>typeof value==='string'?value:value?.expr?.value??value?.value??null;
function sql(s,file,ast,dialect){
  for(const statement of Array.isArray(ast)?ast:[ast]){
    if(!statement||statement.type!=='create'||!['table','view'].includes(statement.keyword))continue;
    for(const table of statement.table||[]){
      const name=identifier(table.table),schema=identifier(table.db);if(!name)continue;
      const owner=s.id('sql-table',(schema||'UNKNOWN_SEARCH_PATH')+':'+name);
      s.fact(file,'F05','SQL_'+statement.keyword.toUpperCase()+'_DECLARATION',0,file.text.length,owner,{name,schema,dialect,meaning:'source-declared-ddl',actualState:'NOT_ESTABLISHED'},schema?'RESOLVED':'PARTIAL');
      for(const column of statement.create_definitions||[]){
        if(column.resource==='column'){
          const field=identifier(column.column?.column);if(!field){s.gap(file,'F05','SQL_COLUMN_IDENTITY_UNAVAILABLE');continue;}
          s.fact(file,'F05','SQL_COLUMN_DECLARATION',0,file.text.length,s.id('sql-column',owner+':'+field),{owner,name:field,dataType:column.definition?.dataType??null,primaryKey:!!column.primary_key,nullable:column.nullable?.type??null,dialect,meaning:'native-parser-ddl'});
        }
        const reference=column.reference_definition;
        if(reference){for(const target of reference.table||[])s.fact(file,'F05','SQL_FOREIGN_KEY_DECLARATION',0,file.text.length,null,{owner,column:identifier(column.column?.column),targetSchema:identifier(target.db),targetTable:identifier(target.table),targetColumns:(reference.definition||[]).map(c=>identifier(c.column)),actualState:'NOT_ESTABLISHED'});}
        if(column.constraint_type)s.fact(file,'F05','SQL_CONSTRAINT_DECLARATION',0,file.text.length,null,{owner,kind:column.constraint_type,columns:(column.definition||[]).map(c=>identifier(c.column)),name:column.constraint??null});
      }
    }
  }
}
function orm(s,file,doc){
  const root=doc.documentElement,namespace=root?.namespaceURI;
  if(root?.localName!=='entity-mappings')return;
  const versions={'https://jakarta.ee/xml/ns/persistence/orm':['3.0','3.1','3.2'],'http://xmlns.jcp.org/xml/ns/persistence/orm':['2.1','2.2'],'http://java.sun.com/xml/ns/persistence/orm':['1.0','2.0']};
  const version=root.getAttribute('version');if(!versions[namespace]?.includes(version)){s.gap(file,'F05','ORM_MAPPING_DIALECT_UNSUPPORTED');return;}
  const children=(node,name)=>Array.from(node.childNodes||[]).filter(n=>n.nodeType===1&&n.namespaceURI===namespace&&(!name||n.localName===name));
  const packageName=children(root,'package')[0]?.textContent?.trim()||null;
  for(const entity of children(root).filter(n=>['entity','mapped-superclass','embeddable'].includes(n.localName))){
    const declaredClass=entity.getAttribute('class');if(!declaredClass){s.gap(file,'F05','ORM_ENTITY_CLASS_UNAVAILABLE');continue;}
    const owner=s.id('orm-entity',file.path+':'+declaredClass);
    s.fact(file,'F05','ORM_ENTITY_DECLARATION',0,file.text.length,owner,{declaredClass,package:packageName,kind:entity.localName,namespace,version,mergedProviderModel:'NOT_ESTABLISHED'});
    const table=children(entity,'table')[0];
    if(table?.getAttribute('name'))s.fact(file,'F05','ORM_TABLE_MAPPING',0,file.text.length,owner,{table:table.getAttribute('name'),schema:table.getAttribute('schema')||null,catalog:table.getAttribute('catalog')||null,declared:true,physicalDatabaseState:'NOT_ESTABLISHED'});
    else if(entity.localName==='entity')s.gap(file,'F05','ORM_PHYSICAL_NAMING_CONTEXT_REQUIRED');
    for(const attributes of children(entity,'attributes'))for(const attribute of children(attributes)){
      if(!['id','embedded-id','basic','version','many-to-one','one-to-many','one-to-one','many-to-many','element-collection','embedded','transient'].includes(attribute.localName)){s.gap(file,'F05','ORM_ATTRIBUTE_KIND_UNSUPPORTED');continue;}
      const name=attribute.getAttribute('name');if(!name){s.gap(file,'F05','ORM_ATTRIBUTE_IDENTITY_UNAVAILABLE');continue;}
      const column=children(attribute,'column')[0],join=children(attribute,'join-column')[0];
      s.fact(file,'F05','ORM_ATTRIBUTE_MAPPING',0,file.text.length,owner,{attribute:name,kind:attribute.localName,column:column?.getAttribute('name')||null,joinColumn:join?.getAttribute('name')||null,targetEntity:attribute.getAttribute('target-entity')||null,declared:true});
    }
  }
}
function migrations(s){
  const profiles=s.input.context.sqlMigrationProfiles;if(profiles===undefined)return;
  const sources=[...s.files.values()].filter(f=>f.text!==null&&!['dependency','toolchain'].includes(f.role));
  if(!Array.isArray(profiles)){for(const file of sources.filter(f=>f.path.endsWith('.sql')))s.gap(file,'F08','MIGRATION_PROFILE_INVALID');return;}
  for(const profile of profiles){
    const selected=new Map();
    try{
      if(profile?.id!=='flyway-default-sql'||profile.version!=='1'||!Array.isArray(profile.roots)||!profile.roots.length||Object.keys(profile).some(k=>!['id','version','roots'].includes(k)))throw Error('MIGRATION_PROFILE_UNSUPPORTED');
      for(const root of profile.roots){if(root!=='.')logical(root);for(const file of sources)if((root==='.'||file.path.startsWith(root+'/'))&&!path.relative(root,file.path).split('/').some(p=>p.startsWith('.')))selected.set(file.path,file);}
    }catch{for(const file of sources.filter(f=>f.path.endsWith('.sql')))s.gap(file,'F08','MIGRATION_PROFILE_UNSUPPORTED');continue;}
    const entries=[],versions=new Map();
    for(const file of selected.values()){
      const basename=path.basename(file.path),match=basename.match(/^V(.+?)__(.+)\.sql$/),repeat=basename.match(/^R__(.+)\.sql$/);if(!match&&!repeat)continue;
      let parts=[];if(match){const raw=match[1].split(/[._]/);if(raw.some(p=>!/^\d+$/.test(p))){s.gap(file,'F08','MIGRATION_VERSION_UNSUPPORTED');continue;}parts=raw.map(p=>BigInt(p).toString());while(parts.length>1&&parts.at(-1)==='0')parts.pop();}
      const key=match?'V:'+parts.join('.'):'R:'+repeat[1],entry={file,parts,key,repeat:!!repeat};entries.push(entry);versions.set(key,(versions.get(key)||0)+1);
    }
    let conflict=false;
    for(const entry of entries){
      const duplicate=versions.get(entry.key)>1;conflict||=duplicate;
      s.fact(entry.file,'F08','SQL_MIGRATION_DECLARATION',0,entry.file.text.length,s.id('sql-migration',entry.file.path),{profile:'flyway-default-sql:v1',version:entry.repeat?null:entry.parts.join('.'),repeatable:entry.repeat,sourceDigest:entry.file.sha256,execution:'NOT_EXECUTED',databaseHistory:'NOT_CAPTURED'},duplicate?'AMBIGUOUS':'RESOLVED');
      if(duplicate)s.gap(entry.file,'F08','MIGRATION_VERSION_CONFLICT');
      if(s.files.has(entry.file.path+'.conf'))s.gap(entry.file,'F08','MIGRATION_SCRIPT_CONFIGURATION_UNRESOLVED');
    }
    if(conflict)continue;
    const compare=(a,b)=>{if(a.repeat!==b.repeat)return a.repeat?1:-1;if(a.repeat)return a.key<b.key?-1:a.key>b.key?1:0;for(let i=0;i<Math.max(a.parts.length,b.parts.length);i++){const x=BigInt(a.parts[i]||'0'),y=BigInt(b.parts[i]||'0');if(x!==y)return x<y?-1:1;}return 0;};
    entries.sort(compare);
    for(let i=0;i+1<entries.length;i++)s.fact(entries[i].file,'F08','DECLARED_MIGRATION_PRECEDES',0,entries[i].file.text.length,s.id('sql-migration',entries[i+1].file.path),{next:entries[i+1].file.path,meaning:'default-naming-order',execution:'NOT_EXECUTED'},'CONDITIONAL',[{path:entries[i+1].file.path,start:0,end:entries[i+1].file.text.length}]);
  }
}
module.exports={sql,orm,migrations};
