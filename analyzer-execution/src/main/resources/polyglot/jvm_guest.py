#!/usr/bin/python3
"""Registered native JVM adapter recipe. Only fixed platform entry points accept captured data.

No target compiler plugins or application main methods are loaded. Groovy transforms stay
inside containment; external acquisition remains disabled by the worker network policy.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

from guest_semantic_runner import digest,require_file,unique

PROFILES={
    'kotlin':('1.9.25','KotlinEngine',('kotlin-compiler-embeddable-1.9.25.jar','kotlin-stdlib-1.9.25.jar','kotlin-script-runtime-1.9.25.jar','kotlin-reflect-1.6.10.jar','kotlin-daemon-embeddable-1.9.25.jar','trove4j-1.0.20200330.jar','annotations-24.1.0.jar'),('kotlin-stdlib-1.9.25.jar',)),
    'scala':('2.13.16','ScalaEngine',('scala-compiler-2.13.16.jar','scala-library-2.13.16.jar','scala-reflect-2.13.16.jar','java-diff-utils-4.15.jar','jline-3.27.1.jar'),('scala-library-2.13.16.jar',)),
    'groovy':('4.0.27','GroovyEngine',('groovy-4.0.27.jar',),('groovy-4.0.27.jar',)),
}


def validate(campaign):
    if (set(campaign)!={'schema','contexts','platform','adapters'} or campaign['schema']!='registered-jvm-campaign:m8.4-v1'
            or not isinstance(campaign['contexts'],dict) or not campaign['contexts']):raise ValueError('JVM_CAMPAIGN_SCHEMA')
    for name,value in campaign['contexts'].items():
        if not re.fullmatch('[a-z][a-z0-9-]{0,63}',name) or set(value)!={'language','request'} or value['language'] not in PROFILES:raise ValueError('JVM_CONTEXT_NOT_REGISTERED')
        if set(value['request'])!={'path','size','digest'}:raise ValueError('JVM_INPUT_HANDLE_REQUIRED')


def command(registration,campaign,language,request):
    _,engine,jars,_=PROFILES[language];sdk=registration['sdkRoot']+'/jvm/'
    return [registration['runtime']['java']['path'],'-Xmx1024m','-Dfile.encoding=UTF-8','-cp',':'.join([campaign['platform']['path'],campaign['adapters']['path'],*[sdk+name for name in jars]]),'evo.polyglot.'+engine,str(request)]


def run(registration,base):
    import pwd
    campaign=registration['campaign'];validate(campaign)
    root=Path(registration['work']);control=Path(base);user=pwd.getpwnam('evobuild')
    for name in ('platform','adapters'):require_file(campaign[name]['path'],campaign[name])
    spec=importlib.util.spec_from_file_location('jvm_cgroup',control/'base-runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
    results={}
    for name,context in sorted(campaign['contexts'].items()):
        request=require_file(context['request']['path'],context['request'])
        value=json.loads(request.read_bytes(),object_pairs_hook=unique)
        version,_,_,platform=PROFILES[context['language']]
        expected=[registration['sdkRoot']+'/jvm/'+jar for jar in platform]
        if (value['language']!=context['language'] or value['dialect']!=version or value['classpath']!=expected or value['platformArtifacts']!=expected
                or not str(Path(value['sourceRoot'])).startswith(str(control)+'/inputs/'+name+'/')):raise ValueError('JVM_CONTEXT_BINDING_CHANGED')
        output=root/'native'/name;output.mkdir(parents=True,mode=0o755)
        home=output/'home';home.mkdir(mode=0o700);os.chown(home,user.pw_uid,user.pw_gid)
        native=dict(attempt=registration['attempt']+'-'+name,work=str(output),memoryBytes=2147483648,tasksMax=256,cpuPercent=100,
                    argv=command(registration,campaign,context['language'],request))
        argv=[part.replace('RuntimeMaxSec=3600','RuntimeMaxSec=120') for part in runner.build_command(native,output)]
        stdout=output/'capture.ndjson';stderr=output/'stderr'
        with stdout.open('xb') as out,stderr.open('xb') as err:
            try:result=subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=out,stderr=err,check=False,timeout=150);exit_code=result.returncode
            except subprocess.TimeoutExpired:exit_code=124
        require_file(request,context['request'])
        results[name]=dict(exit=exit_code,output=dict(size=stdout.stat().st_size,digest=digest(stdout)),stderr=dict(size=stderr.stat().st_size,digest=digest(stderr)))
    for name in ('platform','adapters'):require_file(campaign[name]['path'],campaign[name])
    return dict(schema='registered-jvm-campaign-result:m8.4-v1',results=results,qualified=False,trust='PRODUCER_ASSERTION')
