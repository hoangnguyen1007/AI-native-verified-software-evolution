'use strict';
// Literal Thymeleaf 3.1 declarations over native HTML locations, never a renderer.
const path=require('node:path').posix;
const {logical}=require('./common.cjs');
const {attributeRange}=require('./resources.cjs');
function analyze(s){
  const files=[...s.files.values()].filter(f=>f.text!==null&&!['dependency','toolchain'].includes(f.role)&&/\.html?$/.test(f.path)).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
  const profiles=s.input.context.templateProfiles,handled=new Set();
  if(profiles!==undefined&&!Array.isArray(profiles)){for(const f of files)s.gap(f,'F02','TEMPLATE_PROFILE_INVALID');return;}
  for(const profile of profiles||[]){
    let selected=[];
    try{
      if(profile?.id!=='thymeleaf-html-literals'||profile.version!=='3.1'||!Array.isArray(profile.roots)||!profile.roots.length||Object.keys(profile).some(k=>!['id','version','roots'].includes(k)))throw Error();
      for(const root of profile.roots)if(root!=='.')logical(root);
      selected=files.filter(f=>profile.roots.some(root=>root==='.'||f.path.startsWith(root+'/')));
    }catch{for(const f of files)s.gap(f,'F02','TEMPLATE_PROFILE_UNSUPPORTED');continue;}
    const declarations=new Map(),references=[],edges=new Map();
    const edge=(from,to,reference=null)=>{if(!edges.has(from))edges.set(from,[]);edges.get(from).push({to,reference});};
    for(const file of selected){
      if(handled.has(file.path)){s.gap(file,'F02','TEMPLATE_PROFILE_AMBIGUOUS');continue;}handled.add(file.path);
      const document=s.id('template',file.path);let visited=0;
      try{
        const ast=s.tools.parse5.parse(file.text,{sourceCodeLocationInfo:true});
        const stack=[{node:ast,owner:document}];
        while(stack.length){
          const {node,owner}=stack.pop();if(++visited>100000){s.gap(file,'F02','TEMPLATE_STATIC_WORK_BOUND');break;}
          let enclosing=owner;const attrs=node.attrs||[],location=node.sourceCodeLocation;
          const get=name=>attrs.find(a=>a.name==='th:'+name||a.name==='data-th-'+name);
          const range=attr=>attributeRange(file.text,location.attrs[attr.name]);
          const declared=get('fragment');
          if(declared&&location?.attrs?.[declared.name]){
            const [start,end]=range(declared),name=declared.value.trim();
            if(/^[A-Za-z_][\w.-]*$/.test(name)){
              enclosing=s.id('template-fragment',file.path+':'+name+':'+start);const key=file.path+'\0'+name;
              const entry={file,start,end,id:enclosing};if(!declarations.has(key))declarations.set(key,[]);declarations.get(key).push(entry);
              s.fact(file,'F02','TEMPLATE_FRAGMENT_DECLARATION',start,end,enclosing,{name,profile:'thymeleaf-html-literals:3.1',meaning:'source-declared-fragment',rendering:'NOT_EXECUTED'});edge(document,enclosing);
            }else s.gap(file,'F02','TEMPLATE_FRAGMENT_SIGNATURE_REQUIRED',start,end);
          }
          for(const operation of ['insert','replace']){
            const attr=get(operation);if(!attr||!location?.attrs?.[attr.name])continue;
            const [start,end]=range(attr);references.push({file,start,end,owner:enclosing,value:attr.value,operation});
          }
          for(const child of [...(node.childNodes||[]),...(node.content?[node.content]:[])].reverse())stack.push({node:child,owner:enclosing});
        }
        s.native.push({path:file.path,engine:'parse5',version:'7.3.0',mode:'thymeleaf-3.1-literal-declarations-no-rendering',diagnostics:[]});
      }catch{s.gap(file,'F02','TEMPLATE_PARSE_OR_MAPPING_ERROR');}
    }
    for(const ref of references){
      const {file,start,end}=ref,match=ref.value.trim().match(/^~\{\s*([\w./-]*)(?:\s*::\s*([A-Za-z_][\w.-]*))?\s*\}$/);
      if(!match||!match[1]&&!match[2]){s.gap(file,'F02','TEMPLATE_DYNAMIC_FRAGMENT_REQUIRED',start,end);continue;}
      const name=match[1],fragment=match[2];let candidates=[];
      if(!name||name==='this')candidates=[file];
      else if(name.split('/').some(p=>!p||p==='.'||p==='..')){s.gap(file,'F02','TEMPLATE_RESOLVER_CONTEXT_REQUIRED',start,end);continue;}
      else for(const root of profile.roots){const candidate=s.files.get(path.join(root,name+'.html'));if(candidate&&selected.includes(candidate)&&handled.has(candidate.path)&&!candidates.includes(candidate))candidates.push(candidate);}
      const matches=fragment?candidates.flatMap(f=>declarations.get(f.path+'\0'+fragment)||[]):candidates.map(f=>({file:f,start:0,end:f.text.length,id:s.id('template',f.path)}));
      const status=matches.length===1?'RESOLVED':matches.length?'CANDIDATES':'UNRESOLVED',target=matches.length===1?matches[0].id:null;
      s.fact(file,'F02','TEMPLATE_FRAGMENT_REFERENCE',start,end,target,{operation:ref.operation,fragment:fragment||null,candidates:matches.map(m=>m.id),meaning:'captured-literal-declaration-binding',runtimeResolver:'NOT_ESTABLISHED',rendering:'NOT_EXECUTED'},status,matches.map(m=>({path:m.file.path,start:m.start,end:m.end})));
      if(!matches.length)s.gap(file,'F02',candidates.length?'TEMPLATE_FRAGMENT_UNAVAILABLE':'TEMPLATE_SOURCE_UNAVAILABLE',start,end);
      if(matches.length>1)s.gap(file,'F02','TEMPLATE_FRAGMENT_AMBIGUOUS',start,end);
      if(target)edge(ref.owner,target,ref);
    }
    // Back edges describe declaration cycles, not proof of a nonterminating render.
    const colors=new Map();for(const root of edges.keys())if(!colors.has(root)){
      const stack=[{id:root,index:0}];colors.set(root,1);
      while(stack.length){const frame=stack.at(-1),list=edges.get(frame.id)||[];
        if(frame.index===list.length){colors.set(frame.id,2);stack.pop();continue;}
        const next=list[frame.index++];if(colors.get(next.to)===1&&next.reference){const r=next.reference;s.gap(r.file,'F02','TEMPLATE_INCLUDE_CYCLE',r.start,r.end,{meaning:'declaration-dependency-cycle',rendering:'NOT_EXECUTED'});}
        else if(!colors.has(next.to)){colors.set(next.to,1);stack.push({id:next.to,index:0});}
      }
    }
  }
  for(const file of files)if(!handled.has(file.path)&&/(?:\bth:|\bdata-th-)(?:insert|replace|fragment)\s*=/.test(file.text))s.gap(file,'F02','TEMPLATE_PROFILE_REQUIRED');
}
module.exports={analyze};
