#!/usr/bin/env python3
"""rust-analyzer native CST census and native definition/type projection.

The captured project model is supplied explicitly. Cargo discovery, build scripts,
proc-macro execution and flycheck are disabled; no application lifecycle runs here.
Native expansion/build evidence requires a separately captured provider obligation.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file
from lsp_client import LspClient
from native_lsp_projection import parameters,definitions

DECLARATIONS={'FN','STRUCT','ENUM','VARIANT','TRAIT','TYPE_ALIAS','MODULE','CONST','STATIC','RECORD_FIELD','TYPE_PARAM','LIFETIME_PARAM','IDENT_PAT'}


def parse_cst(text,length):
    """Decode the pinned SDK's indented debug wire, not Rust source grammar."""
    roots=[];stack=[]
    for line in text.splitlines():
        if not line.strip():continue
        match=re.fullmatch(r'( *)([A-Z][A-Z_0-9]*)@(\d+)\.\.(\d+)(?: (.*))?',line)
        if not match:raise ValueError('RUST_NATIVE_CST_WIRE_UNSUPPORTED')
        indent,kind,start,end,token=match.groups();start=int(start);end=int(end)
        if not 0<=start<=end<=length or len(indent)%2:raise ValueError('RUST_NATIVE_CST_RANGE')
        while stack and stack[-1][0]>=len(indent):stack.pop()
        if stack and not stack[-1][1]['start']<=start<=end<=stack[-1][1]['end']:raise ValueError('RUST_NATIVE_CST_NESTING')
        node=dict(kind=kind,start=start,end=end,token=token,children=[],parent=stack[-1][1] if stack else None)
        (stack[-1][1]['children'] if stack else roots).append(node);stack.append((len(indent),node))
    if len(roots)!=1 or roots[0]['kind']!='SOURCE_FILE' or roots[0]['start']!=0 or roots[0]['end']!=length:raise ValueError('RUST_NATIVE_CST_DOCUMENT_MEMBERSHIP')
    return roots[0]


def descend(node):
    yield node
    for child in node['children']:yield from descend(child)


