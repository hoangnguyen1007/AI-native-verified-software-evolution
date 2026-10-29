#!/usr/bin/env python3
"""Derive a new Swift projection from immutable native metadata; never run tools.

The original guest census and its earlier neutral results remain untouched. This
producer assertion is not a native rerun or semantic-method qualification.
"""
import argparse
import base64
import contextlib
import json
from pathlib import Path
import re
import types
import uuid

from guest_semantic_runner import require_file,unique
from native_protocol import canonical,identity,logical,order,sha
from swift_engine import analyze


def handle(raw):return dict(digest=sha(raw),size=len(raw))
def read(path):return json.loads(path.read_bytes(),object_pairs_hook=unique)
def saved(directory,value):
    if not isinstance(value,dict) or set(value)!={'digest','size'} or not re.fullmatch('sha256:[a-f0-9]{64}',value['digest']) or type(value['size']) is not int or value['size']<0:raise ValueError('CAPTURED_RECORD_HANDLE')
    return require_file(directory/value['digest'][7:],value).read_bytes()
def write(path,value):
    raw=canonical(value).encode('utf8')
    with path.open('xb') as output:output.write(raw)
    return handle(raw)


def reproject(root,destination,collector_pin,selected=None):
    root=root.resolve(strict=True);destination=destination.absolute()
    if destination.exists() or destination.is_symlink():raise ValueError('PROJECTION_DESTINATION_MUST_BE_NEW')
    # Inputs cannot be inside the new output, and the output cannot touch the
    # frozen worker directory. No caller-provided native path is host-opened.
    if root==destination or root in destination.parents or destination in root.parents:raise ValueError('PROJECTION_INPUT_OUTPUT_OVERLAP')
    capture=root/'capture';collector_file=capture/'collector-result.json';require_file(collector_file,collector_pin);collector=read(collector_file)
    if collector['schema']!='guest-output-census:m8.4-v2' or collector['inventoryClosed'] is not True or collector['captureClosed'] is not True or collector['pending']!=0 or collector['rejected']!=0:raise ValueError('CLOSED_NATIVE_CAPTURE_REQUIRED')
    manifest=(capture/'entries.ndjson').read_bytes();scope=(capture/'scope.json').read_bytes()
    if handle(manifest)!=collector['manifest'] or handle(scope)!=collector['scope']:raise ValueError('NATIVE_CAPTURE_CONTAINER_CHANGED')
    rows={};previous=None;logical_bytes=captured_bytes=0
    for raw in manifest.splitlines():
        row=json.loads(raw,object_pairs_hook=unique)
        if raw!=canonical(row).encode('utf8'):raise ValueError('NATIVE_CAPTURE_ROW_CANONICAL')
        key=base64.b64decode(row['path'],validate=True)
        if base64.b64encode(key).decode()!=row['path'] or previous is not None and key<=previous:raise ValueError('NATIVE_CAPTURE_PATH_ORDER')
        previous=key;path=logical(key.decode('utf8'))
        if row['disposition']!='PRESENT':raise ValueError('NATIVE_CAPTURE_ROW_INCOMPLETE')
        if row['kind'] in ('FILE','HARDLINK'):
            logical_bytes+=row['size'];captured_bytes+=row['size']
            if row['content']['size']!=row['size']:raise ValueError('NATIVE_CAPTURE_ROW_SIZE')
            saved(capture/'objects',row['content']);rows[path]=row['content']
    if len(manifest.splitlines())!=collector['entries'] or logical_bytes!=collector['logicalBytes'] or captured_bytes!=collector['capturedBytes']:raise ValueError('NATIVE_CAPTURE_COUNTERS')
    export=read(root/'export-request.json')['request'];attempt=export['attempt'];owner=read(root/('owner-'+attempt+'.json'));off=read(root/'termination-result.json')
    if off['state']!='Off' or any(off[key]!=owner[key] for key in ('attempt','vmId','fence','launch')) or uuid.UUID(attempt)!=uuid.UUID(off['attempt']):raise ValueError('NATIVE_CAPTURE_OWNER_TERMINATION')
    profile=read(root/'profile.json');campaign_file=root/'campaign.json';require_file(campaign_file,profile['campaign']);campaign=read(campaign_file)
    for path,digest in profile['controls'].items():
        control=root/'control-sources'/logical(path)
        if sha(control.read_bytes())!=digest:raise ValueError('NATIVE_CAPTURE_CONTROL_CHANGED')
    results=read(root/'native-campaign-result.json')['results'];contexts=campaign['contexts']
    if set(results)!=set(contexts):raise ValueError('NATIVE_CAPTURE_CONTEXT_DENOMINATOR')
    excluded=[]
    if selected is not None:
        if not selected or not set(selected)<=set(contexts):raise ValueError('SWIFT_PROJECTION_CONTEXT_SELECTION')
        excluded=sorted(set(contexts)-set(selected),key=order);contexts={name:context for name,context in contexts.items() if name in selected}
    swift_probe=rows.get('probes/swift/stdout')
    if swift_probe is None:raise ValueError('CAPTURED_SWIFT_VERSION_REQUIRED')
    sources={name:handle((Path(__file__).parent/name).read_bytes()) for name in ('reproject_swift_capture.py','swift_engine.py','swift_indexstore.py','native_protocol.py','guest_semantic_runner.py')}
    original_tools={context['request']['tool'] for context in contexts.values()}
    if len(original_tools)!=1:raise ValueError('SWIFT_CAMPAIGN_TOOL_SCOPE')
    original_tool=next(iter(original_tools));tool=identity(['native-swift-metadata-projection:m8.4-v2',original_tool,sources])
    destination.mkdir(parents=False);projected={}
    for name,context in sorted(contexts.items(),key=lambda item:order(item[0])):
        logical(name);blueprint=context['request'];result=results[name]
        if blueprint['language']!='swift' or result['exit']!=0:raise ValueError('COMPLETED_SWIFT_ADAPTER_REQUIRED')
        prefix='native/'+name+'/'
        def native(path):
            value=rows.get(prefix+path)
            if value is None:raise ValueError('NATIVE_METADATA_OUTPUT_MISSING:'+path)
            return value
        prepared_handle=native('prepared-request.json');prepared=json.loads(saved(capture/'objects',prepared_handle),object_pairs_hook=unique)
        if any(prepared.get(key)!=value for key,value in blueprint.items()):raise ValueError('NATIVE_REQUEST_BLUEPRINT_CHANGED')
        original_output={key:result['output'][key] for key in ('digest','size')}
        if native('capture.ndjson')!=original_output:raise ValueError('NATIVE_EXPORT_RESULT_CHANGED')
        case=destination/name;case.mkdir();objects=case/'objects';objects.mkdir()
        syntax={path:native('state/'+str(index)+'-syntax.json') for index,path in enumerate(sorted((item['path'] for item in prepared['files']),key=order))}
        records=dict(schema='captured-swift-native-records:m8.4-v1',scope=prepared['scope'],originalTool=original_tool,documents={item['path']:item['sha256'] for item in prepared['files']},syntax=syntax,ast=native('state/swift-ast.stdout'),stderr=native('state/swift-ast.stderr'),version=swift_probe,adapterExit=result['exit'],audit=dict(collector=collector_pin,manifest=collector['manifest'],preparedRequest=prepared_handle,originalProjection=original_output))
        if prefix+'state/swift-index.json' in rows:records.update(schema='captured-swift-native-records:m8.4-v2',index=native('state/swift-index.json'))
        for value in [*syntax.values(),records['ast'],records['stderr'],records['version'],*([records['index']] if 'index' in records else [])]:
            target=objects/value['digest'][7:]
            if not target.exists():target.write_bytes(saved(capture/'objects',value))
        records_handle=write(case/'records.json',records)
        request=dict(prepared,tool=tool,provider=dict(id='native.swift.captured',version='6.0.3-projection.5'),nativeOriginTool=original_tool,nativeRecords=records_handle,workRoot=str(case/'state'))
        request_handle=write(case/'request.json',request)
        with (case/'capture.ndjson').open('xb') as stream,contextlib.redirect_stdout(types.SimpleNamespace(buffer=stream)):
            analyze(case/'request.json',recorded=case)
        # Native input closure excludes audit requests/attempt paths. Source
        # locators are content-scoped in the registered native recipe.
        effective={key:value for key,value in prepared.items() if key not in ('sourceRoot','workRoot','files')}
        resolution=write(case/'resolution.json',dict(schema='captured-native-reprojection-inputs:m8.4-v1',effective=effective,native=dict(syntax=syntax,ast=records['ast'],stderr=records['stderr'],version=swift_probe,**({'index':records['index']} if 'index' in records else {})),sdk=profile['sdk']['manifest'],runtime=read(root/'guest-registration.json')['runtime'],mapperTool=tool))
        projected[name]=dict(request=request_handle,records=records_handle,output=handle((case/'capture.ndjson').read_bytes()),resolution=resolution,originalOutput=result['output'])
    report=dict(schema='captured-native-reprojection:m8.4-v1',mode='CAPTURED_NATIVE_METADATA_REPROJECTION',sourceCollector=collector_pin,sourceManifest=collector['manifest'],sourceScope=collector['scope'],mapperTool=tool,mapperSources=sources,contexts=projected,excludedContexts=excluded,trust='PRODUCER_ASSERTION',qualified=False)
    write(destination/'projection.json',report)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--capture-root',type=Path,required=True);parser.add_argument('--destination',type=Path,required=True);parser.add_argument('--collector-digest',required=True);parser.add_argument('--collector-size',type=int,required=True);parser.add_argument('--contexts');args=parser.parse_args()
    report=reproject(args.capture_root,args.destination,dict(digest=args.collector_digest,size=args.collector_size),None if args.contexts is None else set(args.contexts.split(',')))
    print(canonical(dict(contexts=len(report['contexts']),mapperTool=report['mapperTool'],qualified=False)))
