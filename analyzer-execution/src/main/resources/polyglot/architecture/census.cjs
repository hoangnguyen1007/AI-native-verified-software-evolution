'use strict';
const {digest}=require('./common.cjs');
const categories=Array.from({length:12},(_,i)=>'F'+String(i+1).padStart(2,'0'));
// This denominator is created before emitters run. It never uses fact/gap counts.
function enumerate(s){
  const rows=[];
  for(const f of s.files.values())for(const category of categories)rows.push({id:digest(['architecture-obligation:m8.4-v1',s.input.scope,f.path,f.sha256,category]),path:f.path,content:f.sha256,category});
  return rows;
}
function dispositions(s){
  const rows=[];
  for(const f of s.files.values()){
    let disposition='NON_CODE_RESOURCE',reason=null;
    if(['dependency','toolchain'].includes(f.role))disposition='CAPTURED_RESOLUTION_INPUT';
    else if(f.text===null){disposition='BINARY';if(!s.binaryProviders?.has(f.path))reason='BINARY_METADATA_PROVIDER_REQUIRED';}
    else if(s.native.some(n=>n.path===f.path))disposition='NATIVE_CONTEXT_INPUT';
    else if(/\.(?:[cm]?[jt]sx?|html?|css|vue|svelte|astro|jsonc?|ya?ml|toml|proto|graphql|gql|sql|xml|xsd|properties)$/.test(f.path)||/(?:^|\/)\.env(?:\.|$)/.test(f.path))disposition='ANALYSIS_INPUT';
    else if(/\.(?:tf|hcl|sh|bash|ps1|gradle|kts|bzl|jsp|ftl|jinja2?|razor|cshtml|blade\.php|erb|pug|less|sass|scss)$/.test(f.path)||/(?:^|\/)(?:Dockerfile(?:\..*)?|Makefile|Jenkinsfile|build\.sbt)$/.test(f.path)){
      disposition='DECLARATIVE_OR_EXECUTABLE_INPUT';if(!s.native.some(n=>n.path===f.path))reason='DIALECT_OR_NATIVE_MODEL_PROVIDER_REQUIRED';
    }
    else if(/\.(?:md|txt|rst|adoc|svg|csv|tsv)$/.test(f.path))disposition='DECLARED_DOCUMENT_OR_ASSET';
    else{disposition='UNCLASSIFIED';reason='RESOURCE_CLASSIFICATION_REQUIRED';}
    rows.push({path:f.path,content:f.sha256,role:f.role||'unknown',disposition});
    if(/\.min\.[cm]?js$/.test(f.path)&&!s.validatedSourceMaps?.has(f.path))s.gap(f,'F08','POSSIBLE_MINIFIED_SOURCE_MAPPING_REQUIRED');
    if(reason&&!s.obligations.some(o=>o.path===f.path&&o.reason===reason))s.gap(f,'F12',reason);
  }
  return rows;
}
module.exports={categories,enumerate,dispositions};
