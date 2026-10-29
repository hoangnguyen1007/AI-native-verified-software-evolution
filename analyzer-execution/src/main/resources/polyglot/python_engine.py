#!/usr/bin/env python3
"""Native Python AST census with pinned Pyright definition/hover bindings.

The registered recipe supplies the interpreter, Node and SDK pins. Applications are not
imported for type inference. Host execution is reserved for authored platform controls.
"""
import argparse
import ast
import json
import io
from pathlib import Path
import subprocess
import sys
import tokenize
import urllib.parse

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file
from lsp_client import LspClient


def analyze(file,node,sdk,python):
    out=Output(file,'native-python-request:m8.4-v1','python',('3.10',));r=out.request;sdk=Path(sdk)
    if r['parserVersion']!=sys.version.split()[0] or json.loads((sdk/'package.json').read_bytes())['version']!='1.1.396':raise ValueError('PYTHON_NATIVE_TUPLE_MISMATCH')
    for path,handle in [(node,r['node']),(python,r['python']),(sdk/'langserver.index.js',r['server']),(sdk/'index.js',r['cli'])]:require_file(path,handle)
    nodes={};declarations={};parse_trees={};module_names=r['moduleNames']
    if set(module_names)!=set(out.sources):raise ValueError('PYTHON_MODULE_MEMBERSHIP_REQUIRED')
    for source in out.sources.values():
        lines=source.raw.splitlines(keepends=True);starts=[];total=0
        for line in lines:starts.append(total);total+=len(line)
        def offset(line,column):return starts[line-1]+column+(3 if line==1 and source.raw.startswith(b'\xef\xbb\xbf') else 0)
        def bounds(n):return offset(n.lineno,n.col_offset),offset(n.end_lineno,n.end_col_offset)
        nodes[source.path]={};declarations[source.path]=[]
        try:
            encoding,_=tokenize.detect_encoding(io.BytesIO(source.raw).readline)
            if encoding.lower().replace('_','-') not in ('utf-8','utf-8-sig'):raise ValueError('PYTHON_SOURCE_ENCODING_CONTEXT_UNQUALIFIED')
            tree=ast.parse(source.raw,filename=str(source.physical),feature_version=(3,10));parse_trees[source.path]=tree;out.traversed.add(source.path)
        except (SyntaxError,ValueError) as failure:out.diagnostic(source,'ERROR','PYTHON_NATIVE_PARSE',str(failure.msg) if isinstance(failure,SyntaxError) else 'Native parser rejected source encoding',syntax=True);continue
        def walk(n,parents=(),owners=()):
            scope=module_names[source.path]+('::'+'::'.join(owners) if owners else '')
            def site(category,role,target=n):
                a,b=bounds(target);out.site(source,category,role,a,b,native=(target,parents,scope))
            if hasattr(n,'lineno') and getattr(n,'end_lineno',None) is not None:
                start,end=bounds(n);nodes[source.path][id(n)]=(start,end,parents,scope)
                if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef,ast.arg)) or isinstance(n,ast.Name) and isinstance(n.ctx,(ast.Store,ast.Param)):
                    site('A02','declaration');name=n.name if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)) else n.arg if isinstance(n,ast.arg) else n.id
                    declarations[source.path].append(dict(node=n,name=name,owner=scope,span=source.span(start,end,'UTF8')))
                if isinstance(n,(ast.Name,ast.Attribute,ast.alias)):site('A03','reference')
                if isinstance(n,(ast.Import,ast.ImportFrom)):site('A09','import-boundary')
                if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef)):
                    if n.returns is not None:site('A04','return-annotation',n.returns)
                    for decorator in n.decorator_list:site('A08','decorator',decorator)
                if isinstance(n,ast.arg) and n.annotation is not None:site('A04','parameter-annotation',n.annotation)
                if isinstance(n,ast.AnnAssign):site('A04','variable-annotation',n.annotation)
                if isinstance(n,ast.ClassDef):
                    for base in n.bases:site('A05','heritage',base)
                    for decorator in n.decorator_list:site('A08','decorator',decorator)
                if isinstance(n,ast.Call):site('A06','call')
                if isinstance(n,(ast.Name,ast.Attribute)):site('A07','value-access');site('A06','callable-reference')
                if isinstance(n,(ast.If,ast.While,ast.IfExp)):site('A10','guard',n.test)
            if isinstance(n,ast.comprehension):
                for guard in n.ifs:site('A10','guard',guard)
            if isinstance(n,ast.match_case) and n.guard is not None:site('A10','guard',n.guard)
            next_owners=owners+((n.name,) if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)) else ())
            for child in ast.iter_child_nodes(n):walk(child,parents+(n,),next_owners)
        walk(tree)
    def symbol(source,decl):
        n=decl['node'];kind='CLASS' if isinstance(n,ast.ClassDef) else 'FUNCTION' if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef)) else 'PARAMETER' if isinstance(n,ast.arg) else 'VARIABLE'
        signature=kind+':'+decl['name']
        if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef)):
            args=n.args;signature+='('+','.join(['posonly:'+p.arg for p in args.posonlyargs]+['pos:'+p.arg for p in args.args]+(['*'+args.vararg.arg] if args.vararg else [])+['kwonly:'+p.arg for p in args.kwonlyargs]+(['**'+args.kwarg.arg] if args.kwarg else []))+')'
        if kind in ('VARIABLE','PARAMETER') or sum(d['name']==decl['name'] and d['owner']==decl['owner'] for d in declarations[source.path])>1:signature+='@'+str(decl['span']['start'])
        return out.symbol(decl['owner'],signature,decl['name'],kind,'GENERATED' if source.role=='generated' else 'PROJECT',decl['span'])
    config={'python':{'pythonPath':str(python)},'python.analysis':{'autoSearchPaths':False,'useLibraryCodeForTypes':False,'diagnosticMode':'openFilesOnly','typeCheckingMode':'basic','pythonVersion':'3.10'}}
    output=Path(r['workRoot']);output.mkdir(exist_ok=True)
    with (output/'pyright-lsp.stderr').open('xb') as errors,LspClient([str(node),str(sdk/'langserver.index.js'),'--stdio'],out.root,errors,config) as client:
        initialized=client.request('initialize',dict(processId=None,rootUri=out.root.as_uri(),capabilities={'general':{'positionEncodings':['utf-16']},'workspace':{'configuration':True}},initializationOptions=None,workspaceFolders=[dict(uri=out.root.as_uri(),name='captured')]))
        if initialized['capabilities'].get('positionEncoding','utf-16')!='utf-16':raise ValueError('PYRIGHT_POSITION_ENCODING_UNSUPPORTED')
        client.notify('initialized',{});client.notify('workspace/didChangeConfiguration',{'settings':config})
        for source in out.sources.values():client.notify('textDocument/didOpen',dict(textDocument=dict(uri=source.physical.as_uri(),languageId='python',version=1,text=source.text)))
        cache={}
        def query(source,n):
            data=nodes[source.path].get(id(n))
            if data is None:return [],None
            start,end,_,_=data
            if isinstance(n,ast.Attribute):start=end-len(n.attr.encode('utf8'))
            elif isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)):
                # Declaration binding uses its native AST declaration directly; definition
                # requests are reserved for references, never searched by matching names.
                return [],None
            position=source.span(start,min(end,start+len(source.raw[start:].decode('utf8')[0].encode('utf8'))),'UTF8')
            key=(source.path,position['start'])
            if key not in cache:
                params=dict(textDocument={'uri':source.physical.as_uri()},position={'line':position['startLine']-1,'character':position['startColumn']-1})
                locations=client.request('textDocument/definition',params) or [];locations=locations if isinstance(locations,list) else [locations]
                hover=client.request('textDocument/hover',params);cache[key]=(locations,hover)
            return cache[key]
        def targets(locations):
            values={}
            for location in locations:
                uri=location.get('targetUri',location.get('uri'));parsed=urllib.parse.urlparse(uri)
                if parsed.scheme!='file':continue
                physical=Path(urllib.parse.unquote(parsed.path).lstrip('/') if sys.platform=='win32' else urllib.parse.unquote(parsed.path)).absolute();source=out.source_path(physical)
                if source is None:continue
                pos=location.get('targetSelectionRange',location.get('range'))['start'];candidates=[d for d in declarations[source.path] if d['span']['startLine']-1<=pos['line']<=d['span']['endLine']-1]
                # The native position must lie inside the declaration, with the smallest
                # enclosing native AST range. Name equality never establishes a binding.
                matches=[]
                for decl in candidates:
                    span=decl['span'];point=(pos['line']+1,pos['character']+1)
                    if (span['startLine'],span['startColumn'])<=point<(span['endLine'],span['endColumn']):matches.append(decl)
                if not matches:continue
                smallest=min(d['span']['end']-d['span']['start'] for d in matches);matches=[d for d in matches if d['span']['end']-d['span']['start']==smallest]
                if len(matches)==1:
                    value=symbol(source,matches[0]);values[value['id']]=value
            return list(values.values())
        def attribute(site):
            n,parents,scope=site['native'];source=site['source'];category=site['category'];role=site['role']
            if category=='A10':return out.fact('RESOLVED',truth='UNKNOWN')
            if category=='A09':return out.fact('UNSUPPORTED',reason='PYRIGHT_MODULE_INSTANCE_AND_IMPORT_CLOSURE_SUPPLEMENT_REQUIRED')
            if category=='A02':
                declaration=next(d for d in declarations[source.path] if d['node'] is n);return out.fact('RESOLVED',[symbol(source,declaration)])
            probe=n.func if isinstance(n,ast.Call) else n
            locations,hover=query(source,probe);values=targets(locations)
            if role=='callable-reference':
                if parents and isinstance(parents[-1],ast.Call) and parents[-1].func is n:return out.fact('NOT_APPLICABLE',reason='REFERENCE_IS_INVOCATION_CALLEE')
                if values and all(value['kind'] not in ('FUNCTION','CLASS') for value in values):return out.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_CALLABLE_DECLARATION')
            if category=='A07' and isinstance(n,ast.Attribute) and values:return out.fact('PARTIAL',values,reason='PYRIGHT_DESCRIPTOR_AND_ATTRIBUTE_ACCESS_SUPPLEMENT_REQUIRED',nativeHover=hover)
            if category=='A07' and values and all(value['kind'] in ('FUNCTION','CLASS') for value in values):return out.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_STATE')
            if not values:return out.fact('UNRESOLVED',reason='PYRIGHT_NATIVE_TARGET_OR_CAPTURED_DECLARATION_UNAVAILABLE',nativeHover=hover)
            if category=='A06' and role=='call' and any(value['kind']!='FUNCTION' for value in values):return out.fact('UNRESOLVED',reason='PYRIGHT_INDIRECT_OR_CLASS_RUNTIME_CALL_NOT_ESTABLISHED',calleeSymbols=values,nativeHover=hover)
            status='CANDIDATES' if len(values)>1 else 'RESOLVED';extra={}
            if category=='A07':extra['access']='READ_WRITE' if parents and isinstance(parents[-1],ast.AugAssign) and parents[-1].target is n else 'WRITE' if isinstance(n.ctx,ast.Store) else 'DELETE' if isinstance(n.ctx,ast.Del) else 'READ'
            return out.fact(status,values,nativeHover=hover,**extra)
        # A separate deterministic CLI diagnostic view avoids depending on notification timing.
        command=[str(node),str(sdk/'index.js'),'--outputjson','--pythonversion','3.10','--pythonplatform','Linux','--pythonpath',str(python),*[str(source.physical) for source in out.sources.values()]]
        with (output/'pyright-cli.json').open('xb') as diagnostics,(output/'pyright-cli.stderr').open('xb') as errors:
            result=subprocess.run(command,cwd=out.root,stdin=subprocess.DEVNULL,stdout=diagnostics,stderr=errors,timeout=120)
        if result.returncode not in (0,1):raise ValueError('PYRIGHT_NATIVE_DIAGNOSTIC_OPERATION_FAILED')
        report=json.loads((output/'pyright-cli.json').read_bytes())
        for diagnostic in report['generalDiagnostics']:
            source=out.source_path(diagnostic['file'])
            if source:out.diagnostic(source,diagnostic['severity'].upper(),diagnostic.get('rule','PYRIGHT_NATIVE'),diagnostic['message'])
        def attributed(site):
            value=attribute(site)
            if value is not None and site['category']=='A06' and site['role']=='call':
                node,parents,_=site['native'];owner=None
                for index in range(len(parents)-1,-1,-1):
                    parent=parents[index];child=parents[index+1] if index+1<len(parents) else node
                    if isinstance(parent,ast.Lambda) and child is parent.body:break
                    if isinstance(parent,ast.ClassDef) and child in parent.body:break
                    if isinstance(parent,(ast.FunctionDef,ast.AsyncFunctionDef)) and child in parent.body:
                        matches=[d for d in declarations[site['source'].path] if d['node'] is parent]
                        if len(matches)==1:owner=symbol(site['source'],matches[0])['id']
                        break
                value.update(caller=owner,callerStatus='RESOLVED' if owner else 'UNRESOLVED')
                if owner is None:
                    value['callerReason']='PYTHON_NATIVE_INITIALIZER_OR_ANONYMOUS_CALLER_REQUIRED'
                    if value['status']=='RESOLVED':value.update(targetStatus='RESOLVED',status='PARTIAL',reason=value['callerReason'],uncertainty=[value['callerReason']])
            return value
        out.emit(attributed)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('request','node','sdk','python'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.node,args.sdk,args.python)
