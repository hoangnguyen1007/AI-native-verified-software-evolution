'use strict';
// Regenerates compact native output from a trusted authored fixture; never analyzes a repository on the host.
const fs=require('node:fs');const path=require('node:path');const worker=require('../../../main/resources/polyglot/typescript-worker.cjs');
const modulePath=process.env.POLYGLOT_TYPESCRIPT_MODULE;if(!modulePath)throw Error('PINNED_TYPESCRIPT_MODULE_REQUIRED');
const ts=worker.compiler(modulePath);const out=path.resolve(process.argv[2]);
const source="export function twice(value:number):number { return value*2; }\nexport const answer=twice(4);\n";
const sourceDigest=worker.digest(['authored-typescript-fixture',source]);
const scope=worker.digest(['language-context:m8.4-v1',sourceDigest,'root','app','main','baseline','typescript','5.9.3']);
const sourceHash='sha256:'+require('node:crypto').createHash('sha256').update(source).digest('hex');
const files=[{path:'main.ts',text:source,sha256:sourceHash}];
for(const name of fs.readdirSync(path.dirname(modulePath)).filter(name=>/^lib\..*\.d\.ts$/.test(name))){const text=fs.readFileSync(path.join(path.dirname(modulePath),name),'utf8');files.push({path:'toolchain/lib/'+name,text,sha256:'sha256:'+require('node:crypto').createHash('sha256').update(text).digest('hex')});}
const input={schema:'typescript-capture-request:m8.4-v1',scope,repositoryScope:'urn:test:typescript-native',packageScope:'app',language:'typescript',projectFiles:['main.ts'],files,options:{target:'ES2022',module:'CommonJS',moduleResolution:'Node10',strict:true}};
const result=worker.analyze(input,ts);
const header={schema:result.schema,scope,language:result.language,engine:result.engine,tool:result.tool,extractor:result.extractor,trust:result.trust};
const end={schema:'typescript-capture-end:m8.4-v1',scope,documents:result.documents.length,census:result.census.length,observations:result.observations.length,diagnostics:result.diagnostics};
fs.mkdirSync(out,{recursive:true});
// These are generator-owned test resources. Preserve old revisions by generating into a new directory when inputs change.
for(const name of ['main.ts','capture.ndjson','fixture.json'])if(fs.existsSync(path.join(out,name)))throw Error('REFUSE_TO_OVERWRITE_NATIVE_FIXTURE');
fs.writeFileSync(path.join(out,'main.ts'),source);
fs.writeFileSync(path.join(out,'capture.ndjson'),[header,...result.documents,...result.census,...result.observations,end].map(worker.canonical).join('\n')+'\n');
fs.writeFileSync(path.join(out,'fixture.json'),worker.canonical({schema:'authored-typescript-fixture:m8.4-v1',inventory:sourceDigest,scope,source:sourceHash,compiler:result.tool,extractor:result.extractor,version:ts.version,trust:'PRODUCER_ASSERTION',execution:'HOST_AUTHORED_FIXTURE_ONLY'})+'\n');
process.stdout.write(JSON.stringify({documents:result.documents.length,census:result.census.length,observations:result.observations.length,errors:result.diagnostics.filter(d=>d.category==='Error').length})+'\n');
