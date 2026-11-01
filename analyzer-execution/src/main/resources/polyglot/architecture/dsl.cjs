'use strict';
const {sha}=require('./common.cjs');
function analyze(s){for(const file of s.files.values()){
  if(file.text===null||['dependency','toolchain'].includes(file.role))continue;
  try{
    if(/\.(?:tf|hcl)$/.test(file.path)){
      const parsed=s.tools['hcl2-parser'].parseToObject(file.text);if(!parsed?.[0]||parsed[1])throw Error('HCL_PARSE_ERROR');
      const model=parsed[0];for(const family of ['module','resource','data','variable','output','provider','terraform'])for(const [name,entry] of Object.entries(model[family]||{})){
        s.fact(file,'F09','HCL_DECLARATION',0,file.text.length,s.id('hcl',file.path+':'+family+':'+name),{family,name,declared:true});
        if(family==='module')for(const item of Array.isArray(entry)?entry:[entry])if(typeof item.source==='string')s.reference(file,'F09','HCL_MODULE_REFERENCE',0,file.text.length,item.source,{module:name});
      }s.native.push({path:file.path,engine:'hcl2-parser',mode:'hcl2-declarations',diagnostics:[]});
    }else if(/(?:^|\/)Dockerfile(?:\..*)?$/.test(file.path)){
      const ast=s.tools['dockerfile-ast'].DockerfileParser.parse(file.text),lines=file.text.split(/(?<=\n)/);const offset=p=>lines.slice(0,p.line).reduce((a,l)=>a+l.length,0)+p.character;
      for(const instruction of ast.getInstructions()){
        const range=instruction.getRange(),start=offset(range.start),end=offset(range.end),keyword=instruction.getKeyword();
        if(keyword==='FROM')s.fact(file,'F09','CONTAINER_IMAGE',start,end,null,{imageDigest:sha(Buffer.from(instruction.getImage()||'')),declared:true,execution:'NOT_EXECUTED'});
        else if(['COPY','ADD'].includes(keyword))s.fact(file,'F08','CONTAINER_BUILD_INPUT',start,end,null,{keyword,argumentDigests:instruction.getArguments().map(a=>sha(Buffer.from(a.getValue())))},'PARTIAL');
        else if(['RUN','CMD','ENTRYPOINT','SHELL','ONBUILD'].includes(keyword)){s.fact(file,'F08','DECLARED_COMMAND',start,end,null,{keyword,commandDigest:sha(Buffer.from(instruction.getArgumentsContent()||'')),execution:'NOT_EXECUTED'});s.gap(file,'F08','EXECUTABLE_BUILD_EFFECTS_UNKNOWN',start,end);}
        else s.fact(file,'F10','CONTAINER_DIRECTIVE',start,end,null,{keyword});
      }s.native.push({path:file.path,engine:'dockerfile-ast',mode:'declarations-only',diagnostics:[]});
    }else if(/\.(?:sh|bash)$/.test(file.path)){
      const ast=s.tools['bash-parser'].default(file.text,{mode:'bash',insertLOC:true});
      const seen=new Set();function walk(n){if(!n||typeof n!=='object'||seen.has(n))return;seen.add(n);if(n.type==='Command'){
        const start=n.loc?.start?.char??0,end=n.loc?.end?.char===undefined?file.text.length:n.loc.end.char+1;
        s.fact(file,'F08','DECLARED_COMMAND',start,end,null,{commandDigest:sha(Buffer.from(n.name?.text||'')),execution:'NOT_EXECUTED'});s.gap(file,'F08','EXECUTABLE_SCRIPT_EFFECTS_UNKNOWN',start,end);
      }for(const value of Object.values(n))if(Array.isArray(value))value.forEach(walk);else if(value&&typeof value==='object')walk(value);}walk(ast);
      s.native.push({path:file.path,engine:'bash-parser',mode:'syntax-no-execution',diagnostics:[]});
    }
  }catch{s.gap(file,'F12','DSL_PARSE_ERROR');}
}}
module.exports={analyze};
