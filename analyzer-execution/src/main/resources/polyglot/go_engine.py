#!/usr/bin/env python3
"""Go native compiler DTO -> neutral tables, with a separate syntax denominator."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file,unique


def analyze(request,engine):
    out=Output(request,'native-go-request:m8.4-v1','go',('1.24',));r=out.request;require_file(engine,r['engine'])
    work=Path(r['workRoot']);work.mkdir(exist_ok=True);projection=work/'go-native.json'
    with projection.open('xb') as stdout,(work/'go-native.stderr').open('xb') as stderr:
        result=subprocess.run([str(engine),str(request)],stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,timeout=120)
    if result.returncode:raise ValueError('GO_NATIVE_OPERATION_FAILED')
    raw=json.loads(projection.read_bytes(),object_pairs_hook=unique)
    if raw['Schema']!='native-go-projection:m8.4-v1' or raw['Compiler']!='go1.24.0':raise ValueError('GO_NATIVE_PROJECTION_TUPLE')
    for path in raw['Traversed']:
        if path not in out.sources:raise ValueError('GO_NATIVE_FOREIGN_DOCUMENT')
        out.traversed.add(path)
    for path in raw['Failed']:
        if path not in out.sources:raise ValueError('GO_NATIVE_FOREIGN_DOCUMENT')
        out.failed.add(path)
    for diagnostic in raw['Diagnostics']:
        sources=[out.sources[diagnostic['Document']]] if diagnostic['Document'] in out.sources else out.sources.values()
        for source in sources:out.diagnostic(source,diagnostic['Severity'],diagnostic['Code'],diagnostic['Message'])
    native_ids=set()
    for site in raw['Census']:
        if type(site['Ordinal']) is not int or site['Ordinal'] in native_ids:raise ValueError('GO_NATIVE_DUPLICATE_OBLIGATION')
        native_ids.add(site['Ordinal']);out.site(out.sources[site['Document']],site['Category'],site['Role'],site['Start'],site['End'],native=site['Ordinal'])
    if set(raw['Facts'])-{str(value) for value in native_ids}:raise ValueError('GO_NATIVE_EMISSION_OUTSIDE_CENSUS')
    def symbol(value):
        source=out.sources.get(value['Document']);span=source.span(value['Start'],value['End'],'UTF8') if source and 0<=value['Start']<value['End'] else None
        return out.symbol(value['Owner'],value['Signature'],value['Name'],value['Kind'],value['Origin'],span,locationPrecision='DECLARATION_NAME' if span else 'ABSENT_UNCAPTURED_DECLARATION')
    def attribute(site):
        value=raw['Facts'].get(str(site['native']))
        if value is None:return None
        extra={}
        if value.get('Callee'):extra['calleeSymbol']=symbol(value['Callee'])
        if value['Access']:extra['access']=value['Access']
        if value['Dispatch']:extra['dispatch']=value['Dispatch']
        if site['category']=='A10' and value['Status']=='RESOLVED':extra['truth']='TRUE_IN_CAPTURED_BUILD_CONTEXT' if value['Reason']=='GO_NATIVE_SELECTED_BUILD_PREDICATE' else 'UNKNOWN'
        caller=symbol(value['Caller'])['id'] if value.get('Caller') else None
        if value.get('CallerStatus'):extra['callerStatus']=value['CallerStatus']
        if value.get('CallerReason'):extra['callerReason']=value['CallerReason']
        fact=out.fact(value['Status'],[symbol(s) for s in value['Symbols']],caller=caller,type=value['Type'] or None,reason=value['Reason'] or None,**extra)
        if site['category']=='A06' and site['role'] in ('call','construct') and caller is None and fact['status']=='RESOLVED':fact.update(targetStatus='RESOLVED',status='PARTIAL',reason='GO_NATIVE_INITIALIZER_OR_ANONYMOUS_CALLER_REQUIRED',uncertainty=['GO_NATIVE_INITIALIZER_OR_ANONYMOUS_CALLER_REQUIRED'])
        return fact
    out.emit(attribute)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--request',type=Path,required=True);parser.add_argument('--engine',type=Path,required=True);args=parser.parse_args();analyze(args.request,args.engine)
