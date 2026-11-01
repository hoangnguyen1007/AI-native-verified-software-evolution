'use strict';
// Fixed SDK data resolvers only. Target modules, hooks and plugins are never loaded.
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module'),{pathToFileURL}=require('node:url');
(async()=>{
  const request=JSON.parse(fs.readFileSync(0,'utf8')),native=createRequire(path.resolve(process.argv[2],'package.json'));
  const {NuxtConfigSchema}=await import(pathToFileURL(native.resolve('@nuxt/schema'))),{applyDefaults}=await import(pathToFileURL(native.resolve('untyped')));
  const raw={...request.config,rootDir:request.config.rootDir?path.resolve(request.root,request.config.rootDir):request.root,workspaceDir:request.config.workspaceDir||request.namespace,dev:request.mode==='development'};
  const config=await applyDefaults(NuxtConfigSchema,raw);
  const relative=value=>{const contained=path.relative(request.namespace,value);if(path.isAbsolute(contained)||contained==='..'||contained.startsWith('..'+path.sep))throw Error('NUXT_RESOLVED_PATH_OUTSIDE_CAPTURE');return path.relative(request.root,value).replaceAll('\\','/')||'.';};
  process.stdout.write(JSON.stringify({rootDir:relative(config.rootDir),srcDir:relative(config.srcDir),pagesDir:relative(path.resolve(config.srcDir,config.dir.pages)),baseURL:config.app.baseURL,ssr:config.ssr,pages:config.pages??null}));
})().catch(()=>{process.stderr.write('NUXT_NATIVE_CONFIGURATION_FAILED\n');process.exitCode=2;});
