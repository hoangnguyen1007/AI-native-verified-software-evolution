'use strict';
// Fixed first-party schema only. Both generators parse source; the generated app is never loaded.
const fs=require('node:fs'),path=require('node:path');
const [repository,sdk,source,output]=process.argv.slice(2);
const tools=require(path.join(repository,'analyzer-execution/src/main/resources/polyglot/architecture/bridge-models.cjs')).load(sdk);
const invoke=(main,args)=>new Promise((resolve,reject)=>main(args,(error,text)=>error?reject(error):resolve(text)));
(async()=>{
 const js=await invoke(tools.pbjs.main,['--target','static-module','--wrap','commonjs','--force-number',source]);
 fs.writeFileSync(path.join(output,'users.js'),js);
 fs.writeFileSync(path.join(output,'users.d.ts'),await invoke(tools.pbts.main,['--no-comments',path.join(output,'users.js')]));
 fs.writeFileSync(path.join(output,'generator.json'),JSON.stringify({tool:tools.identity,profile:'protobufjs-static-commonjs:8.8.1/pbjs-pbts:2.7.1'}));
})().catch(()=>{process.stderr.write('AUTHORED_BRIDGE_GENERATOR_FAILED\n');process.exitCode=2;});
