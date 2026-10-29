#!/usr/bin/env python3
"""Pinned libclang 18.1.1 native C/C++/Objective-C adapter. No application execution.

One export is one translation-unit/compile-command context; headers are not flattened
across commands. Compiler/SDK provenance is supplied by the registered worker recipe.
"""
import argparse
import ctypes
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_protocol import Output,logical,sha
from guest_semantic_runner import require_file

PROFILES={'c':('c17','c'),'cpp':('c++20','c++'),'objectivec':('gnu17','objective-c')}


def analyze(request,bindings,library):
    import json
    from guest_semantic_runner import unique
    raw=json.loads(Path(request).read_bytes(),object_pairs_hook=unique);language=raw['language']
    if language not in PROFILES:raise ValueError('CLANG_LANGUAGE_UNQUALIFIED')
    dialect,native_language=PROFILES[language];out=Output(request,'native-clang-request:m8.4-v1',language,(dialect,));r=out.request
    require_file(library,r['library']);require_file(Path(bindings)/'clang/cindex.py',r['bindings'])
    sys.path.insert(0,str(bindings))
    from clang import cindex as cx
    cx.Config.set_library_file(str(library));lib=cx.conf.lib
    lib.clang_getClangVersion.restype=cx._CXString;lib.clang_getClangVersion.errcheck=cx._CXString.from_result
    version=lib.clang_getClangVersion()
    if not ('18.1.1' in version):raise ValueError('CLANG_NATIVE_VERSION_MISMATCH')
    arguments=r['arguments']
    if not isinstance(arguments,list) or any(not isinstance(arg,str) or '\0' in arg for arg in arguments):raise ValueError('CLANG_ARGUMENTS_INVALID')
    if any(arg in ('-load','-plugin','-add-plugin','-fplugin') or arg.startswith(('-fplugin=','-fpass-plugin=','@')) for arg in arguments):
        for source in out.sources.values():out.diagnostic(source,'ERROR','CLANG_EXECUTABLE_EXTENSION_REQUIRES_RECIPE','Executable compiler extensions require a separately registered isolated build recipe',syntax=True)
        out.emit(lambda site:out.fact('UNSUPPORTED',reason='CLANG_EXECUTABLE_EXTENSION_REQUIRES_RECIPE'));return
    unit=logical(r['translationUnit'])
    if unit not in out.sources:raise ValueError('CLANG_TRANSLATION_UNIT_MEMBERSHIP')
    if arguments[:4]!=['-x',native_language,'-std='+dialect,'-fsyntax-only']:raise ValueError('CLANG_COMMAND_CONTEXT_MISMATCH')
    if any(arg in ('-x','-std','--std','--language','-cc1') or arg.startswith(('-std=','--std=','--language=','--driver-mode=')) for arg in arguments[4:]):raise ValueError('CLANG_COMMAND_DIALECT_OVERRIDE')
    index=cx.Index.create(excludeDecls=False);tu=index.parse(str(out.sources[unit].physical),args=arguments,unsaved_files=[(str(s.physical),s.text) for s in out.sources.values()],options=cx.TranslationUnit.PARSE_DETAILED_PROCESSING_RECORD)
    def source_for(location):
        return out.source_path(location.file.name) if location.file else None
    def source_of(cursor):return source_for(cursor.extent.start)
    def span(cursor):
        source=source_of(cursor)
        if source is None or source_for(cursor.extent.end)!=source:return None
        try:return source.span(cursor.extent.start.offset,cursor.extent.end.offset,'UTF8')
        except ValueError:return None
    errors=False
    for diagnostic in tu.diagnostics:
        source=source_for(diagnostic.location) or out.sources[unit];severity='ERROR' if diagnostic.severity>=cx.Diagnostic.Error else 'WARNING'
        out.diagnostic(source,severity,'CLANG_'+str(diagnostic.category_number),diagnostic.spelling);errors|=severity=='ERROR'
    # Clang may drop a malformed expression entirely. Native error recovery cannot prove
    # absence of calls/types in that TU; retain opaque scope obligations in every document.
    if errors:
        for source in out.sources.values():out.failed.add(source.path)
    kinds=cx.CursorKind
    def ensure_kind(cursor):
        try:return cursor.kind
        except ValueError:
            # The pinned wheel omits several real C-index enums (e.g. ObjCRootClass=431).
            # Ask the same native library for its spelling/category; do not edit vendor bytes
            # or discard the cursor because the Python enumeration is older than that ABI.
            number=cursor._kind_id
            lib.clang_getCursorKindSpelling.argtypes=[ctypes.c_uint]
            lib.clang_getCursorKindSpelling.restype=cx._CXString;lib.clang_getCursorKindSpelling.errcheck=cx._CXString.from_result
            name=lib.clang_getCursorKindSpelling(number)
            if not name or not 0<=number<=700:raise ValueError('CLANG_CURSOR_KIND_UNRECOGNIZED')
            value=cx.CursorKind(number);setattr(cx.CursorKind,'NATIVE_'+str(number)+'_'+name,value);return value
    functions={kinds.FUNCTION_DECL,kinds.CXX_METHOD,kinds.CONSTRUCTOR,kinds.DESTRUCTOR,kinds.CONVERSION_FUNCTION,kinds.FUNCTION_TEMPLATE,kinds.OBJC_INSTANCE_METHOD_DECL,kinds.OBJC_CLASS_METHOD_DECL}
    state={kinds.VAR_DECL,kinds.FIELD_DECL,kinds.PARM_DECL,kinds.OBJC_PROPERTY_DECL,kinds.OBJC_IVAR_DECL}
    references={kinds.DECL_REF_EXPR,kinds.MEMBER_REF_EXPR,kinds.TYPE_REF,kinds.TEMPLATE_REF,kinds.NAMESPACE_REF,kinds.OVERLOADED_DECL_REF,kinds.MEMBER_REF,kinds.OBJC_CLASS_REF,kinds.OBJC_PROTOCOL_REF,kinds.OBJC_SUPER_CLASS_REF}
    calls={kinds.CALL_EXPR,kinds.OBJC_MESSAGE_EXPR,kinds.CXX_NEW_EXPR,kinds.CXX_DELETE_EXPR}
    parents={kinds.CXX_BASE_SPECIFIER,kinds.OBJC_SUPER_CLASS_REF,kinds.OBJC_PROTOCOL_REF}
    attributes={kinds.ANNOTATE_ATTR,kinds.UNEXPOSED_ATTR,kinds.MACRO_DEFINITION,kinds.MACRO_INSTANTIATION}
    call_regions=[];seen=set()
    def census(cursor,ancestors=()):
        ensure_kind(cursor);source=source_of(cursor);children=list(cursor.get_children())
        if source is not None:
            out.traversed.add(source.path);seen.add(source.path)
            def site(category,role,target=cursor):out.site(source,category,role,target.extent.start.offset,target.extent.end.offset,native=(cursor,ancestors))
            if cursor.kind==kinds.NAMESPACE:site('A01','namespace')
            if cursor.kind.is_declaration():site('A02','declaration')
            if cursor.kind in references:site('A03','reference')
            if cursor.kind in (kinds.TYPE_REF,kinds.TEMPLATE_REF) or cursor.kind.is_declaration() and cursor.type.kind!=cx.TypeKind.INVALID:site('A04','type-use')
            if cursor.kind in parents:site('A05','heritage')
            if cursor.kind in calls:
                site('A06','message-send' if cursor.kind==kinds.OBJC_MESSAGE_EXPR else 'call')
                if children:call_regions.append((source.path,children[0].extent.start.offset,children[0].extent.end.offset))
            if cursor.kind in (kinds.DECL_REF_EXPR,kinds.MEMBER_REF_EXPR):site('A07','value-access');site('A06','callable-reference')
            if cursor.kind in attributes or cursor.kind.is_attribute():site('A08','native-annotation-or-macro')
            if cursor.kind==kinds.INCLUSION_DIRECTIVE:site('A03','include');site('A09','include-boundary')
            if cursor.kind in (kinds.IF_STMT,kinds.SWITCH_STMT,kinds.WHILE_STMT,kinds.FOR_STMT,kinds.CONDITIONAL_OPERATOR):site('A10','guard')
        for child in children:
            # SDK ASTs remain origins of referenced declarations. They are not emitted as project code.
            if source_of(child) is not None or ensure_kind(child)==kinds.TRANSLATION_UNIT:census(child,ancestors+(cursor,))
    census(tu.cursor)
    # An empty TU still has native parsing evidence, even when it has no cursor children.
    out.traversed.add(unit)
    def owner(cursor):
        values=[];parent=cursor.semantic_parent
        while parent and parent.kind!=kinds.TRANSLATION_UNIT:
            values.append(parent.spelling or parent.kind.name+'@'+str(parent.location.offset));parent=parent.semantic_parent
        return '::'.join(reversed(values)) or '<global-namespace>'
    def symbol(cursor):
        if cursor is None or cursor.kind.is_invalid():return None
        source=source_of(cursor);origin='GENERATED' if source is not None and source.role=='generated' else 'PROJECT' if source else 'UNKNOWN'
        filename=str(Path(cursor.location.file.name).absolute()) if cursor.location.file else None
        if source is None and filename:
            for entry in r['originRoots']:
                root=Path(entry['path']).absolute()
                if Path(filename).is_relative_to(root):
                    if entry['origin'] not in ('PLATFORM','DEPENDENCY'):raise ValueError('CLANG_ORIGIN_CLASSIFICATION')
                    origin=entry['origin'];break
        signature=cursor.kind.name+':'+cursor.spelling+':'+cursor.type.get_canonical().spelling
        native_owner=owner(cursor)
        if cursor.linkage==cx.LinkageKind.INTERNAL or cursor.kind in (kinds.VAR_DECL,kinds.PARM_DECL) and cursor.semantic_parent and cursor.semantic_parent.kind in functions:
            native_owner+='@'+(source.path if source else '<uncaptured>')+':'+str(cursor.location.offset)
        return out.symbol(native_owner,signature,cursor.spelling or '<anonymous>',cursor.kind.name,origin,span(cursor),nativeUsr=cursor.get_usr(),mangledName=cursor.mangled_name,locationPrecision='FULL_NATIVE_EXTENT' if span(cursor) else 'ABSENT_UNCAPTURED_DECLARATION')
    def overloaded(cursor):
        if cursor.kind!=kinds.OVERLOADED_DECL_REF:return []
        lib.clang_getNumOverloadedDecls.argtypes=[cx.Cursor];lib.clang_getNumOverloadedDecls.restype=ctypes.c_uint
        lib.clang_getOverloadedDecl.argtypes=[cx.Cursor,ctypes.c_uint];lib.clang_getOverloadedDecl.restype=cx.Cursor
        values=[]
        for i in range(lib.clang_getNumOverloadedDecls(cursor)):
            declaration=lib.clang_getOverloadedDecl(cursor,i);declaration._tu=tu;value=symbol(declaration)
            if value:values.append(value)
        return values
    def attribute(site):
        cursor,ancestors=site['native'];category=site['category'];role=site['role'];target=cursor if category=='A02' else cursor.referenced
        caller=next((symbol(parent) for parent in reversed(ancestors) if parent.kind in functions),None);caller=caller['id'] if caller else None
        if category=='A10':return out.fact('RESOLVED',caller=caller,truth='UNKNOWN',nativeKind=cursor.kind.name)
        if role=='callable-reference':
            if any(path==site['source'].path and start<=cursor.extent.start.offset and cursor.extent.end.offset<=end for path,start,end in call_regions):return out.fact('NOT_APPLICABLE',reason='REFERENCE_IS_INVOCATION_CALLEE')
            if target and target.kind not in functions:return out.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_CALLABLE_DECLARATION')
        if category=='A07' and target and target.kind not in state:return out.fact('NOT_APPLICABLE',reason='NATIVE_BINDING_IS_NOT_STATE')
        if category=='A04':
            type=cursor.type.get_canonical()
            if type.kind==cx.TypeKind.INVALID:return out.fact('UNRESOLVED',reason='CLANG_NATIVE_TYPE_UNAVAILABLE')
            declaration=type.get_declaration();value=symbol(declaration)
            return out.fact('PARTIAL' if value and value['origin']=='UNKNOWN' else 'RESOLVED',[value] if value else [],type=type.spelling,caller=caller)
        if role=='native-annotation-or-macro':return out.fact('UNSUPPORTED',reason='CLANG_MACRO_EXPANSION_LINEAGE_SUPPLEMENT_REQUIRED',nativeKind=cursor.kind.name)
        if role in ('include','include-boundary'):
            included=cursor.get_included_file();return out.fact('PARTIAL' if included else 'UNRESOLVED',reason='CLANG_INCLUDE_BYTES_AND_CONTEXT_REQUIRE_CAPTURED_MEMBERSHIP',include=cursor.spelling)
        if category=='A06' and role!='callable-reference' and target and target.kind not in functions:return out.fact('UNRESOLVED',reason='CLANG_INDIRECT_CALL_TARGET_NOT_ESTABLISHED',calleeSymbol=symbol(target),caller=caller)
        values=overloaded(cursor)
        if values:return out.fact('CANDIDATES',values,reason='CLANG_OVERLOADED_DECLARATION_SET',caller=caller)
        value=symbol(target)
        if value is None:return out.fact('UNRESOLVED',reason='CLANG_NATIVE_BINDING_UNAVAILABLE',caller=caller)
        status='CONDITIONAL' if cursor.kind==kinds.OBJC_MESSAGE_EXPR else 'PARTIAL' if value['origin']=='UNKNOWN' else 'RESOLVED'
        extra={}
        if category=='A07':
            extra['access']='READ_OR_NATIVE_WRITE_CLASSIFICATION_PENDING'
            return out.fact('PARTIAL',[value],type=cursor.type.get_canonical().spelling,caller=caller,reason='CLANG_STATE_ACCESS_MODE_SUPPLEMENT_REQUIRED',**extra)
        return out.fact(status,[value],type=cursor.type.get_canonical().spelling,caller=caller,reason='OBJC_RUNTIME_DISPATCH_NOT_A_UNIVERSAL_TARGET' if status=='CONDITIONAL' else None,**extra)
    out.emit(attribute)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--request',type=Path,required=True);parser.add_argument('--bindings',type=Path,required=True);parser.add_argument('--library',type=Path,required=True)
    args=parser.parse_args();analyze(args.request,args.bindings,args.library)
