#!/usr/bin/env python3
"""SwiftSyntax census joined by source positions to Swift 6.0.3 compiler AST.

SDK debug AST is version-pinned and decoded as an s-expression, not Swift grammar.
All full source ranges come from native SwiftSyntax; compiler points never become
invented spans. Dynamic/uncaptured bindings retain explicit obligations.
"""
import argparse
import bisect
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output
from guest_semantic_runner import require_file,require_sdk_file,unique
from swift_indexstore import Reader as IndexReader,definitions as index_definitions,relations as index_relations


def ast_wire(text):
    roots=[];stack=[];quoted=False;escape=False;chunk=[]
    def header():
        if chunk:
            if not stack:raise ValueError('SWIFT_AST_TEXT_OUTSIDE_ROOT')
            stack[-1]['header']+=''.join(chunk);chunk.clear()
    for char in text:
        if quoted:
            chunk.append(char)
            if escape:escape=False
            elif char=='\\':escape=True
            elif char=='"':quoted=False
        elif char=='"':quoted=True;chunk.append(char)
        elif char=='(':
            header();node=dict(header='',children=[],parent=stack[-1] if stack else None)
            (stack[-1]['children'] if stack else roots).append(node);stack.append(node)
        elif char==')':
            header()
            if not stack:raise ValueError('SWIFT_AST_UNBALANCED')
            stack.pop()
        elif stack:chunk.append(char)
        elif not char.isspace():raise ValueError('SWIFT_AST_TEXT_OUTSIDE_ROOT')
    if stack or quoted or chunk:raise ValueError('SWIFT_AST_TRUNCATED')
    return roots


def descend(node):
    yield node
    for child in node['children']:yield from descend(child)


def point(source,line,column):
    lines=source.raw.splitlines(keepends=True)
    if not 1<=line<=len(lines) or not 1<=column<=len(lines[line-1])+1:return None
    return sum(map(len,lines[:line-1]))+column-1


def declaration_location(header):
    match=re.search(r'\bdecl="((?:[^"\\]|\\.)*)"',header)
    if not match:return None
    location=re.search(r'@(.+):(\d+):(\d+)(?: \[with [^\n]*\])?$',match.group(1))
    return (location.group(1),int(location.group(2)),int(location.group(3))) if location else None


def index_commands(compiler,module,header_options,module_cache,work,files):
    """Each primary is indexed in the same complete, ordered module context."""
    commands=[]
    for ordinal,primary in enumerate(files):
        inputs=[]
        for path in files:
            if path==primary:inputs.append('-primary-file')
            inputs.append(str(path))
        commands.append([str(compiler),'-frontend','-typecheck','-swift-version','6','-module-name',module,*header_options,'-module-cache-path',module_cache,'-index-store-path',str(work/'swift-index-store'),'-index-unit-output-path',str(work/('swift-index-unit-'+str(ordinal))),*inputs])
    return commands


