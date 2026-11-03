'use strict';
// Authored platform fixture only. No repository script, config or application module is executed.
const fs=require('node:fs'),path=require('node:path');
const [repository,requestFile,outputFile,toolsRoot,routingRoot,bridgeRoot]=process.argv.slice(2);
const worker=require(path.join(repository,'analyzer-execution/src/main/resources/polyglot/architecture/worker.cjs'));
const {canonical,digest}=require(path.join(repository,'analyzer-execution/src/main/resources/polyglot/architecture/common.cjs'));
const {libraries}=require(path.join(repository,'analyzer-execution/src/test/resources/polyglot/architecture-fixture.cjs'));
(async()=>{
  const input=JSON.parse(fs.readFileSync(requestFile,'utf8'));
  const configs=input.supportingConfigs;delete input.supportingConfigs;
  const tools=await require(path.join(repository,'analyzer-execution/src/main/resources/polyglot/architecture/tools.cjs')).load(toolsRoot,routingRoot,undefined,undefined,bridgeRoot);
  input.files.push(...libraries(['@types/react','csstype','protobufjs','long']));
  const result=await worker.analyze(input,{tools});
  const configInput={...input,scope:digest([input.scope,'captured-build-configurations']),app:'build-config',profiles:[],context:{buildRoot:'.',module:'build-config',sourceSet:'configuration',variant:'authored',conditions:{}},files:configs};
  const configResult=await worker.analyze(configInput,{tools});
  fs.writeFileSync(outputFile,canonical({input,result,configuration:{input:configInput,result:configResult}}));
  process.stdout.write('BRIDGE_JOURNEY_CAPTURE '+result.identity+' '+result.facts.length+' facts\n');
})().catch(()=>{process.stderr.write('BRIDGE_JOURNEY_CAPTURE_FAILED\n');process.exitCode=2;});
