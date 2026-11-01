'use strict';
const crypto=require('node:crypto');
const path=require('node:path').posix;
const sha=bytes=>'sha256:'+crypto.createHash('sha256').update(bytes).digest('hex');
function canonical(value) {
  if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
  if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';
  if(value===undefined||typeof value==='number'&&!Number.isSafeInteger(value))throw Error('NON_CANONICAL_VALUE');
  return JSON.stringify(value);
}
const digest=value=>sha(Buffer.from(canonical(value),'utf8'));
function logical(name) {
  if(typeof name!=='string'||!name||/[\\\x00:]/.test(name)||name.startsWith('/')||name.split('/').some(s=>!s||s==='.'||s==='..'))throw Error('INVALID_LOGICAL_PATH');
  return name;
}
function coordinates(text){
  const lines=[0];for(let i=0;i<text.length;i++)if(text[i]==='\n'||text[i]==='\r'&&text[i+1]!=='\n')lines.push(i+1);
  function point(n){
    if(!Number.isSafeInteger(n)||n<0||n>text.length||text.charCodeAt(n)>=0xdc00&&text.charCodeAt(n)<=0xdfff)throw Error('NATIVE_SOURCE_RANGE_INVALID');
    let low=0,high=lines.length;while(low+1<high){const middle=(low+high)>>>1;if(lines[middle]<=n)low=middle;else high=middle;}
    return [low+1,n-lines[low]+1];
  }
  return (start,end)=>{if(end<start)throw Error('NATIVE_SOURCE_RANGE_INVALID');const [startLine,startColumn]=point(start),[endLine,endColumn]=point(end);return {start,end,startLine,startColumn,endLine,endColumn};};
}
function span(text,start,end){return coordinates(text)(start,end);}
function create(input,tools) {
  if(input.schema!=='architecture-request:m8.4-v1'||!/^sha256:[a-f0-9]{64}$/.test(input.scope)||!input.repository||!input.app||!input.context)throw Error('ARCHITECTURE_CONTEXT_REQUIRED');
  if(!Array.isArray(input.files)||!Array.isArray(input.profiles)||new Set(input.profiles.map(p=>p.id)).size!==input.profiles.length)throw Error('ARCHITECTURE_SELECTION_INVALID');
  const files=new Map();let bytes=0;
  for(const f of input.files){logical(f.path);let raw;
    if(typeof f.text==='string'&&f.text.isWellFormed()&&f.data===undefined)raw=Buffer.from(f.text,'utf8');
    else if(typeof f.data==='string'&&f.text===undefined){raw=Buffer.from(f.data,'base64');if(raw.toString('base64')!==f.data)throw Error('CAPTURED_BINARY_ENCODING');}
    else throw Error('CAPTURED_SOURCE_ENCODING');
    if(sha(raw)!==f.sha256||files.has(f.path))throw Error('CAPTURED_SOURCE_HASH_OR_DUPLICATE');bytes+=raw.length;if(bytes>64*1024*1024)throw Error('ARCHITECTURE_CONTEXT_WORKER_REQUIRED');files.set(f.path,{...f,text:f.text??null,size:raw.length});}
  const facts=[],obligations=[],documents=[],artifacts=[],positions=new Map();
  const located=(file,start,end)=>{if(!positions.has(file.path))positions.set(file.path,coordinates(file.text));return positions.get(file.path)(start,end);};
  const state={input,tools,files,facts,obligations,documents,artifacts,trees:new Map(),native:[],resourceBases:new Map(),htmlBaseExplicit:new Set(),profiles:new Map(input.profiles.map(p=>[p.id,p]))};
  state.artifact=(file,kind,text)=>{
    const bytes=Buffer.from(typeof text==='string'?text:JSON.stringify(text),'utf8'),content={digest:sha(bytes),size:bytes.length};
    const id=digest(['architecture-derived:m8.4-v1',input.scope,file.path,file.sha256,kind,content]);
    artifacts.push({id,source:file.path,sourceDigest:file.sha256,kind,content,data:bytes.toString('base64'),population:'generated-helper'});return {id,content};
  };
  state.id=(kind,key)=>digest(['architecture-symbol:m8.4-v1',input.repository,input.app,kind,key]);
  state.gap=(file,category,reason,start=0,end=file.text?.length??0,details={})=>{
    const row={path:file.path,category,reason,span:file.text===null?null:located(file,start,end),details};row.id=digest([input.scope,row]);obligations.push(row);return row;
  };
  state.fact=(file,category,kind,start,end,target,attributes={},status='RESOLVED',supports=[])=>{
    const row={path:file.path,category,kind,span:file.text===null?null:located(file,start,end),target:target??null,attributes:{...attributes,rangePrecision:file.text===null?'BINARY_DOCUMENT_SUPPORT':start===0&&end===file.text.length?'DOCUMENT_SUPPORT':'ORIGINAL_SOURCE_RANGE'},status,supports,
      owner:state.id('document',file.path),context:input.scope,conditions:input.context.conditions||{},population:fPopulation(file)};
    row.id=digest(row);facts.push(row);
    if(status!=='RESOLVED')state.gap(file,category,'ARCHITECTURE_'+status,start,end,{fact:row.id});
    return row;
  };
  state.reference=(file,category,kind,start,end,specifier,attributes={})=>{
    if(typeof specifier!=='string'||specifier.includes('\0'))return state.gap(file,category,'DYNAMIC_REFERENCE',start,end);
    let clean=specifier.split(/[?#]/)[0],resolved=null,status='UNRESOLVED',external=/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(specifier),capturedUri=false;
    const base=state.resourceBases.get(file.path);
    if(base&&!external){try{const uri=new URL(specifier,base.url);external=base.external||uri.origin!==base.origin;
      if(!external){if(/%2f|%5c/i.test(uri.pathname))return state.gap(file,category,'RESOURCE_URI_PATH_MAPPING_REQUIRED',start,end);clean=decodeURIComponent(uri.pathname.slice(1));capturedUri=true;}
    }catch{return state.gap(file,category,'RESOURCE_BASE_CONTEXT_REQUIRED',start,end);}}
    if(!external){
      const candidate=capturedUri?path.normalize(clean):path.normalize(clean.startsWith('/')?clean.slice(1):path.join(path.dirname(file.path),clean));
      if(candidate!=='..'&&!candidate.startsWith('../'))for(const p of [candidate,...['.ts','.tsx','.js','.jsx','.vue','.svelte','.astro','/index.ts','/index.js'].map(ext=>candidate+ext)])if(files.has(p)){resolved=p;status='RESOLVED';break;}
    }
    const row=state.fact(file,category,kind,start,end,resolved,{...attributes,specifierDigest:sha(Buffer.from(specifier)),external,...(base?{declaredBaseDigest:sha(Buffer.from(base.url))}:{})},status);
    if(!resolved)state.gap(file,category,external?'EXTERNAL_RESOURCE_NOT_CAPTURED':'RESOURCE_UNAVAILABLE',start,end,{reference:row.id});
    return row;
  };
  return state;
}
function fPopulation(f){if(f.role)return f.role;if(/(?:^|\/)(?:test|tests|__tests__|examples|stories|generated|vendor)(?:\/|$)|\.(?:test|spec|stories)\./.test(f.path))return 'nonproduction';return 'unknown';}
module.exports={sha,digest,canonical,logical,coordinates,span,create};