def analyze(request,compiler=None,census=None,recorded=None):
    out=(Output.from_captured if recorded else Output)(request,'native-swift-request:m8.4-v1','swift',('6',));r=out.request
    records=None;index_data=None
    def captured(handle):
        if set(handle)!={'digest','size'} or not re.fullmatch('sha256:[a-f0-9]{64}',handle['digest']):raise ValueError('SWIFT_NATIVE_RECORD_HANDLE')
        return require_file(Path(recorded)/'objects'/handle['digest'][7:],handle).read_bytes()
    if recorded:
        file=Path(recorded)/'records.json';require_file(file,r['nativeRecords']);records=json.loads(file.read_bytes(),object_pairs_hook=unique)
        fields={'schema','scope','originalTool','documents','syntax','ast','stderr','version','adapterExit','audit'}
        if records.get('schema')=='captured-swift-native-records:m8.4-v2':fields.add('index')
        if set(records)!=fields or records['schema'] not in ('captured-swift-native-records:m8.4-v1','captured-swift-native-records:m8.4-v2') or records['scope']!=r['scope'] or records['originalTool']!=r['nativeOriginTool'] or records['adapterExit']!=0:raise ValueError('SWIFT_NATIVE_RECORD_CONTEXT')
        if records['documents']!={p:s.digest for p,s in out.sources.items()} or set(records['syntax'])!=set(out.sources):raise ValueError('SWIFT_NATIVE_RECORD_SOURCE_MEMBERSHIP')
        version=captured(records['version']).decode('utf8')
        if records.get('index') is not None:index_data=json.loads(captured(records['index']),object_pairs_hook=unique)
    else:
        require_sdk_file(compiler,r['compiler']);require_file(census,r['censusEngine']);runtime=Path(r['syntaxRuntime']['path']);require_sdk_file(runtime,r['syntaxRuntime'])
        host=Path(r['syntaxLibraries'])
        if host!=compiler.parent.parent/'lib/swift/host':raise ValueError('SWIFT_PINNED_SYNTAX_LIBRARY_ROOT')
        environment=dict(os.environ,LD_LIBRARY_PATH=str(host)+':'+str(compiler.parent.parent/'lib/swift/linux'))
        headers=Path(r['syntaxHeaders'])
        if headers!=Path('/opt/evo-toolset/swift-headers'):raise ValueError('SWIFT_PINNED_SYNTAX_HEADER_ROOT')
        # Native SDK search order keeps Clang resource headers before glibc wrappers.
        header_options=['-sdk',str(headers)]
        for directory in (headers/'usr/include',headers/'usr/include/x86_64-linux-gnu'):
            if not directory.is_dir():raise ValueError('SWIFT_CAPTURED_NATIVE_HEADERS_REQUIRED')
        if r['moduleCachePolicy']!='PRIVATE_UNIT_TMPFS_64M_EPHEMERAL':raise ValueError('SWIFT_PRIVATE_MODULE_CACHE_POLICY')
        module_cache='/tmp/evo-native-swift-module-cache'
        version=subprocess.run([str(compiler),'--version'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=True).stdout.decode('utf8')
    if 'Swift version 6.0.3' not in version:raise ValueError('SWIFT_NATIVE_VERSION_MISMATCH')
    if not re.fullmatch('[A-Za-z_][A-Za-z_0-9]*',r['moduleName']):raise ValueError('SWIFT_CAPTURED_MODULE_NAME')
    # Recorded native locators are data, never host write destinations. The
    # caller owns the output stream; this mode opens no work/source paths.
    work=None if records else Path(r['workRoot'])
    if work is not None:work.mkdir(exist_ok=True)
    declarations={path:[] for path in out.sources};callable_regions={path:[] for path in out.sources}
    for ordinal,source in enumerate(out.sources.values()):
        if records:raw_syntax=captured(records['syntax'][source.path])
        else:
            result=subprocess.run([str(runtime),*header_options,'-module-cache-path',module_cache,'-I',str(host),'-L',str(host),'-lSwiftParser','-lSwiftSyntax',str(census),str(source.physical)],env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            (work/(str(ordinal)+'-syntax.json')).write_bytes(result.stdout);(work/(str(ordinal)+'-syntax.stderr')).write_bytes(result.stderr)
            if result.returncode:out.diagnostic(source,'ERROR','SWIFT_NATIVE_SYNTAX_OPERATION','Native syntax tool failed',syntax=True);continue
            raw_syntax=result.stdout
        syntax=json.loads(raw_syntax,object_pairs_hook=unique)
        if syntax['schema']!='native-swift-census:m8.4-v1':raise ValueError('SWIFT_NATIVE_CENSUS_SCHEMA')
        out.traversed.add(source.path)
        if syntax['hasError']:out.diagnostic(source,'ERROR','SWIFT_NATIVE_PARSE','Native syntax tree contains recovery nodes',syntax=True)
        for node in syntax['nodes']:
            kind=node['kind']
            if kind=='token':continue
            if kind in ('functionDecl','initializerDecl','deinitializerDecl','accessorDecl','closureExpr','subscriptDecl'):callable_regions[source.path].append(node)
            def site(category,role):out.site(source,category,role,node['start'],node['end'],native=node)
            if node.get('declaration') or kind in ('functionParameter','identifierPattern','closureExpr','genericParameter'):
                site('A02','declaration')
                if 'name' in node and node['nameStart']<node['nameEnd']:declarations[source.path].append(dict(node=node,span=source.span(node['start'],node['end'],'UTF8'),nameSpan=source.span(node['nameStart'],node['nameEnd'],'UTF8')))
            if kind in ('declReferenceExpr','memberAccessExpr'):site('A03','reference');site('A07','value-access');site('A06','callable-reference')
            if node.get('typeUse') or kind=='functionSignature':site('A04','type-use')
            if kind in ('inheritanceClause','inheritedType','extensionDecl','genericWhereClause'):site('A05','heritage')
            if kind=='declModifier' and node.get('modifier')=='override':site('A05','override')
            if kind=='functionCallExpr':site('A06','call')
            if kind in ('attribute','macroExpansionDecl','macroExpansionExpr','accessorDecl'):site('A08','attribute-or-generation')
            if kind=='importDecl':site('A03','import');site('A09','import-boundary')
            if kind in ('ifConfigClause','ifExpr','guardStmt','whileStmt','switchCase'):site('A10','guard')
    if records:
        # Original root-owned wrapper exit 0 establishes that its fixed compiler
        # operation completed with one of the accepted exits. Do not invent which
        # compiler exit it was: the exact native stdout/stderr are reprojected.
        ast_text=captured(records['ast']).decode('utf8');error_text=captured(records['stderr']).decode('utf8');compiler_failed=None
    else:
        astfile=work/'swift-ast.stdout';errfile=work/'swift-ast.stderr'
        command=[str(compiler),'-frontend','-typecheck','-dump-ast','-swift-version','6','-module-name',r['moduleName'],*header_options,'-module-cache-path',module_cache,*[str(source.physical) for source in out.sources.values()]]
        with astfile.open('xb') as stdout,errfile.open('xb') as stderr:checked=subprocess.run(command,stdout=stdout,stderr=stderr,stdin=subprocess.DEVNULL,timeout=120)
        if checked.returncode not in (0,1):raise ValueError('SWIFT_NATIVE_TYPECHECK_OPERATION')
        compiler_failed=checked.returncode!=0
        ast_text=astfile.read_text(encoding='utf8');error_text=errfile.read_text(encoding='utf8')
        if r.get('indexLibrary'):
            library=Path(r['indexLibrary']['path']);require_sdk_file(library,r['indexLibrary']);store=work/'swift-index-store'
            commands=index_commands(compiler,r['moduleName'],header_options,module_cache,work,[source.physical for source in out.sources.values()])
            for ordinal,command in enumerate(commands):
                with (work/('swift-index-'+str(ordinal)+'.stdout')).open('xb') as stdout,(work/('swift-index-'+str(ordinal)+'.stderr')).open('xb') as stderr:indexed=subprocess.run(command,stdout=stdout,stderr=stderr,stdin=subprocess.DEVNULL,timeout=120)
                if indexed.returncode not in (0,1):raise ValueError('SWIFT_NATIVE_INDEX_OPERATION')
            if store.is_dir():
                index_data=IndexReader(library).read(store,out.source_path,{path:source.digest for path,source in out.sources.items()})
                (work/'swift-index.json').write_text(json.dumps(index_data,ensure_ascii=False,sort_keys=True,separators=(',',':')),encoding='utf8')
    if index_data is not None and (set(index_data)!={'schema','abi','documents','occurrences'} or index_data['schema']!='native-swift-indexstore:m8.4-v1' or index_data['abi']!=15 or index_data['documents']!={path:source.digest for path,source in out.sources.items()}):raise ValueError('SWIFT_NATIVE_INDEX_CONTEXT')
    trees=ast_wire(ast_text);references={};typed_declarations={}
    for tree in trees:
        for node in descend(tree):
            header=node['header'];kind=header.split(None,1)[0] if header.strip() else '';node['kind']=kind
            location=re.search(r'\blocation=(.+?):(\d+):(\d+)(?:\s|$)',header)
            if location:
                file,line,column=location.groups();source=out.source_path(file)
                if source:
                    offset=point(source,int(line),int(column))
                    if offset is not None:references.setdefault((source.path,offset),[]).append(node)
            region=re.search(r'\brange=\[(.+?):(\d+):(\d+) - line:(\d+):(\d+)\]',header)
            if region and kind.endswith('_decl'):
                file,line,column,_,_=region.groups();source=out.source_path(file)
                if source:typed_declarations.setdefault((source.path,point(source,int(line),int(column))),[]).append(node)
    def symbol(source,declaration,typed=None):
        node=declaration['node'];signature=node['kind']+':'+node['name'];header=typed['header'] if typed else ''
        native_type=re.search(r'interface type="((?:[^"\\]|\\.)*)"',header)
        # Native source anchors distinguish overloads/locals on identical captured
        # declarations. Optional typed metadata belongs to the observation, never
        # to logical identity. Stability across source edits is not asserted.
        signature+='@'+str(node['nameStart']);return out.symbol(r['moduleName']+'::'+source.path+'::'+'::'.join(node['owners']),signature,node['name'],node['kind'],'GENERATED' if source.role=='generated' else 'PROJECT',declaration['span'],nativeInterfaceType=native_type.group(1) if native_type else None,locationPrecision='FULL_NATIVE_DECLARATION')
    def caller(source,node):
        enclosing=[region for region in callable_regions[source.path] if region is not node and region['start']<=node['start'] and node['end']<=region['end']]
        if not enclosing:return None
        size=min(region['end']-region['start'] for region in enclosing);nearest=[region for region in enclosing if region['end']-region['start']==size]
        if len(nearest)!=1:return None
        matches=[declaration for declaration in declarations[source.path] if declaration['node'] is nearest[0]]
        return symbol(source,matches[0])['id'] if len(matches)==1 else None
    def native_target(node):
        # Swift anchors implicit receiver parameters at the enclosing function's
        # name. That locator is not a reference to the function itself.
        if re.search(r'\bdecl="(?:[^"\\]|\\.)*\.self@',node['header']):return None
        location=declaration_location(node['header'])
        if not location:return None
        file,line,column=location;source=out.source_path(file)
        if source is None:return None
        offset=point(source,int(line),int(column));matches=[d for d in declarations[source.path] if d['node']['nameStart']==offset]
        if len(matches)!=1:return None
        declaration=matches[0]
        return symbol(source,declaration,typed_declaration(source,declaration))
    def typed_declaration(source,declaration):
        syntax=declaration['node'];typed=[]
        expected={'functionDecl':'func_decl','classDecl':'class_decl','structDecl':'struct_decl','enumDecl':'enum_decl','protocolDecl':'protocol_decl','typeAliasDecl':'typealias','identifierPattern':'var_decl'}.get(syntax['kind'])
        for (path,start),values in typed_declarations.items():
            if path==source.path and start is not None and syntax['start']<=start<=syntax['nameStart']:
                typed.extend(value for value in values if value['kind']==expected and not re.match(r'\S+\s+implicit\b',value['header']))
        return typed[0] if len(typed)==1 else None
    def indexed_declaration(path,line,column,kind):
        source=out.sources.get(path)
        if source is None:return None
        offset=point(source,line,column);matches=[d for d in declarations[path] if d['node']['nameStart']==offset]
        if kind==9:matches=[d for d in declarations[path] if d['node']['kind']=='extensionDecl' and offset is not None and d['node']['start']<=offset<d['node']['end']]
        kinds={5:'enumDecl',6:'structDecl',7:'classDecl',8:'protocolDecl',9:'extensionDecl',11:'typeAliasDecl',12:'functionDecl',16:'functionDecl',17:'functionDecl',18:'functionDecl',22:'initializerDecl'}
        if kind in kinds:matches=[d for d in matches if d['node']['kind']==kinds[kind]]
        return symbol(source,matches[0],typed_declaration(source,matches[0])) if len(matches)==1 else None
    bindings=index_definitions(index_data,indexed_declaration) if index_data is not None else {}
    def attribute(site):
        source=site['source'];node=site['native'];category=site['category'];role=site['role']
        if category=='A10':return out.fact('RESOLVED',truth='UNKNOWN')
        if category in ('A08','A09'):return out.fact('UNSUPPORTED',reason={'A08':'SWIFT_CAPTURED_MACRO_AND_SYNTHETIC_LINEAGE_REQUIRED','A09':'SWIFT_CAPTURED_MODULE_INSTANCE_AND_ABI_SUPPLEMENT_REQUIRED'}[category])
        if category=='A05':
            if index_data is None:return out.fact('UNSUPPORTED',reason='SWIFT_NATIVE_PROTOCOL_EXTENSION_CONFORMANCE_SUPPLEMENT_REQUIRED')
            if node['kind']=='inheritanceClause':return out.fact('NOT_APPLICABLE',reason='NATIVE_AGGREGATE_HAS_SEPARATE_INHERITED_TYPE_QUESTIONS')
            if node['kind']=='genericWhereClause':return out.fact('UNSUPPORTED',reason='SWIFT_NATIVE_GENERIC_REQUIREMENT_QUALIFICATION_REQUIRED')
            region=node.get('ownerDeclaration',node) if role=='override' else node;relation_kind='override' if role=='override' else 'extension' if node['kind']=='extensionDecl' else 'heritage'
            pairs=index_relations(index_data,source.path,region['start'],region['end'],lambda path,line,col:point(out.sources[path],line,col),bindings,relation_kind)
            if not pairs:return out.fact('UNRESOLVED',reason='SWIFT_NATIVE_INDEX_RELATION_UNAVAILABLE')
            if len(pairs)!=1:return out.fact('PARTIAL',reason='SWIFT_NATIVE_INDEX_RELATION_REGION_AMBIGUOUS')
            pair=pairs[0];owners=pair['owners'];targets=pair['targets'];kind=pair['nativeTargetKind'];relation='OVERRIDES' if relation_kind=='override' else 'EXTENDS_TYPE' if relation_kind=='extension' else 'REFINES_PROTOCOL' if kind==8 and len(owners)==1 and owners[0]['kind']=='protocolDecl' else 'CONFORMS_TO' if kind==8 else 'INHERITS' if kind==7 else 'NATIVE_HERITAGE'
            complete=len(owners)==1 and len(targets)==1 and (relation_kind!='heritage' or kind in (7,8));conditional=bool(node.get('conditionalExtension'))
            return out.fact('CONDITIONAL' if complete and conditional else 'RESOLVED' if complete else 'PARTIAL',targets,owner=owners[0]['id'] if len(owners)==1 else None,relation=relation,reason='SWIFT_NATIVE_CONDITIONAL_EXTENSION_REQUIREMENTS_RETAINED' if complete and conditional else None if complete else 'SWIFT_NATIVE_INDEX_DECLARATION_BINDING_INCOMPLETE',nativeIndexRoles=pair['nativeRoles'])
        if category=='A02':
            matches=[d for d in declarations[source.path] if d['node'] is node]
            return out.fact('RESOLVED',[symbol(source,matches[0],typed_declaration(source,matches[0]))]) if len(matches)==1 else out.fact('UNSUPPORTED',reason='SWIFT_NATIVE_ANONYMOUS_DECLARATION_SUPPLEMENT_REQUIRED')
        region=node.get('callee',dict(start=node['start'],end=node['end']));native=[]
        for (path,offset),values in references.items():
            if path==source.path and region['start']<=offset<region['end']:native.extend(value for value in values if value['kind'] in ('declref_expr','member_ref_expr','dynamic_member_ref_expr','unresolved_decl_ref_expr','overloaded_decl_ref_expr'))
        targets={}
        for reference in native:
            target=native_target(reference)
            if target:targets[target['id']]=target
        values=list(targets.values())
        if not values:return out.fact('UNRESOLVED',reason='SWIFT_NATIVE_BINDING_OR_CAPTURED_DECLARATION_UNAVAILABLE')
        if category=='A07' and all(value['kind'] in ('functionDecl','structDecl','classDecl','enumDecl','protocolDecl') for value in values):return out.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_STATE')
        if role=='call' and any(value['kind'] not in ('functionDecl','initializerDecl') for value in values):return out.fact('UNRESOLVED',reason='SWIFT_INDIRECT_RUNTIME_CALL_NOT_ESTABLISHED',calleeSymbols=values)
        status='CANDIDATES' if len(values)>1 else 'RESOLVED';reason=None
        if node['kind']=='memberAccessExpr' or role=='call' and len(node['owners'])>0 and any('dynamic_member_ref_expr'==n['kind'] or re.search(r'\bdecl="(?:[^"\\]|\\.)*\.self@',n['header']) for n in native):status='CONDITIONAL';reason='SWIFT_RECEIVER_OR_PROTOCOL_RUNTIME_DISPATCH_NOT_ESTABLISHED'
        if category=='A07':status='PARTIAL';reason='SWIFT_NATIVE_ACCESSOR_AND_STATE_MODE_SUPPLEMENT_REQUIRED'
        return out.fact(status,values,reason=reason)
    def attributed(site):
        value=attribute(site)
        if site['category']=='A06' and site['role']=='call':
            owner=caller(site['source'],site['native']);value['caller']=owner;value['callerStatus']='RESOLVED' if owner else 'UNRESOLVED'
            if owner is None:
                value['callerReason']='SWIFT_NATIVE_CALLER_UNAVAILABLE'
                if value['status']=='RESOLVED':
                    value.update(targetStatus='RESOLVED',status='PARTIAL',reason='SWIFT_NATIVE_CALLER_UNAVAILABLE',uncertainty=['SWIFT_NATIVE_CALLER_UNAVAILABLE'])
        return value
    found=0
    for line in error_text.splitlines():
        match=re.fullmatch(r'(.+?):(\d+):(\d+): (error|warning|note): (.+)',line)
        if not match:continue
        file,number,column,severity,message=match.groups();source=out.source_path(file)
        if source:out.diagnostic(source,'ERROR' if severity=='error' else 'WARNING','SWIFTC_NATIVE',message);found+=1
    if compiler_failed and not found:
        for source in out.sources.values():out.diagnostic(source,'ERROR','SWIFT_NATIVE_DIAGNOSTIC_SOURCE_UNMAPPED','Native compiler failed; exact stderr retained')
    out.emit(attributed)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--request',type=Path,required=True)
    for name in ('compiler','census','recorded'):parser.add_argument('--'+name,type=Path)
    args=parser.parse_args()
    if bool(args.recorded)==bool(args.compiler or args.census) or not args.recorded and not (args.compiler and args.census):parser.error('Select captured native records or the fixed compiler/census recipe')
    analyze(args.request,args.compiler,args.census,args.recorded)
