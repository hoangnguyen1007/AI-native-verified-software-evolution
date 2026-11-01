'use strict';
const {sha,logical,coordinates}=require('./common.cjs');
function analyze(s){
  const api=s.tools.sourceMap,requests=s.input.context.sourceMaps||[],bindings=new Map(),points=new Map();
  const fallback=[...s.files.values()].filter(f=>!['dependency','toolchain'].includes(f.role)).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0)[0];
  if(!Array.isArray(requests)){if(fallback)s.gap(fallback,'F08','SOURCE_MAP_SELECTION_INVALID');return;}
  function location(file,line,column){
    if(!points.has(file.path)){const starts=[0];for(let i=0;i<file.text.length;i++)if(file.text[i]==='\n'||file.text[i]==='\r'&&file.text[i+1]!=='\n')starts.push(i+1);points.set(file.path,{starts,at:coordinates(file.text)});}
    const {starts,at}=points.get(file.path);if(!Number.isInteger(line)||!Number.isInteger(column)||line<1||line>starts.length||column<0)throw Error('SOURCE_MAP_COORDINATES_INVALID');
    const offset=starts[line-1]+column,end=starts[line]??file.text.length;if(offset>end||line<starts.length&&offset>=end)throw Error('SOURCE_MAP_COORDINATES_INVALID');at(offset,offset);
  }
  function inspect(file,generated,map,url,artifactRefs,compilerOwned=false,projection=null){
    let trace;try{if(map.version!==3)throw Error();trace=new api.AnyMap(map,url);}catch{s.gap(file,'F08','SOURCE_MAP_FORMAT_INVALID');return null;}
    const sources=new Map();let valid=true,unmapped=0,segments=0;
    const fail=reason=>{valid=false;s.gap(file,'F08',reason);};
    for(let i=0;i<trace.resolvedSources.length;i++){
      const uri=trace.resolvedSources[i],content=trace.sourcesContent?.[i];let target;
      let sourceProjection=null,mappedText=null;
      if(compilerOwned&&content===file.text)target=file;
      else if(compilerOwned&&projection&&content===projection.text){target=file;sourceProjection=projection.metadata;mappedText=projection.text;}
      else try{const parsed=new URL(uri);if(parsed.origin==='https://captured.invalid'&&!/%2f|%5c/i.test(parsed.pathname)){const key=decodeURIComponent(parsed.pathname.slice(1));logical(key);target=s.files.get(key);}}catch{}
      if(!target||target.text===null){fail('SOURCE_MAP_SOURCE_UNAVAILABLE');continue;}
      if(typeof content!=='string'){fail('SOURCE_MAP_SOURCE_CONTENT_REQUIRED');continue;}
      if(sha(Buffer.from(content,'utf8'))!==(sourceProjection?.digest||target.sha256)){fail('SOURCE_MAP_SOURCE_CONTENT_MISMATCH');continue;}
      if(sources.has(uri)){fail('SOURCE_MAP_SOURCE_AMBIGUOUS');continue;}
      sources.set(uri,{file:target,count:0,sourceProjection,mappingDocument:mappedText===null?target:{path:target.path+'#excerpt:'+sourceProjection.start,text:mappedText}});
    }
    try{api.eachMapping(trace,m=>{
      segments++;location(generated,m.generatedLine,m.generatedColumn);
      if(m.source===null){unmapped++;return;}
      const source=sources.get(m.source);if(!source){valid=false;return;}location(source.mappingDocument,m.originalLine,m.originalColumn);source.count++;
    });}catch(error){fail(error.message==='SOURCE_MAP_COORDINATES_INVALID'?error.message:'SOURCE_MAP_MAPPING_INVALID');}
    s.fact(file,'F08','SOURCE_MAP_DOCUMENT',0,file.text.length,null,{generated:generated.path,generatedDigest:generated.sha256,declaredSources:trace.resolvedSources.length,capturedSources:sources.size,segmentsObserved:segments,unmappedSegments:unmapped,semanticAccuracy:'NOT_CERTIFIED',...artifactRefs},valid?'RESOLVED':'PARTIAL');
    for(const source of sources.values())if(source.count)s.fact(file,'F08','SOURCE_MAP_BINDING',0,file.text.length,s.id('document',source.file.path),{generated:generated.path,generatedDigest:generated.sha256,source:source.file.path,sourceDigest:source.file.sha256,segments:source.count,unmappedSegments:unmapped,precision:source.sourceProjection?'NATIVE_SEGMENT_POINTS_IN_SOURCE_EXCERPT':'NATIVE_SEGMENT_POINTS',...(source.sourceProjection?{sourceProjection:source.sourceProjection}:{}),semanticAccuracy:'NOT_CERTIFIED',...artifactRefs},valid?'RESOLVED':'PARTIAL',[{path:source.file.path,start:source.sourceProjection?.start??0,end:source.sourceProjection?.end??source.file.text.length}]);
    s.native.push({path:file.path,engine:'@jridgewell/trace-mapping',version:'0.3.31',mode:'captured-byte-and-coordinate-validation',diagnostics:valid?[]:['SOURCE_MAP_INCOMPLETE'],...(projection?{sourceProjection:projection.metadata}:{})});
    if(!segments){fail('SOURCE_MAP_MAPPING_EMPTY');return null;}
    return valid?{sources:[...sources.values()].filter(v=>v.count).map(v=>v.file.path),file,trace}:null;
  }
  if(Array.isArray(requests))for(const request of requests){
    const generated=s.files.get(request?.generated),file=s.files.get(request?.map);if(!generated||generated.text===null){if(fallback)s.gap(fallback,'F08','SOURCE_MAP_GENERATED_DOCUMENT_UNAVAILABLE');continue;}
    if(!file||file.text===null){s.gap(generated,'F08','SOURCE_MAP_DOCUMENT_UNAVAILABLE');continue;}
    try{
      const parsed=JSON.parse(file.text),result=inspect(file,generated,parsed,'https://captured.invalid/'+file.path,{map:file.path});
      if(result){const previous=bindings.get(generated.path);if(previous){bindings.set(generated.path,null);s.gap(file,'F08','SOURCE_MAP_GENERATED_BINDING_AMBIGUOUS');}else if(!bindings.has(generated.path))bindings.set(generated.path,result);}
    }catch{s.gap(file,'F08','SOURCE_MAP_FORMAT_INVALID');}
  }
  s.validatedSourceMaps=new Set([...bindings].filter(([,b])=>b).map(([path])=>path));
  function chain(generated,documents,maps,seen){
    if(seen.has(generated)){const binding=bindings.get(generated);if(binding)s.gap(binding.file,'F08','SOURCE_MAP_CHAIN_CYCLE');return;}
    const binding=bindings.get(generated);if(!binding)return;
    seen=new Set([...seen,generated]);
    for(const source of binding.sources){const nextDocuments=[...documents,source],nextMaps=[...maps,binding.file.path];
      if(bindings.get(source))chain(source,nextDocuments,nextMaps,seen);
      else if(nextDocuments.length>2)s.fact(binding.file,'F08','SOURCE_MAP_CHAIN',0,binding.file.text.length,s.id('document',source),{documents:nextDocuments,maps:nextMaps,precision:'HASH_LINKED_NATIVE_SEGMENT_MAPS',semanticAccuracy:'NOT_CERTIFIED'},'RESOLVED',nextDocuments.map(path=>({path,start:0,end:s.files.get(path).text.length})));
    }
  }
  for(const generated of bindings.keys())chain(generated,[generated],[],new Set());
  // Existing compiler artifacts already carry exact input/output hashes; validate their maps in place.
  for(const mapArtifact of s.artifacts.filter(a=>a.kind.endsWith('-map'))){
    const generatedArtifact=s.artifacts.find(a=>a.source===mapArtifact.source&&a.kind===mapArtifact.kind.slice(0,-4));
    const file=s.files.get(mapArtifact.source);if(!generatedArtifact||file?.text===null||!file)continue;
    try{const generated={path:generatedArtifact.id,sha256:generatedArtifact.content.digest,text:Buffer.from(generatedArtifact.data,'base64').toString('utf8')};
      let projection=null;
      if(mapArtifact.kind==='vue-render-map'){
        const parsed=s.tools['@vue/compiler-sfc'].parse(file.text,{filename:file.path}),template=parsed.descriptor?.template;
        if(!parsed.errors.length&&template&&!template.src&&(!template.lang||template.lang==='html')&&file.text.slice(template.loc.start.offset,template.loc.end.offset)===template.content)projection={text:template.content,metadata:{schema:'source-excerpt:m8.4-v1',language:'html',start:template.loc.start.offset,end:template.loc.end.offset,digest:sha(Buffer.from(template.content,'utf8'))}};
      }
      inspect(file,generated,JSON.parse(Buffer.from(mapArtifact.data,'base64').toString('utf8')),'https://captured.invalid/'+file.path+'.map',{mapArtifact:mapArtifact.id,generatedArtifact:generatedArtifact.id},true,projection);
    }catch{s.gap(file,'F08','SOURCE_MAP_FORMAT_INVALID');}
  }
}
module.exports={analyze};
