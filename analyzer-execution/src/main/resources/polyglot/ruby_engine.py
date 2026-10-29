#!/usr/bin/env python3
"""Pinned Sorbet parser census + native LSP definitions, without Ruby evaluation."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file,unique
from lsp_client import LspClient
from native_lsp_projection import parameters,definitions

DECLARATIONS={'Class','Module','Def','DefS','Param','RestParam','Kwarg','Kwrestarg','Blockarg','LVarLhs','IVarLhs','CVarLhs','GVarLhs','ConstLhs'}
STATE={'LVar','IVar','CVar','GVar','LVarLhs','IVarLhs','CVarLhs','GVarLhs'}


def location(source,value):
    match=re.fullmatch(r'.+:(\d+):(\d+)-(\d+):(\d+)',value or '')
    if not match:return None
    a,b,c,d=map(int,match.groups());lines=source.raw.splitlines(keepends=True)
    if not 1<=a<=c<=len(lines) or not 1<=b<=len(lines[a-1])+1 or not 1<=d<=len(lines[c-1])+1:return None
    # Sorbet's pinned native loc wire uses one-based UTF-8 byte columns and an
    # exclusive end. The isolated non-BMP/CRLF label controls this contract.
    return sum(map(len,lines[:a-1]))+b-1,sum(map(len,lines[:c-1]))+d-1


def analyze(request,sorbet):
    out=Output(request,'native-ruby-request:m8.4-v1','ruby',('sorbet-0.6',));r=out.request;require_file(sorbet,r['analyzer'])
    version=subprocess.run([str(sorbet),'--version'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=10).stdout.decode('utf8')
    if '0.6.13594' not in version:raise ValueError('SORBET_NATIVE_VERSION_MISMATCH')
    work=Path(r['workRoot']);work.mkdir(exist_ok=True);declarations={path:[] for path in out.sources}
    for ordinal,source in enumerate(out.sources.values()):
        result=subprocess.run([str(sorbet),'--no-config','--stop-after=parser','--print=parse-tree-json-with-locs','--file',str(source.physical)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        (work/(str(ordinal)+'-parse.json')).write_bytes(result.stdout);(work/(str(ordinal)+'-parse.stderr')).write_bytes(result.stderr)
        if result.returncode not in (0,100):raise ValueError('SORBET_NATIVE_PARSE_OPERATION')
        if result.returncode:out.diagnostic(source,'ERROR','SORBET_NATIVE_PARSE','Native parser reported syntax errors',syntax=True)
        try:tree=json.loads(result.stdout,object_pairs_hook=unique)
        except (ValueError,UnicodeError):out.diagnostic(source,'ERROR','SORBET_NATIVE_PARSE_VIEW_UNAVAILABLE','Native parser returned no JSON syntax view',syntax=True);continue
        if not isinstance(tree,dict):out.diagnostic(source,'ERROR','SORBET_NATIVE_PARSE_VIEW_UNAVAILABLE','Native parser returned no root node',syntax=True);continue
        out.traversed.add(source.path)
        def walk(node,parents=(),owners=()):
            if not isinstance(node,dict) or 'type' not in node:return
            kind=node['type'];bounds=location(source,node.get('loc'));scope=r['packageScope']+'::'+'::'.join(owners)
            def site(category,role,target=node):
                span=location(source,target.get('loc'));out.site(source,category,role,*(span or (None,None)),native=(target,parents+(node,) if target is not node else parents,scope))
            name=node.get('name')
            if isinstance(name,dict):name=name.get('name')
            if kind in DECLARATIONS:
                site('A02','declaration')
                if isinstance(name,str) and bounds and bounds[0]<bounds[1]:declarations[source.path].append(dict(node=node,name=name,owner=scope,span=source.span(*bounds,'UTF8')))
            if kind in ('Class','Module'):site('A01','namespace')
            if kind in ('Const','LVar','IVar','CVar','GVar'):site('A03','reference')
            if kind in ('Def','DefS'):site('A04','signature')
            if kind=='Class':site('A05','heritage',node['superclass']) if isinstance(node.get('superclass'),dict) else site('A05','implicit-superclass')
            if kind=='Send':
                site('A06','call')
                if node.get('method') in ('sig','attr_reader','attr_writer','attr_accessor','include','extend'):site('A08','dsl-or-generated-member')
                if node.get('method') in ('require','require_relative','load','autoload'):site('A09','resource-boundary')
            if kind in STATE:site('A07','state-access')
            if kind in ('If','While','Until','Case','For'):site('A10','guard')
            next_owners=owners+((name+('.<singleton>' if kind=='DefS' else ''),) if kind in ('Class','Module','Def','DefS') and isinstance(name,str) else ())
            for value in node.values():
                if isinstance(value,dict):walk(value,parents+(node,),next_owners)
                elif isinstance(value,list):
                    for child in value:walk(child,parents+(node,),next_owners)
        walk(tree)
    def symbol(source,declaration):
        node=declaration['node'];kind=node['type'];signature=kind+':'+declaration['name']
        if kind not in ('Class','Module'):signature+='@'+str(declaration['span']['start'])
        return out.symbol(declaration['owner'],signature,declaration['name'],kind,'GENERATED' if source.role=='generated' else 'PROJECT',declaration['span'])
    with (work/'sorbet-lsp.stderr').open('xb') as errors,LspClient([str(sorbet),'--no-config','--lsp','--disable-watchman','--lsp-error-cap=0','--dir='+str(out.root)],out.root,errors) as client:
        initialized=client.request('initialize',dict(processId=None,rootUri=out.root.as_uri(),capabilities={'general':{'positionEncodings':['utf-16']}},initializationOptions={'supportsSorbetURIs':True},workspaceFolders=[dict(uri=out.root.as_uri(),name='captured')]))
        if initialized['capabilities'].get('positionEncoding','utf-16')!='utf-16':raise ValueError('SORBET_NATIVE_POSITION_ENCODING')
        client.notify('initialized',{})
        # No editor changes: Sorbet reads the exact immutable directory at startup.
        cache={}
        def query(source,node):
            bounds=location(source,node.get('methodLoc') if node['type']=='Send' else node.get('loc'))
            if bounds is None:return [],None
            key=(source.path,bounds[0])
            if key not in cache:
                # This pinned Sorbet version's Position::toLoc/detail2Pos uses
                # byte columns even though the old LSP handshake omits encoding.
                # Use its verified native wire, then map back to original UTF-16.
                params=parameters(source,bounds[0],position_encoding='UTF8');refs=client.request('textDocument/definition',params);hover=client.request('textDocument/hover',params);cache[key]=(definitions(out,refs,declarations,symbol,position_encoding='UTF8'),hover)
            return cache[key]
        def attribute(site):
            node,parents,scope=site['native'];source=site['source'];category=site['category'];kind=node['type']
            if category=='A10':return out.fact('RESOLVED',truth='UNKNOWN')
            if category=='A01':return out.fact('PARTIAL',reason='RUBY_REOPENED_NAMESPACE_AND_LOAD_ORDER_NOT_ESTABLISHED')
            if category in ('A08','A09'):return out.fact('UNSUPPORTED',reason={'A08':'RUBY_NATIVE_DSL_AND_GENERATED_LINEAGE_REQUIRED','A09':'RUBY_NATIVE_LOAD_AND_RESOURCE_CLOSURE_REQUIRED'}[category])
            if category=='A05':
                if site['role']=='implicit-superclass':return out.fact('UNSUPPORTED',reason='RUBY_NATIVE_IMPLICIT_SUPERCLASS_AND_REOPENING_REQUIRED')
                owner=[d for d in declarations[source.path] if parents and d['node'] is parents[-1] and d['node']['type']=='Class'];values,hover=query(source,node)
                if not values:return out.fact('UNRESOLVED',reason='RUBY_NATIVE_SUPERCLASS_TARGET_UNAVAILABLE',relation='DECLARED_SUPERCLASS',nativeHover=hover)
                if len(owner)!=1:return out.fact('PARTIAL',values,reason='RUBY_NATIVE_SUPERCLASS_OWNER_UNAVAILABLE',relation='DECLARED_SUPERCLASS',nativeHover=hover)
                return out.fact('CONDITIONAL',values,owner=symbol(source,owner[0])['id'],relation='DECLARED_SUPERCLASS',reason='RUBY_RUNTIME_REOPENING_AND_ANCESTOR_LOAD_ORDER_UNQUALIFIED',nativeHover=hover)
            if category=='A02':
                matches=[d for d in declarations[source.path] if d['node'] is node]
                return out.fact('RESOLVED',[symbol(source,matches[0])]) if len(matches)==1 else out.fact('UNSUPPORTED',reason='SORBET_NATIVE_DECLARATION_LOCATION_UNAVAILABLE')
            values,hover=query(source,node)
            if not values:return out.fact('UNRESOLVED',reason='SORBET_NATIVE_BINDING_OR_CAPTURED_DECLARATION_UNAVAILABLE',nativeHover=hover)
            status='CANDIDATES' if len(values)>1 else 'RESOLVED';reason=None;extra={}
            if category=='A06':
                if any(value['kind'] not in ('Def','DefS') for value in values):return out.fact('UNRESOLVED',reason='RUBY_INDIRECT_RUNTIME_CALL_NOT_ESTABLISHED',calleeSymbols=values,nativeHover=hover)
                if any(value['kind']=='Def' for value in values):status='CONDITIONAL';reason='RUBY_OPEN_CLASS_AND_VIRTUAL_DISPATCH_NOT_ESTABLISHED'
            if category=='A07':status='PARTIAL';reason='RUBY_NATIVE_STATE_BINDING_AND_ACCESS_MODE_SUPPLEMENT_REQUIRED'
            caller=None
            for parent in reversed(parents):
                matches=[d for d in declarations[source.path] if d['node'] is parent and parent['type'] in ('Def','DefS')]
                if matches:caller=symbol(source,matches[0])['id'];break
            return out.fact(status,values,caller=caller,reason=reason,nativeHover=hover,**extra)
        # Independent native CLI diagnostic pass, unaffected by LSP notification timing.
        command=[str(sorbet),'--no-config','--color=never','--no-error-count','--no-error-sections','--remove-path-prefix='+str(out.root)+'/',*[str(source.physical) for source in out.sources.values()]]
        with (work/'sorbet-check.stdout').open('xb') as stdout,(work/'sorbet-check.stderr').open('xb') as stderr:checked=subprocess.run(command,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,timeout=90)
        if checked.returncode not in (0,100):raise ValueError('SORBET_NATIVE_TYPECHECK_OPERATION')
        found=0
        for line in (work/'sorbet-check.stderr').read_text(encoding='utf8').splitlines():
            match=re.fullmatch(r'(.+?):(\d+): (.+) https://srb.help/(\d+)',line)
            if not match:continue
            path,number,message,code=match.groups();source=out.sources.get(path) or out.source_path(path)
            if source:out.diagnostic(source,'ERROR','SORBET_'+code,message);found+=1
        if checked.returncode and not found:
            for source in out.sources.values():out.diagnostic(source,'ERROR','SORBET_NATIVE_DIAGNOSTIC_SOURCE_UNMAPPED','Native checker failed; exact stderr retained in capture')
        out.emit(attribute)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','sorbet'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.sorbet)
