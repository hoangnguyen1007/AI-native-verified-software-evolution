'use strict';
// Only fixed, public platform-owned SDK libraries are read here. No target discovery.
const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');
const root=path.resolve('.evo/tools/m845-architecture/node_modules');
const hash=s=>'sha256:'+crypto.createHash('sha256').update(s).digest('hex');
function libraries(names=[],packageRoot=root){
  if(path.resolve(packageRoot)!==root){
    if(path.resolve(packageRoot)!==path.resolve('.evo/tools/m845-vue-declarations-3.5.22/node_modules'))throw Error('FIXTURE_LIBRARY_ROOT_UNREGISTERED');
    const pin=require('./architecture-vue-declarations/pin.json');
    if(hash(fs.readFileSync(path.join(packageRoot,'../package-lock.json')))!==pin.lock||require('../../../main/resources/polyglot/architecture/tool-tree.cjs').tree(packageRoot).digest!==pin.tree.digest)throw Error('FIXTURE_LIBRARY_BYTES_CHANGED');
  }
  const result=[];
  function walk(dir,prefix,role){for(const item of fs.readdirSync(dir,{withFileTypes:true})){
    if(item.isSymbolicLink())throw Error('FIXTURE_SDK_LINK');const p=path.join(dir,item.name),logical=prefix+'/'+item.name;
    if(item.isDirectory()){if(item.name!=='node_modules')walk(p,logical,role);}
    else if(/\.d\.[cm]?ts$/.test(item.name)||item.name==='package.json'){
      const text=fs.readFileSync(p,'utf8');result.push({path:logical,text,sha256:hash(text),role});
    }
  }}
  walk(path.join(root,'typescript/lib'),'toolchain/lib','toolchain');
  for(const name of names)walk(path.join(packageRoot,name),'node_modules/'+name,'dependency');return result;
}
module.exports={libraries};