def analyze(request,analyzer,compiler=None):
    output=Output(request,'native-rust-request:m8.4-v1','rust',('2021',));r=output.request;require_file(analyzer,r['analyzer'])
    version=subprocess.run([str(analyzer),'--version'],stdout=subprocess.PIPE,check=True,timeout=10).stdout.decode('utf8').strip()
    if not version.startswith('rust-analyzer 0.3.2319-standalone'):raise ValueError('RUST_ANALYZER_VERSION_MISMATCH')
    if set(r['crate'])!={'root','name','cfg','target'} or r['crate']['root'] not in output.sources or not re.fullmatch('[A-Za-z_][A-Za-z_0-9]*',r['crate']['name']):raise ValueError('RUST_CAPTURED_CRATE_REQUIRED')
    if not isinstance(r['crate']['cfg'],list) or any(not isinstance(value,str) for value in r['crate']['cfg']):raise ValueError('RUST_CAPTURED_CFG_REQUIRED')
    work=Path(r['workRoot']);work.mkdir(exist_ok=True);trees={};declarations={path:[] for path in output.sources}
    for source in output.sources.values():
        result=subprocess.run([str(analyzer),'parse'],input=source.raw,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        (work/(str(len(trees))+'-cst.stdout')).write_bytes(result.stdout);(work/(str(len(trees))+'-cst.stderr')).write_bytes(result.stderr)
        if result.returncode:output.diagnostic(source,'ERROR','RUST_NATIVE_PARSE_OPERATION','Native CST operation failed',syntax=True);continue
        tree=parse_cst(result.stdout.decode('utf8'),len(source.raw));trees[source.path]=tree;output.traversed.add(source.path)
        for node in descend(tree):
            kind=node['kind']
            if node['token'] is not None:continue
            if kind=='ERROR':output.diagnostic(source,'ERROR','RUST_NATIVE_PARSE','Native parser retained an error node',syntax=True)
            def site(category,role):output.site(source,category,role,node['start'],node['end'],native=node)
            if kind in DECLARATIONS:
                names=[child for child in node['children'] if child['kind']=='NAME']
                if len(names)==1:
                    name=source.raw[names[0]['start']:names[0]['end']].decode('utf8');parents=[];parent=node['parent']
                    while parent:
                        if parent['kind'] in DECLARATIONS|{'IMPL'}:
                            parent_names=[child for child in parent['children'] if child['kind']=='NAME']
                            parents.append(source.raw[parent_names[0]['start']:parent_names[0]['end']].decode('utf8') if len(parent_names)==1 else parent['kind']+'@'+str(parent['start']))
                        parent=parent['parent']
                    declarations[source.path].append(dict(node=node,name=name,owner=r['crate']['name']+'::'+source.path+'::'+'::'.join(reversed(parents)),span=source.span(node['start'],node['end'],'UTF8')))
                site('A02','declaration')
            if kind in ('NAME_REF','USE'):site('A03','reference')
            if kind.endswith('_TYPE') or kind in ('RET_TYPE','TYPE_BOUND_LIST'):site('A04','type-use')
            if kind=='IMPL':site('A05','implementation')
            if kind=='TYPE_BOUND':site('A05','type-bound')
            if kind in ('CALL_EXPR','METHOD_CALL_EXPR'):site('A06','call')
            if kind in ('RECORD_EXPR','TUPLE_STRUCT_PAT'):site('A06','construct')
            if kind in ('PATH_EXPR','FIELD_EXPR'):site('A07','value-access');site('A06','callable-reference')
            if kind in ('ATTR','MACRO_CALL','MACRO_RULES'):site('A08','attribute-or-macro')
            if kind in ('USE','EXTERN_CRATE','EXTERN_BLOCK','MODULE'):site('A09','import-or-abi-boundary')
            if kind in ('IF_EXPR','WHILE_EXPR','MATCH_GUARD','CFG'):site('A10','guard')
    def symbol(source,declaration):
        node=declaration['node'];kind=node['kind'];signature=kind+':'+declaration['name']
        # Position disambiguates shadowed bindings and local/anonymous owners. Native
        # type/overload information stays an observation, not a guessed signature.
        if kind in ('IDENT_PAT','TYPE_PARAM','LIFETIME_PARAM','RECORD_FIELD'):signature+='@'+str(node['start'])
        return output.symbol(declaration['owner'],signature,declaration['name'],kind,'GENERATED' if source.role=='generated' else 'PROJECT',declaration['span'])
    crate=dict(root_module=str(output.sources[r['crate']['root']].physical),edition=r['dialect'],deps=[],cfg=r['crate']['cfg'],env={},display_name=r['crate']['name'],is_workspace_member=True)
    if r['crate']['target']:crate['target']=r['crate']['target']
    project=dict(crates=[crate]);config=dict(linkedProjects=[project],cargo={'buildScripts':{'enable':False}},procMacro={'enable':False},checkOnSave=False,diagnostics={'enable':True})
    with (work/'rust-analyzer.stderr').open('xb') as errors,LspClient([str(analyzer)],output.root,errors,{'rust-analyzer':config}) as client:
        initialized=client.request('initialize',dict(processId=None,rootUri=output.root.as_uri(),capabilities={'general':{'positionEncodings':['utf-16']},'workspace':{'configuration':True},'experimental':{'serverStatusNotification':True}},initializationOptions=config,workspaceFolders=[dict(uri=output.root.as_uri(),name='captured')]))
        if initialized['capabilities'].get('positionEncoding','utf-16')!='utf-16':raise ValueError('RUST_NATIVE_POSITION_ENCODING')
        client.notify('initialized',{})
        readiness=client.wait_notification('experimental/serverStatus',lambda value:value.get('quiescent') is True)
        if readiness.get('health')!='ok':
            for source in output.sources.values():output.diagnostic(source,'ERROR','RUST_NATIVE_PROJECT_READINESS',readiness.get('message') or 'Native workspace did not become healthy',syntax=True)
        for source in output.sources.values():client.notify('textDocument/didOpen',dict(textDocument=dict(uri=source.physical.as_uri(),languageId='rust',version=1,text=source.text)))
        for source in output.sources.values():
            report=client.request('textDocument/diagnostic',dict(textDocument={'uri':source.physical.as_uri()}))
            if report.get('kind')!='full':raise ValueError('RUST_NATIVE_FULL_DIAGNOSTIC_VIEW_REQUIRED')
            for item in report['items']:
                code=str(item.get('code','RUST_ANALYZER_NATIVE'))
                output.diagnostic(source,'ERROR' if item.get('severity')==1 else 'WARNING',code,item['message'],syntax=code=='syntax-error')
        cache={}
        def query(source,node):
            probe=node
            if node['kind']=='METHOD_CALL_EXPR':
                names=[child for child in node['children'] if child['kind']=='NAME_REF']
                if len(names)==1:probe=names[0]
            elif node['kind'] in ('CALL_EXPR','RECORD_EXPR'):
                children=[child for child in node['children'] if child['token'] is None]
                if children:probe=children[0]
            if probe['kind'] in ('PATH_EXPR','PATH_TYPE'):
                paths=[child for child in probe['children'] if child['kind']=='PATH']
                if len(paths)==1:probe=paths[0]
            if probe['kind']=='PATH':
                segments=[child for child in probe['children'] if child['kind']=='PATH_SEGMENT']
                names=[child for segment in segments for child in segment['children'] if child['kind']=='NAME_REF']
                if names:probe=names[-1]
            elif probe['kind']!='NAME_REF':
                refs=[child for child in descend(probe) if child['kind']=='NAME_REF']
                if refs:probe=refs[-1]
            key=(source.path,probe['start'])
            if key not in cache:
                params=parameters(source,probe['start']);locations=client.request('textDocument/definition',params);hover=client.request('textDocument/hover',params)
                cache[key]=(definitions(output,locations,declarations,symbol),hover)
            return cache[key]
        def attribute(site):
            source=site['source'];node=site['native'];category=site['category'];role=site['role'];kind=node['kind']
            if category=='A10':return output.fact('RESOLVED',truth='UNKNOWN')
            if category in ('A08','A09'):return output.fact('UNSUPPORTED',reason={'A08':'RUST_CAPTURED_MACRO_EXPANSION_AND_GENERATION_REQUIRED','A09':'RUST_NATIVE_MODULE_INSTANCE_AND_ABI_SUPPLEMENT_REQUIRED'}[category])
            if category=='A05':
                if kind=='IMPL':
                    separators=[child for child in node['children'] if child['kind']=='FOR_KW']
                    if not separators:return output.fact('NOT_APPLICABLE',reason='NATIVE_INHERENT_IMPL_HAS_NO_TRAIT_RELATION')
                    separator=separators[0];trait_types=[child for child in node['children'] if child['kind'].endswith('_TYPE') and child['end']<=separator['start']];self_types=[child for child in node['children'] if child['kind'].endswith('_TYPE') and child['start']>=separator['end']]
                    if len(trait_types)!=1 or len(self_types)!=1 or self_types[0]['kind']!='PATH_TYPE':return output.fact('UNSUPPORTED',reason='RUST_NATIVE_COMPOSITE_SELF_OR_TRAIT_TYPE_OWNER_REQUIRED')
                    owners,owner_hover=query(source,self_types[0]);values,hover=query(source,trait_types[0]);relation='NEGATIVE_TRAIT_IMPLEMENTATION' if any(child['kind']=='BANG' for child in node['children']) else 'IMPLEMENTS_TRAIT'
                else:
                    paths=[child for child in node['children'] if child['kind']=='PATH_TYPE']
                    if not paths and any(child['kind']=='LIFETIME' for child in node['children']):return output.fact('NOT_APPLICABLE',reason='NATIVE_LIFETIME_BOUND_HAS_NO_TYPE_HERITAGE')
                    if len(paths)!=1:return output.fact('UNSUPPORTED',reason='RUST_NATIVE_BOUND_TYPE_FORM_UNQUALIFIED')
                    parent=node['parent'];owners=[];owner_hover=None
                    while parent:
                        matches=[d for d in declarations[source.path] if d['node'] is parent]
                        if len(matches)==1:owners=[symbol(source,matches[0])];break
                        parent=parent['parent']
                    values,hover=query(source,paths[0]);relation='SUPERTRAIT' if parent and parent['kind']=='TRAIT' else 'RELAXED_TYPE_BOUND' if any(child['kind']=='QUESTION' for child in node['children']) else 'TYPE_BOUND'
                known_owner=owners[0]['id'] if len(owners)==1 else None
                if not values:return output.fact('UNRESOLVED',reason='RUST_NATIVE_TRAIT_OR_BOUND_TARGET_UNAVAILABLE',owner=known_owner,relation=relation,nativeHover=hover)
                if any(value['kind']!='TRAIT' for value in values):return output.fact('PARTIAL',values,reason='RUST_NATIVE_BINDING_IS_NOT_A_PROVEN_TRAIT',owner=known_owner,relation=relation,nativeHover=hover)
                if len(owners)!=1:return output.fact('PARTIAL',values,reason='RUST_NATIVE_RELATION_OWNER_UNAVAILABLE_OR_AMBIGUOUS',relation=relation,ownerCandidates=owners,nativeHover=hover,nativeOwnerHover=owner_hover)
                return output.fact('CANDIDATES' if len(values)>1 else 'RESOLVED',values,owner=owners[0]['id'],relation=relation,nativeHover=hover,nativeOwnerHover=owner_hover)
            if category=='A02':
                matches=[d for d in declarations[source.path] if d['node'] is node]
                return output.fact('RESOLVED',[symbol(source,matches[0])]) if len(matches)==1 else output.fact('UNSUPPORTED',reason='RUST_NATIVE_DECLARATION_NAME_UNAVAILABLE')
            values,hover=query(source,node)
            if role=='callable-reference':
                if node['parent'] and node['parent']['kind']=='CALL_EXPR' and node['parent']['children'][0] is node:return output.fact('NOT_APPLICABLE',reason='REFERENCE_IS_INVOCATION_CALLEE')
                if values and all(value['kind']!='FN' for value in values):return output.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_CALLABLE_DECLARATION')
            if category=='A07' and values and all(value['kind'] in ('FN','STRUCT','ENUM','TRAIT','MODULE','TYPE_ALIAS') for value in values):return output.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_STATE')
            if not values:return output.fact('UNRESOLVED',reason='RUST_NATIVE_BINDING_OR_CAPTURED_DECLARATION_UNAVAILABLE',nativeHover=hover)
            if role=='call' and any(value['kind']!='FN' for value in values):return output.fact('UNRESOLVED',reason='RUST_INDIRECT_RUNTIME_CALL_NOT_ESTABLISHED',calleeSymbols=values,nativeHover=hover)
            status='CANDIDATES' if len(values)>1 else 'RESOLVED';reason=None
            if role=='call' and kind=='METHOD_CALL_EXPR':status='CONDITIONAL';reason='RUST_TRAIT_OR_RECEIVER_DISPATCH_REQUIRES_NATIVE_PROOF'
            extra={}
            if category=='A07':status='PARTIAL';reason='RUST_NATIVE_STATE_ACCESS_MODE_SUPPLEMENT_REQUIRED'
            return output.fact(status,values,reason=reason,nativeHover=hover,**extra)
        if compiler:
            require_file(compiler,r['compiler']);command=[str(compiler),'--crate-type=lib','--crate-name',r['crate']['name'],'--edition='+r['dialect'],'--emit=metadata','--error-format=json','--out-dir',str(work),str(output.sources[r['crate']['root']].physical)]
            for cfg in r['crate']['cfg']:command.extend(['--cfg',cfg])
            if r['crate']['target']:command.extend(['--target',r['crate']['target']])
            with (work/'rustc.stdout').open('xb') as stdout,(work/'rustc.stderr').open('xb') as stderr:result=subprocess.run(command,stdout=stdout,stderr=stderr,stdin=subprocess.DEVNULL,timeout=90)
            if result.returncode not in (0,1):raise ValueError('RUST_NATIVE_COMPILER_OPERATION_FAILED')
            for line in (work/'rustc.stderr').read_text(encoding='utf8').splitlines():
                diagnostic=json.loads(line)
                if diagnostic.get('$message_type')!='diagnostic':continue
                sources={s.path:s for span in diagnostic.get('spans',[]) if (s:=output.source_path(span['file_name']))}
                if not sources:sources=output.sources
                for source in sources.values():output.diagnostic(source,'ERROR' if diagnostic['level'] in ('error','failure-note') else 'WARNING','RUSTC_'+str((diagnostic.get('code') or {}).get('code','NATIVE')),diagnostic['message'])
        else:
            for source in output.sources.values():output.diagnostic(source,'WARNING','RUST_NATIVE_COMPILER_DIAGNOSTICS_NOT_CAPTURED','Language-server authored control; compiler diagnostic evidence is absent')
        def attributed(site):
            value=attribute(site)
            if value is not None and site['category']=='A06' and site['role'] in ('call','construct'):
                parent=site['native']['parent'];owner=None
                while parent:
                    if parent['kind'] in ('CLOSURE_EXPR','CONST','STATIC'):break
                    if parent['kind']=='FN':
                        matches=[d for d in declarations[site['source'].path] if d['node'] is parent]
                        if len(matches)==1:owner=symbol(site['source'],matches[0])['id']
                        break
                    parent=parent['parent']
                value.update(caller=owner,callerStatus='RESOLVED' if owner else 'UNRESOLVED')
                if owner is None:
                    value['callerReason']='RUST_NATIVE_INITIALIZER_OR_ANONYMOUS_CALLER_REQUIRED'
                    if value['status']=='RESOLVED':value.update(targetStatus='RESOLVED',status='PARTIAL',reason=value['callerReason'],uncertainty=[value['callerReason']])
            return value
        output.emit(attributed)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','analyzer'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--compiler',type=Path)
    args=parser.parse_args();analyze(args.request,args.analyzer,args.compiler)
