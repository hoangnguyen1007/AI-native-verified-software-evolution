'use strict';
const {sha}=require('./common.cjs');
function attributeRange(text,location){
  const raw=text.slice(location.startOffset,location.endOffset),equals=raw.indexOf('=');
  if(equals<0)return [location.startOffset,location.endOffset];
  let start=equals+1;while(/\s/.test(raw[start]||'')&&start<raw.length)start++;
  let end=raw.length;if(raw[start]==='"'||raw[start]==="'"){start++;end--;}
  return [location.startOffset+start,location.startOffset+end];
}
function html(s,file){
  const errors=[];const ast=s.tools.parse5.parse(file.text,{sourceCodeLocationInfo:true,onParseError:e=>errors.push(e)});
  s.native.push({path:file.path,engine:'parse5',mode:'html5',diagnostics:errors.map(e=>e.code)});
  const fallback=new URL('https://captured.invalid/'+file.path.split('/').map(encodeURIComponent).join('/'));let baseNode=null;
  function findBase(node){if(!baseNode&&node.tagName==='base'&&node.attrs?.some(a=>a.name==='href'))baseNode=node;for(const child of node.childNodes||[])findBase(child);}findBase(ast);
  let base={url:fallback.href,origin:fallback.origin,external:false};
  if(baseNode){const value=baseNode.attrs.find(a=>a.name==='href').value;s.htmlBaseExplicit.add(file.path);
    try{base={url:new URL(value,fallback).href,origin:fallback.origin,external:/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(value)};}
    catch{s.gap(file,'F07','RESOURCE_BASE_CONTEXT_REQUIRED');base={url:'invalid:',origin:fallback.origin,external:true};}
  }s.resourceBases.set(file.path,base);
  function visit(node){
    const loc=node.sourceCodeLocation;
    for(const attr of node.attrs||[]){
      const position=loc?.attrs?.[attr.name];if(!position)continue;
      const [start,end]=attributeRange(file.text,position);
      if(node.tagName==='base'&&attr.name==='href')s.fact(file,'F10','DECLARED_DOCUMENT_BASE',start,end,null,{baseDigest:sha(Buffer.from(attr.value)),selected:node===baseNode,activation:'DECLARED_ONLY'},'CONDITIONAL');
      else if(['src','href','poster','data'].includes(attr.name))s.reference(file,'F07','RESOURCE_REFERENCE',start,end,attr.value,{element:node.tagName,attribute:attr.name});
      if(attr.name.startsWith('on'))s.fact(file,'F04','EVENT_HANDLER',start,end,null,{event:attr.name.slice(2),binding:'inline-source'},'PARTIAL');
      if(node.tagName==='iframe'&&attr.name==='src')s.fact(file,'F09','EMBEDDED_SURFACE',start,end,null,{kind:'iframe',declared:true},'CONDITIONAL');
    }
    if(node.tagName?.includes('-')&&loc)s.fact(file,'F02','CUSTOM_ELEMENT_USE',loc.startOffset,loc.endOffset,null,{tag:node.tagName},'UNRESOLVED');
    if(node.tagName==='script'&&(node.attrs||[]).some(a=>a.name==='type'&&a.value==='importmap')){
      const content=node.childNodes?.find(n=>n.nodeName==='#text');if(content?.sourceCodeLocation){const p=content.sourceCodeLocation;try{const value=JSON.parse(content.value);for(const specifier of Object.values(value.imports||{}))if(typeof specifier==='string')s.reference(file,'F08','IMPORT_MAP',p.startOffset,p.endOffset,specifier);}catch{s.gap(file,'F08','IMPORT_MAP_INVALID',p.startOffset,p.endOffset);}}
    }
    if(node.tagName==='style')for(const content of node.childNodes||[])if(content.nodeName==='#text'&&content.sourceCodeLocation){
      const p=content.sourceCodeLocation;css(s,file,file.text.slice(p.startOffset,p.endOffset),p.startOffset);
    }
    for(const child of node.childNodes||[])visit(child);if(node.content)visit(node.content);
  }
  visit(ast);return ast;
}
function css(s,file,text=file.text,base=0){
  if(file.text.slice(base,base+text.length)!==text){s.gap(file,'F07','STYLE_SOURCE_MAPPING_REQUIRED');return;}
  if(/\.(?:scss|sass|less)$/.test(file.path))s.gap(file,'F07','STYLE_PREPROCESSOR_REQUIRED');
  let ast;
  try{
    ast=s.tools.postcss.parse(text,{from:file.path});s.native.push({path:file.path,engine:'postcss',mode:'css',diagnostics:[]});
    ast.walk(node=>{
      const start=base+node.source.start.offset,end=base+(node.source.end?.offset??node.source.start.offset+node.toString().length);
      if(node.type==='decl'&&node.prop.startsWith('--'))s.fact(file,'F07','CSS_CUSTOM_PROPERTY',start,end,null,{name:node.prop});
      if(node.type==='atrule'&&node.name==='layer')s.fact(file,'F07','CSS_LAYER',start,end,null,{name:node.params});
      if(node.type==='decl'&&node.prop==='composes')s.gap(file,'F07','CSS_MODULE_RESOLUTION_REQUIRED',start,end);
      if(node.type==='atrule'&&['tailwind','apply','plugin','config'].includes(node.name))s.gap(file,'F07','STYLE_PLUGIN_MODEL_REQUIRED',start,end);
    });
  }catch{s.gap(file,'F07','STYLE_PARSE_ERROR');}
  try{
    const diagnostics=[],tree=s.tools['css-tree'],parsed=tree.parse(text,{positions:true,parseCustomProperty:true,onParseError(){diagnostics.push('STYLE_VALUE_OR_DIALECT_MAPPING_REQUIRED');s.gap(file,'F07','STYLE_VALUE_OR_DIALECT_MAPPING_REQUIRED',base,base+text.length);}}),imports=new Set();
    const range=node=>[base+node.loc.start.offset,base+node.loc.end.offset];
    tree.walk(parsed,{visit:'Atrule',enter(node){if(node.name==='import'){
      const value=node.prelude?.children?.first;
      if(value&&['String','Url'].includes(value.type)){s.reference(file,'F07','STYLE_DEPENDENCY',...range(value),value.value);imports.add(value.loc.start.offset+':'+value.loc.end.offset);}
      else s.gap(file,'F07','DYNAMIC_STYLE_REFERENCE',...range(node));
    }}});
    tree.walk(parsed,{visit:'Url',enter(node){if(!imports.has(node.loc.start.offset+':'+node.loc.end.offset))s.reference(file,'F07','STYLE_RESOURCE',...range(node),node.value);}});
    s.native.push({path:file.path,engine:'css-tree',mode:base!==0||text!==file.text?'css-source-excerpt':'css-native-references',diagnostics,...(base!==0||text!==file.text?{sourceProjection:{schema:'source-excerpt:m8.4-v1',language:'css',start:base,end:base+text.length,digest:sha(Buffer.from(text,'utf8'))}}:{})});
  }catch{s.gap(file,'F07','STYLE_REFERENCE_PARSE_ERROR',base,base+text.length);}
  return ast;
}
module.exports={html,css,attributeRange};
