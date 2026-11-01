'use strict';
const fs=require('node:fs'),path=require('node:path');
const {digest,sha}=require('./common.cjs');
function tree(root){
  const rows=[];
  function walk(dir,prefix=''){
    for(const entry of fs.readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0)){
      if(entry.name==='.bin'||entry.name==='.package-lock.json')continue;
      const relative=prefix+entry.name,file=path.join(dir,entry.name);if(entry.isSymbolicLink())throw Error('ARCHITECTURE_SDK_ALIAS');
      if(entry.isDirectory())walk(file,relative+'/');else if(entry.isFile()){const bytes=fs.readFileSync(file);rows.push({path:relative,size:bytes.length,sha256:sha(bytes)});}else throw Error('ARCHITECTURE_SDK_SPECIAL_FILE');
    }
  }walk(root);return {digest:digest(['architecture-tool-tree:m8.4-v1',rows]),files:rows.length,bytes:rows.reduce((a,r)=>a+r.size,0)};
}
module.exports={tree};
if(require.main===module)process.stdout.write(JSON.stringify(tree(process.argv[2]))+'\n');
