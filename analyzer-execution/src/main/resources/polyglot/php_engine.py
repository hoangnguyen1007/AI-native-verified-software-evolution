#!/usr/bin/env python3
"""Pinned PHPStan native scope/reflection DTO to neutral semantic capture."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file,unique


def analyze(request,php,analyzer,engine):
    out=Output(request,'native-php-request:m8.4-v1','php',('8.1',));r=out.request
    for path,expected in [(php,r['runtime']),(analyzer,r['analyzer']),(engine,r['engine'])]:require_file(path,expected)
    work=Path(r['workRoot']);work.mkdir(exist_ok=True);projection=work/'php-native.json'
    with projection.open('xb') as stdout,(work/'php-native.stderr').open('xb') as stderr:
        result=subprocess.run([str(php),'-n',str(engine),str(request),str(analyzer)],stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,timeout=180)
    if result.returncode:raise ValueError('PHP_NATIVE_OPERATION_FAILED')
    raw=json.loads(projection.read_bytes(),object_pairs_hook=unique)
    if raw['schema']!='native-php-projection:m8.4-v1' or not raw['compiler'].startswith('8.1.'):raise ValueError('PHP_NATIVE_PROJECTION_TUPLE')
    for path in raw['traversed']:
        if path not in out.sources:raise ValueError('PHP_NATIVE_FOREIGN_DOCUMENT')
        out.traversed.add(path)
    for path in raw['failed']:
        if path not in out.sources:raise ValueError('PHP_NATIVE_FOREIGN_DOCUMENT')
        out.failed.add(path)
    for diagnostic in raw['diagnostics']:out.diagnostic(out.sources[diagnostic['document']],diagnostic['severity'],diagnostic['code'],diagnostic['message'])
    keys=set()
    for site in raw['census']:
        if type(site['id']) is not int or site['id'] in keys:raise ValueError('PHP_NATIVE_DUPLICATE_OBLIGATION')
        keys.add(site['id']);out.site(out.sources[site['document']],site['category'],site['role'],site['start'],site['end'],'UTF8',site['id'])
    if set(raw['facts'])-{str(key) for key in keys}:raise ValueError('PHP_NATIVE_EMISSION_OUTSIDE_CENSUS')
    def symbol(value):
        source=out.sources.get(value['document']);span=source.span(value['start'],value['end'],'UTF8') if source and 0<=value['start']<value['end'] else None
        lineage=value.get('lineage');extra={}
        if lineage:
            origin=out.sources.get(lineage['document'])
            if origin is None:raise ValueError('PHP_GENERATION_ORIGIN_UNCAPTURED')
            extra['generationLineage']=dict(schema='semantic-generation-lineage:m8.4-v1',mechanism=lineage['mechanism'],source=dict(digest=origin.digest,size=len(origin.raw)),sourceSpan=origin.span(lineage['start'],lineage['end'],'UTF8'))
        return out.symbol(value['owner'],value['signature'],value['name'],value['kind'],value['origin'],span,ambiguous=value.get('ambiguous',False),locationPrecision='PROMOTION_PARAMETER_ORIGIN' if lineage else 'FULL_NATIVE_DECLARATION' if span else 'ABSENT_UNCAPTURED_DECLARATION',**extra)
    def attribute(site):
        value=raw['facts'].get(str(site['native']))
        if value is None:return None
        caller=symbol(value['caller']) if value['caller'] else None;extra={}
        if value.get('truth'):extra['truth']=value['truth']
        if value.get('access'):extra['access']=value['access']
        if value.get('types'):extra['types']=value['types']
        if value.get('relation'):extra['relation']=value['relation']
        if value.get('owner'):extra['owner']=symbol(value['owner'])['id']
        return out.fact(value['status'],[symbol(s) for s in value['symbols']],type=value['type'],caller=caller['id'] if caller else None,reason=value['reason'],**extra)
    out.emit(attribute)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','php','analyzer','engine'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.php,args.analyzer,args.engine)
