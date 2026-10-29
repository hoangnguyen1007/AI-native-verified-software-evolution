'use strict';
// Only the fixed platform-authored fixture below is compiled. No target module,
// npm lifecycle, plugin or application code is imported or executed.
const fs=require('node:fs');const path=require('node:path');
const worker=require('../../../main/resources/polyglot/typescript-worker.cjs');
const compiler=process.env.POLYGLOT_TYPESCRIPT_MODULE;if(!compiler)throw Error('PINNED_TYPESCRIPT_MODULE_REQUIRED');
const destination=process.argv[2];if(!destination||fs.existsSync(destination))throw Error('NEW_FIXTURE_REVISION_REQUIRED');
const previous=path.resolve(__dirname,'../../../../../analyzer/src/test/resources/polyglot/typescript-native-v3');
const metadata=JSON.parse(fs.readFileSync(path.join(previous,'fixture.json'),'utf8'));
const mode=process.argv[3]||'plain';if(!['plain','embedded'].includes(mode))throw Error('AUTHORED_FIXTURE_MODE_REQUIRED');
const source='export function twice(value:number):number { return value*2; }\nexport const answer=twice(4);\n';
const sha=raw=>'sha256:'+require('node:crypto').createHash('sha256').update(raw).digest('hex');
const file=(name,text)=>({path:name,text,sha256:sha(Buffer.from(text,'utf8'))});
const input=file('main.ts',source);if(metadata.source!==input.sha256)throw Error('AUTHORED_FIXTURE_SOURCE_REVISION_CHANGED');
const prefix='\ufeff<!-- \u{1f600} -->\r\n<script lang="ts">\r\n';const container=prefix+source+'</script>\r\n';
if(mode==='embedded'){metadata.inventory=worker.digest([['component.vue',sha(Buffer.from(container,'utf8'))]]);metadata.scope=worker.digest(['language-context:m8.4-v1',metadata.inventory,'root','app','main','baseline','typescript','5.9.3']);input.role='embedded';}
const libraries=fs.readdirSync(path.dirname(compiler)).filter(name=>/^lib\..*\.d\.ts$/.test(name)).sort().map(name=>file('toolchain/lib/'+name,fs.readFileSync(path.join(path.dirname(compiler),name),'utf8')));
const result=worker.analyze({schema:'typescript-capture-request:m8.4-v1',scope:metadata.scope,repositoryScope:'urn:test:typescript-native',packageScope:'app',language:'typescript',projectFiles:['main.ts'],files:[input,...libraries],options:{target:'ES2022',module:'CommonJS',moduleResolution:'Node10',strict:true,allowJs:false,checkJs:false}},worker.compiler(compiler));
const header={schema:result.schema,scope:result.scope,language:result.language,engine:result.engine,tool:result.tool,extractor:result.extractor,trust:result.trust};
const end={schema:'typescript-capture-end:m8.4-v1',scope:result.scope,documents:result.documents.length,census:result.census.length,observations:result.observations.length,diagnostics:result.diagnostics};
fs.mkdirSync(destination,{recursive:false});
fs.writeFileSync(path.join(destination,'generator.cjs'),fs.readFileSync(__filename),{flag:'wx'});
fs.writeFileSync(path.join(destination,'main.ts'),source,{flag:'wx'});
if(mode==='embedded'){
 fs.writeFileSync(path.join(destination,'component.vue'),container,{flag:'wx'});
 const original={path:'component.vue',content:{digest:sha(Buffer.from(container,'utf8')),size:Buffer.byteLength(container,'utf8')},charset:'UTF-8'};
 const map={generated:{path:'main.ts',content:{digest:input.sha256,size:Buffer.byteLength(source,'utf8')},charset:'UTF-8'},originals:{'component.vue':original},segments:[{generatedStart:0,generatedEnd:source.length,original:'component.vue',originalStart:prefix.length,originalEnd:prefix.length+source.length}],method:'authored.exact-script-region:m8.4-v1'};
 fs.writeFileSync(path.join(destination,'source-map.json'),worker.canonical(map)+'\n',{flag:'wx'});
}
fs.writeFileSync(path.join(destination,'capture.ndjson'),[header,...result.documents,...result.census,...result.observations,end].map(worker.canonical).join('\n')+'\n',{flag:'wx'});
fs.writeFileSync(path.join(destination,'fixture.json'),worker.canonical({...metadata,extractor:result.extractor,compiler:result.tool,generator:sha(fs.readFileSync(__filename))})+'\n',{flag:'wx'});
process.stdout.write(JSON.stringify({documents:result.documents.length,census:result.census.length,observations:result.observations.length,diagnostics:result.diagnostics.length,extractor:result.extractor,execution:metadata.execution})+'\n');
