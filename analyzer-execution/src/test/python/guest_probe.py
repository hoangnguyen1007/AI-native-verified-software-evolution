#!/usr/bin/python3
"""Authored conformance fixtures, run only in an owned candidate Ubuntu worker as root.
Passing this subset does not qualify the backend or open target execution admission.
"""
import hashlib
import json
from pathlib import Path
import pwd
import subprocess
import tempfile
import uuid

RUNNER = '/usr/local/lib/evo/runner.py'
results = {}


def run(name, argv, expected=0, memory=268435456, controller_env=None):
    attempt = uuid.uuid4().hex
    work = Path('/work') / attempt
    work.mkdir(mode=0o750)
    account = pwd.getpwnam('evobuild')
    __import__('os').chown(work, account.pw_uid, account.pw_gid)
    request = dict(attempt=attempt, argv=argv, memoryBytes=memory,
                   tasksMax=32, cpuPercent=50, work=str(work))
    result = subprocess.run(['/usr/bin/python3', RUNNER], input=json.dumps(request).encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, env=controller_env)
    journal = b''
    if name == 'memory-max-kills-bounded-allocation':
        journal = subprocess.run(['/usr/bin/journalctl', '--no-pager', '-b', '-u', 'evo-' + attempt, '-o', 'cat'], stdout=subprocess.PIPE, timeout=10).stdout
    proved_oom = name != 'memory-max-kills-bounded-allocation' or b'oom-kill' in journal
    results[name] = dict(passed=result.returncode == expected and proved_oom, exit=result.returncode,
                         stdoutSha256=hashlib.sha256(result.stdout).hexdigest(),
                         stderrSha256=hashlib.sha256(result.stderr).hexdigest())
    if result.returncode != expected or not proved_oom:
        # Only authored fixture diagnostics, bounded to avoid untrusted log ingestion.
        results[name]['diagnostic'] = result.stderr[-800:].decode(errors='replace')
    return work, result


run('clean-target-environment', ['/usr/bin/python3', '-c',
    "import os; assert 'EVO_CONTROLLER_SENTINEL' not in os.environ; "
    "assert 'JAVA_TOOL_OPTIONS' not in os.environ; assert 'MAVEN_OPTS' not in os.environ; "
    "assert os.environ['PATH']=='/usr/bin:/bin'; assert os.environ['TZ']=='UTC'"],
    controller_env={**__import__('os').environ, 'EVO_CONTROLLER_SENTINEL':'fixture-only',
                    'JAVA_TOOL_OPTIONS':'fixture-only', 'MAVEN_OPTS':'fixture-only'})
run('unprivileged-private-home-readonly-system', ['/usr/bin/python3', '-c',
    "import os,pathlib; assert os.getuid()!=0; assert pathlib.Path.home().is_dir(); "
    "assert not os.access('/home/evoctl',os.R_OK); assert not os.access('/home/nguyen',os.R_OK); "
    "assert not os.access('/etc',os.W_OK); assert not pathlib.Path('/mnt/c').exists(); "
    "assert not pathlib.Path('/var/run/docker.sock').exists()"])
run('no-external-or-metadata-route', ['/usr/bin/python3', '-c', """
import socket
for ip in ['169.254.169.254','8.8.8.8','10.0.0.1']:
    s=socket.socket()
    s.settimeout(0.5)
    try:
        s.connect((ip,80))
        raise AssertionError('unexpected egress')
    except OSError:
        pass
    finally:
        s.close()
"""])
run('memory-max-kills-bounded-allocation', ['/usr/bin/python3', '-c',
    "data=bytearray(512*1024*1024); raise AssertionError('memory budget not enforced')"], expected=1, memory=134217728)
run('tasks-max-rejects-bounded-fork', ['/usr/bin/python3', '-c', """
import errno,os,time
children=[]
try:
    for _ in range(64):
        child=os.fork()
        if child==0:
            time.sleep(1)
            os._exit(0)
        children.append(child)
    raise AssertionError('process limit not enforced')
except OSError as error:
    assert error.errno==errno.EAGAIN, error
    assert len(children)<32, len(children)
finally:
    for child in children:os.waitpid(child,0)
"""])
work, _ = run('java-generator', ['/usr/bin/python3', '-c',
    "from pathlib import Path;Path('Generated.java').write_text('public class Generated { public static int value(){return 42;} }'); "
    "Path('Caller.java').write_text('public class Caller { public static void main(String[] a){if(Generated.value()!=42)throw new AssertionError();} }')"])
# Fresh units, exact same working tree: actual compiler output is retained as bytes.
for name, argv in [('javac-generated-consumer',['/usr/bin/javac','Generated.java','Caller.java']),
                   ('java-generated-consumer',['/usr/bin/java','-cp','.', 'Caller'])]:
    request=dict(attempt=work.name,argv=argv,memoryBytes=536870912,tasksMax=64,cpuPercent=50,work=str(work))
    result=subprocess.run(['/usr/bin/python3',RUNNER],input=json.dumps(request).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    results[name]=dict(passed=result.returncode==0,exit=result.returncode,stderr=result.stderr[-800:].decode(errors='replace'))
results['compiled-output-bytes'] = dict(passed=(work/'Caller.class').is_file() and (work/'Generated.class').is_file())
if results['compiled-output-bytes']['passed']:
    results['compiled-output-bytes']['callerSha256']=hashlib.sha256((work/'Caller.class').read_bytes()).hexdigest()
print(json.dumps({'schema':'hyperv-candidate-conformance-subset:m8.4-v1','results':results,'qualified':False},sort_keys=True))
raise SystemExit(0 if all(r['passed'] for r in results.values()) else 1)
