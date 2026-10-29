#!/usr/bin/env python3
"""Read the pinned Swift 6.0.3 Index Store C API inside the isolated worker.

ABI: swiftlang/llvm-project swift-6.0.3-RELEASE, clang/include/indexstore/indexstore.h
(Apache-2.0 WITH LLVM-exception). No index database or application code is loaded
on the controller. Borrowed native pointers are copied before their reader closes.
"""
import ctypes as c
import json

DECLARATION=1;DEFINITION=2;REFERENCE=4;BASE_OF=1<<10;OVERRIDE_OF=1<<11;EXTENDED_BY=1<<14


class String(c.Structure):
    _fields_=[('data',c.c_void_p),('length',c.c_size_t)]


def text(value):
    if not value.length:return ''
    if not value.data:raise ValueError('SWIFT_INDEXSTORE_NULL_STRING')
    return c.string_at(value.data,value.length).decode('utf8')


class Reader:
    def __init__(self,library):
        self.lib=c.CDLL(str(library));p=c.c_void_p;u=c.c_uint;role=c.c_uint64
        self.unit_callback=c.CFUNCTYPE(c.c_bool,p,String);self.pointer_callback=c.CFUNCTYPE(c.c_bool,p,p)
        signatures={
            'version':(u,[]),'store_create':(p,[c.c_char_p,c.POINTER(p)]),'store_dispose':(None,[p]),
            'error_get_description':(c.c_char_p,[p]),'error_dispose':(None,[p]),
            'store_units_apply_f':(c.c_bool,[p,u,p,self.unit_callback]),
            'unit_reader_create':(p,[p,c.c_char_p,c.POINTER(p)]),'unit_reader_dispose':(None,[p]),
            'unit_reader_dependencies_apply_f':(c.c_bool,[p,p,self.pointer_callback]),
            'unit_dependency_get_kind':(u,[p]),'unit_dependency_get_filepath':(String,[p]),'unit_dependency_get_name':(String,[p]),
            'record_reader_create':(p,[p,c.c_char_p,c.POINTER(p)]),'record_reader_dispose':(None,[p]),
            'record_reader_occurrences_apply_f':(c.c_bool,[p,p,self.pointer_callback]),
            'occurrence_get_symbol':(p,[p]),'occurrence_get_roles':(role,[p]),'occurrence_get_line_col':(None,[p,c.POINTER(u),c.POINTER(u)]),
            'occurrence_relations_apply_f':(c.c_bool,[p,p,self.pointer_callback]),
            'symbol_relation_get_roles':(role,[p]),'symbol_relation_get_symbol':(p,[p]),
            'symbol_get_name':(String,[p]),'symbol_get_usr':(String,[p]),'symbol_get_kind':(u,[p]),'symbol_get_language':(u,[p])}
        for name,(result,args) in signatures.items():
            method=getattr(self.lib,'indexstore_'+name);method.restype=result;method.argtypes=args;setattr(self,name,method)
        if self.version()!=15:raise ValueError('SWIFT_INDEXSTORE_ABI_VERSION_UNSUPPORTED')
    def create(self,method,*args):
        error=c.c_void_p();value=method(*args,c.byref(error))
        if error.value:
            try:detail=self.error_get_description(error).decode('utf8')
            finally:self.error_dispose(error)
            raise ValueError('SWIFT_INDEXSTORE_NATIVE_READER:'+detail)
        if not value:raise ValueError('SWIFT_INDEXSTORE_NATIVE_READER_NULL')
        return value
    def each(self,method,reader,callback,unit=False):
        errors=[]
        def guarded(context,value):
            try:callback(value);return True
            except BaseException as error:errors.append(error);return False
        wrapped=(self.unit_callback if unit else self.pointer_callback)(guarded)
        ok=method(reader,1,None,wrapped) if unit else method(reader,None,wrapped)
        if errors:raise errors[0]
        if not ok:raise ValueError('SWIFT_INDEXSTORE_NATIVE_TRAVERSAL_FAILED')
    def symbol(self,pointer):
        if not pointer:raise ValueError('SWIFT_INDEXSTORE_SYMBOL_ABSENT')
        return dict(usr=text(self.symbol_get_usr(pointer)),name=text(self.symbol_get_name(pointer)),kind=self.symbol_get_kind(pointer),language=self.symbol_get_language(pointer))
    def read(self,path,source_for,documents):
        store=self.create(self.store_create,str(path).encode('utf8'));records=set();occurrences={}
        try:
            def unit(name):
                reader=self.create(self.unit_reader_create,store,text(name).encode('utf8'))
                def dependency(value):
                    if self.unit_dependency_get_kind(value)!=2:return
                    source=source_for(text(self.unit_dependency_get_filepath(value)))
                    if source is not None:records.add((source.path,text(self.unit_dependency_get_name(value))))
                try:self.each(self.unit_reader_dependencies_apply_f,reader,dependency)
                finally:self.unit_reader_dispose(reader)
            self.each(self.store_units_apply_f,store,unit,unit=True)
            for source,name in sorted(records):
                reader=self.create(self.record_reader_create,store,name.encode('utf8'))
                def occurrence(value):
                    line=c.c_uint();column=c.c_uint();self.occurrence_get_line_col(value,c.byref(line),c.byref(column));relations=[]
                    self.each(self.occurrence_relations_apply_f,value,lambda rel:relations.append(dict(roles=self.symbol_relation_get_roles(rel),symbol=self.symbol(self.symbol_relation_get_symbol(rel)))))
                    relations.sort(key=lambda rel:(rel['roles'],rel['symbol']['usr']));row=dict(document=source,line=line.value,column=column.value,symbol=self.symbol(self.occurrence_get_symbol(value)),roles=self.occurrence_get_roles(value),relations=relations);occurrences[json.dumps(row,sort_keys=True,separators=(',',':'))]=row
                try:self.each(self.record_reader_occurrences_apply_f,reader,occurrence)
                finally:self.record_reader_dispose(reader)
        finally:self.store_dispose(store)
        return dict(schema='native-swift-indexstore:m8.4-v1',abi=15,documents=documents,occurrences=[occurrences[key] for key in sorted(occurrences)])


def definitions(index,locate):
    """Join exact native definition positions, never declaration display names."""
    found={}
    for row in index['occurrences']:
        if row['roles']&(DECLARATION|DEFINITION):
            value=locate(row['document'],row['line'],row['column'],row['symbol']['kind'])
            if value is not None:found.setdefault(row['symbol']['usr'],{})[value['id']]=value
    return found


def relations(index,document,start,end,offset,bindings,kind):
    """Return native owner/target pairs located within a SwiftSyntax question."""
    found={}
    for row in index['occurrences']:
        if row['document']!=document:continue
        position=offset(document,row['line'],row['column'])
        if position is None or not start<=position<end:continue
        for relation in row['relations']:
            mask=OVERRIDE_OF if kind=='override' else EXTENDED_BY if kind=='extension' else BASE_OF
            if not relation['roles']&mask:continue
            owner_usr,target_usr=(row['symbol']['usr'],relation['symbol']['usr']) if mask==OVERRIDE_OF else (relation['symbol']['usr'],row['symbol']['usr'])
            owners=list(bindings.get(owner_usr,{}).values());targets=list(bindings.get(target_usr,{}).values())
            record=dict(owners=owners,targets=targets,nativeTargetKind=relation['symbol']['kind'] if mask==OVERRIDE_OF else row['symbol']['kind'],nativeRoles=relation['roles'])
            found[(owner_usr,target_usr,relation['roles'])]=record
    return list(found.values())
