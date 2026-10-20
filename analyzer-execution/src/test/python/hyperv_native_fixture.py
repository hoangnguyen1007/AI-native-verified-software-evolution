#!/usr/bin/env python3
"""Controller-side authored fixture runner. Requires paramiko 5.1.0 and an owned candidate VM.
Does not admit arbitrary repositories or produce validated semantic observations.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import uuid
import paramiko
from bounded_tunnel import BoundedTunnel

parser=argparse.ArgumentParser()
parser.add_argument('--config',required=True,type=Path)
parser.add_argument('--broker-root',required=True,type=Path)
parser.add_argument('--mode',choices=['online','offline'],required=True)
parser.add_argument('--family',choices=['maven','gradle'],default='maven')
parser.add_argument('--broken-sibling',action='store_true')
parser.add_argument('--scenario',choices=['generated','forged-capture'],default='generated')
args=parser.parse_args()
if args.family=='maven' and args.scenario!='generated':parser.error('scenario requires Gradle fixture')
repo=Path(__file__).resolve().parents[4]
config=json.loads(args.config.read_text(encoding='utf-8-sig'))
classpath=(repo/'analyzer-execution/target/m843-final/dependencies.classpath').read_text().strip()
classpath=str(repo/'analyzer-execution/target/m843-final/test-classes')+';'+str(repo/'analyzer-execution/target/m843-final/classes')+';'+classpath
command=['java','-cp',classpath,'com.evolution.analysis.execution.broker.BrokerFixtureMain',str(args.broker_root.resolve())]
if args.mode=='offline':command.append('offline')
process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
client=paramiko.SSHClient()
keys=paramiko.HostKeys(config['knownHostsFile'])
for kind,key in keys[config['hostKeyAlias']].items():client.get_host_keys().add(config['workerHost'],kind,key)
output=queue.Queue()
def broker_lines():
    for line in process.stdout:
        output.put(line.rstrip())
threading.Thread(target=broker_lines,daemon=True).start()

tunnel=None

def remote(command,data=None,deadline=300):
    stdin,out,err=client.exec_command(command,timeout=deadline)
    if data is not None:stdin.write(data)
    stdin.channel.shutdown_write()
    channel=out.channel; captured=bytearray(); errors=bytearray(); end=time.monotonic()+deadline
    while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
        if time.monotonic()>end:channel.close();raise RuntimeError('FIXTURE_WATCHDOG_EXPIRED')
        if channel.recv_ready():captured.extend(channel.recv(65536))
        if channel.recv_stderr_ready():errors.extend(channel.recv_stderr(65536))
        if len(captured)+len(errors)>1048576:channel.close();raise RuntimeError('FIXTURE_LOG_BUDGET_EXHAUSTED')
        time.sleep(0.01)
    return channel.recv_exit_status(),bytes(captured),bytes(errors)

try:
    ready=output.get(timeout=30)
    if not ready.startswith('READY '):raise RuntimeError('BROKER_NOT_READY')
    port=int(ready.split()[1])
    client.connect(config['workerHost'],username='evoctl',key_filename=config['identityFile'],allow_agent=False,look_for_keys=False,timeout=10)
    tunnel=BoundedTunnel(port)
    transport=client.get_transport(); transport.request_port_forward('127.0.0.1',48184,handler=tunnel.forward)
    attempt=uuid.uuid4().hex; work='/work/'+attempt
    exit_code,_,_=remote('sudo -n install -d -m 0750 -o evoctl -g evoctl '+work)
    if exit_code:raise RuntimeError('FIXTURE_SETUP_FAILED')
    input_hashes={}
    with client.open_sftp() as sftp:
        fixture=repo/('analyzer-execution/src/test/resources/native/'+args.family+'-'+args.scenario)
        files=sorted(p for p in fixture.rglob('*') if p.is_file())
        made={work}
        for path in files:
            relative=path.relative_to(fixture).as_posix(); remote_path=work+'/'+relative
            parent=Path(relative).parent.as_posix()
            current=work
            if parent!='.':
                for part in parent.split('/'):
                    current+='/'+part
                    if current not in made:sftp.mkdir(current);made.add(current)
            raw=path.read_bytes()
            with sftp.open(remote_path,'wx') as target:target.write(raw)
            input_hashes[relative]=hashlib.sha256(raw).hexdigest()
        settings='<settings><mirrors><mirror><id>captured-central</id><mirrorOf>*</mirrorOf><url>http://127.0.0.1:48184/central/</url></mirror></mirrors></settings>'
        with sftp.open(work+'/settings.xml','wx') as target:target.write(settings.encode())
    exit_code,_,_=remote('sudo -n chown -R evobuild:evobuild '+work)
    if exit_code:raise RuntimeError('FIXTURE_SETUP_FAILED')
    if args.family=='maven':
        if args.broken_sibling:raise RuntimeError('MAVEN_BROKEN_FIXTURE_NOT_REGISTERED')
        argv=['/usr/bin/mvn','--batch-mode','--no-transfer-progress','-s',work+'/settings.xml','-Dmaven.repo.local='+work+'/m2','compile']
    else:
        argv=['/opt/evo-tools/gradle-8.14.6/bin/gradle','--no-daemon','--console=plain','--max-workers=1','-g',work+'/gradle-home','--project-cache-dir',work+'/project-cache','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2']
        if args.mode=='offline':argv.append('--offline')
        if args.broken_sibling:argv+=['--continue','-PbrokenSibling=true','compileJava']
        argv+=[':app:runCaller',':app:runIntegration']
    request=dict(attempt=attempt,argv=argv,memoryBytes=1073741824,tasksMax=128,cpuPercent=100,work=work)
    agent_status,agent_before,_=remote('sudo -n sha256sum /usr/local/lib/evo/runner.py',deadline=10)
    if agent_status:raise RuntimeError('PROTECTED_AGENT_PROBE_FAILED')
    expected_agent=hashlib.sha256((repo/'analyzer-execution/src/main/resources/guest/runner.py').read_bytes()).hexdigest()
    if agent_before.decode().split()[0]!=expected_agent:raise RuntimeError('PROTECTED_AGENT_PIN_MISMATCH')
    print('RUNNING '+args.mode+' authored '+args.family+' generator reactor',flush=True)
    code,logs,errors=remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py',json.dumps(request),300)
    args.broker_root.mkdir(parents=True,exist_ok=True)
    (args.broker_root/(args.mode+'-maven.log')).write_bytes(logs+errors)
    print('NATIVE_EXIT '+str(code),flush=True)
    agent_status,agent_after,_=remote('sudo -n sha256sum /usr/local/lib/evo/runner.py',deadline=10)
    if agent_status or agent_before!=agent_after:raise RuntimeError('PROTECTED_AGENT_CHANGED')
    result=dict(protectedAgentExpectedSha256=expected_agent,protectedAgentSha256=agent_before.decode().split()[0],scenario=args.scenario,launch=request,schema='authored-native-candidate-run:m8.4-v1',mode=args.mode,family=args.family,brokenSibling=args.broken_sibling,attempt=attempt,inputs=input_hashes,buildExit=code,qualified=False,semanticTrust='PRODUCER_ASSERTION')
    expected_exit=1 if args.broken_sibling else 0
    if code==expected_exit:
        classpath='app/target/classes:model/target/classes' if args.family=='maven' else 'app/build/classes/java/main:model/build/classes/java/main:support/build/classes/java/main'
        check=dict(request,argv=['/usr/bin/java','-cp',classpath,'fixture.Caller'])
        run_code,_,_=remote('sudo -n /usr/bin/python3 /usr/local/lib/evo/runner.py',json.dumps(check),30)
        result['consumerExit']=run_code
        output_paths=['model/target/generated-sources/evo/fixture/Generated.java','model/target/classes/fixture/Generated.class','app/target/classes/fixture/Caller.class'] if args.family=='maven' else ['model/build/generated/sources/evo/fixture/Generated.java','model/build/classes/java/main/fixture/Generated.class','app/build/classes/java/main/fixture/Caller.class','app/build/classes/java/integration/fixture/IntegrationCaller.class','support/build/classes/java/main/fixture/Helper.class','buildSrc/build/classes/java/main/fixture/RootPlugin.class']
        if args.scenario=='forged-capture':output_paths+=['capture/claimed.json','capture/index.json','capture/tamper.json']
        result['outputs']={}
        for relative in output_paths:
            read_code,raw,_=remote('sudo -n cat -- '+work+'/'+relative,deadline=15)
            if read_code:raise RuntimeError('FIXTURE_OUTPUT_MISSING')
            digest=hashlib.sha256(raw).hexdigest(); result['outputs'][relative]=dict(sha256=digest,size=len(raw))
            dest=args.broker_root/'outputs'/digest
            dest.parent.mkdir(exist_ok=True)
            if not dest.exists():dest.write_bytes(raw)
        if args.broken_sibling:
            missing='broken/build/classes/java/main/fixture/Broken.class'
            absent,_,_=remote('sudo -n test -e '+work+'/'+missing,deadline=10)
            if absent==0:raise RuntimeError('BROKEN_SIBLING_EMITTED_UNEXPECTED_CLASS')
            result['unavailableOutputs']={missing:'EXPECTED_COMPILATION_FAILURE'}
        if run_code:raise RuntimeError('GENERATED_CONSUMER_FAILED')
    (args.broker_root/(args.mode+'-result.json')).write_text(json.dumps(result,sort_keys=True),encoding='utf-8')
    print(json.dumps(dict(family=args.family,mode=args.mode,attempt=attempt,buildExit=code,consumerExit=result.get('consumerExit'),outputs=len(result.get('outputs',{})),brokenSibling=args.broken_sibling,resultFile=str(args.broker_root/(args.mode+'-result.json')),qualified=False),sort_keys=True),flush=True)
    if code!=expected_exit:raise RuntimeError('AUTHORED_NATIVE_BUILD_UNEXPECTED_EXIT: inspect bounded runtime log')
finally:
    if tunnel is not None:tunnel.close()
    client.close()
    try:process.stdin.write('\n');process.stdin.flush();process.wait(timeout=15)
    except Exception:process.kill()
    while not output.empty():print(output.get_nowait(),flush=True)
    errors=process.stderr.read(2000)
    if errors:print(errors,file=sys.stderr)
