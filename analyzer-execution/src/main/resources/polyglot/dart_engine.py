#!/usr/bin/env python3
"""Native Dart analyzer DTO to neutral captured tables; no application execution."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file,unique


def analyze(request,dart,packages,engine):
    out=Output(request,'native-dart-request:m8.4-v1','dart',('3.7',));r=out.request
    for path,expected in [(dart,r['runtime']),(packages,r['packages']),(engine,r['engine'])]:require_file(path,expected)
    work=Path(r['workRoot']);work.mkdir(exist_ok=True);projection=work/'dart-native.json'
    with projection.open('xb') as stdout,(work/'dart-native.stderr').open('xb') as stderr:
        result=subprocess.run([str(dart),'--disable-analytics','--packages='+str(packages),str(engine),str(request)],stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,timeout=180)
    if result.returncode:raise ValueError('DART_NATIVE_OPERATION_FAILED')
    raw=json.loads(projection.read_bytes(),object_pairs_hook=unique)
    if raw['schema']!='native-dart-projection:m8.4-v1' or raw['compiler']!='3.7.0':raise ValueError('DART_NATIVE_PROJECTION_TUPLE')
    for path in raw['traversed']:
        if path not in out.sources:raise ValueError('DART_NATIVE_FOREIGN_DOCUMENT')
        out.traversed.add(path)
    for path in raw['failed']:
        if path not in out.sources:raise ValueError('DART_NATIVE_FOREIGN_DOCUMENT')
        out.failed.add(path)
    for diagnostic in raw['diagnostics']:
        source=out.sources.get(diagnostic['document'])
        if source is None:raise ValueError('DART_NATIVE_FOREIGN_DIAGNOSTIC')
        out.diagnostic(source,diagnostic['severity'],diagnostic['code'],diagnostic['message'])
    keys=set()
    for site in raw['census']:
        if type(site['id']) is not int or site['id'] in keys:raise ValueError('DART_NATIVE_DUPLICATE_OBLIGATION')
        keys.add(site['id']);out.site(out.sources[site['document']],site['category'],site['role'],site['start'],site['end'],'UTF16',site['id'])
    if set(raw['facts'])-{str(key) for key in keys}:raise ValueError('DART_NATIVE_EMISSION_OUTSIDE_CENSUS')
    def symbol(value):
        source=out.sources.get(value['document']);span=source.span(value['start'],value['end'],'UTF16') if source and 0<=value['start']<value['end'] else None
        return out.symbol(value['owner'],value['signature'],value['name'],value['kind'],value['origin'],span,synthetic=value['synthetic'],locationPrecision=value.get('locationPrecision','DECLARATION_NAME') if span else 'ABSENT_NATIVE_LOCATION')
    def attribute(site):
        value=raw['facts'].get(str(site['native']))
        if value is None:return None
        caller=symbol(value['caller']) if value['caller'] else None;extra={}
        if value['truth']:extra['truth']=value['truth']
        if value['access']:extra['access']=value['access']
        if value.get('relation'):extra['relation']=value['relation']
        if value.get('owner'):extra['owner']=symbol(value['owner'])['id']
        return out.fact(value['status'],[symbol(s) for s in value['symbols']],type=value['type'],caller=caller['id'] if caller else None,reason=value['reason'],**extra)
    out.emit(attribute)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','dart','packages','engine'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.dart,args.packages,args.engine)
