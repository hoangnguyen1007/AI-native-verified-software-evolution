"""Neutral framing/coordinates for pinned native adapters; no language name/type solver.

The syntax ledger must be registered before attribution. Native recovery preserves opaque
obligations. A missing native traversal cannot establish category non-applicability.
"""
import bisect
import hashlib
import json
from pathlib import Path,PurePosixPath
import re
import sys

from guest_semantic_runner import unique

CATEGORIES=tuple('A%02d'%n for n in range(1,13))


def order(text):return text.encode('utf-16-be',errors='strict')
def canonical(value):
    if isinstance(value,dict):return '{'+','.join(canonical(k)+':'+canonical(value[k]) for k in sorted(value,key=order))+'}'
    if isinstance(value,(list,tuple)):return '['+','.join(canonical(v) for v in value)+']'
    if value is None or type(value) in (str,int,bool):return json.dumps(value,ensure_ascii=False,separators=(',',':'))
    raise ValueError('NATIVE_CANONICAL_VALUE')
def sha(raw):return 'sha256:'+hashlib.sha256(raw).hexdigest()
def identity(value):return sha(canonical(value).encode('utf8',errors='strict'))
def logical(path):
    if (not isinstance(path,str) or not path or path.startswith('/') or '\\' in path or ':' in path or '\0' in path
            or any(p in ('','.','..') for p in path.split('/'))):raise ValueError('NATIVE_LOGICAL_PATH')
    path.encode('utf8',errors='strict');return path


class Source:
    def __init__(self,root,item,*,_captured=False):
        self.path=logical(item['path']);self.text=item['text'];self.raw=self.text.encode('utf8',errors='strict');self.digest=sha(self.raw)
        if self.digest!=item['sha256']:raise ValueError('NATIVE_SOURCE_HASH')
        self.physical=root/self.path
        if not _captured and (self.physical.resolve()!=self.physical or any(p.is_symlink() for p in (self.physical,*self.physical.parents)) or self.physical.read_bytes()!=self.raw):raise ValueError('NATIVE_SOURCE_BYTES_OR_ALIAS')
        self.role=item.get('role','main')
        if self.role not in ('main','test','generated','resolutionOnly'):raise ValueError('NATIVE_SOURCE_ROLE')
        self.bounds={'UTF8':[0],'UTF16':[0],'UTF32':[0]};self.positions=[(1,1)];line=column=1
        for index,char in enumerate(self.text):
            for encoding,step in [('UTF8',len(char.encode('utf8'))),('UTF16',len(char.encode('utf-16-le'))//2),('UTF32',1)]:self.bounds[encoding].append(self.bounds[encoding][-1]+step)
            if char=='\r' and (index+1==len(self.text) or self.text[index+1]!='\n'):line+=1;column=1
            elif char=='\n':line+=1;column=1
            else:column+=len(char.encode('utf-16-le'))//2
            self.positions.append((line,column))
    def span(self,start,end,encoding):
        if type(start) is not int or type(end) is not int or not 0<=start<end:raise ValueError('NATIVE_SOURCE_RANGE')
        offsets=self.bounds[encoding];a=bisect.bisect_left(offsets,start);b=bisect.bisect_left(offsets,end)
        if a==len(offsets) or b==len(offsets) or offsets[a]!=start or offsets[b]!=end:raise ValueError('NATIVE_RANGE_SPLITS_CODEPOINT_OR_EXCEEDS_DOCUMENT')
        first=self.positions[a];last=self.positions[b]
        return dict(path=self.path,start=self.bounds['UTF16'][a],end=self.bounds['UTF16'][b],startLine=first[0],startColumn=first[1],endLine=last[0],endColumn=last[1])


class Output:
    def __init__(self,path,schema,language,dialects,*,_captured=False):
        self.request=json.loads(Path(path).read_bytes(),object_pairs_hook=unique);r=self.request
        if r['schema']!=schema or r['language']!=language or r['dialect'] not in dialects:raise ValueError('NATIVE_PROFILE_NOT_QUALIFIED')
        for name in ('scope','tool'):
            if not re.fullmatch('sha256:[a-f0-9]{64}',r[name]):raise ValueError('NATIVE_DIGEST_REQUIRED')
        self.captured=_captured
        if _captured:
            value=r['sourceRoot']
            if not isinstance(value,str) or not value.startswith('/') or any(p in ('','.','..') for p in value.split('/')[1:]) or '\\' in value or '\0' in value:raise ValueError('CAPTURED_NATIVE_ROOT_LOCATOR')
            self.root=PurePosixPath(value)
        else:
            self.root=Path(r['sourceRoot']).absolute()
            if self.root.resolve()!=self.root:raise ValueError('NATIVE_ROOT_ALIAS')
        self.sources={};self.physical={};self.sites=[];self.diagnostics={};self.traversed=set();self.failed=set()
        for item in r['files']:
            source=Source(self.root,item,_captured=_captured)
            if source.path in self.sources:raise ValueError('NATIVE_DUPLICATE_DOCUMENT')
            if source.physical in self.physical:raise ValueError('NATIVE_PHYSICAL_DOCUMENT_ALIAS')
            self.sources[source.path]=source;self.physical[source.physical]=source
        if not self.sources:raise ValueError('NATIVE_EMPTY_SOURCE_MEMBERSHIP')
        self.sources={path:self.sources[path] for path in sorted(self.sources,key=order)}
    @classmethod
    def from_captured(cls,path,schema,language,dialects):
        """Read-only native-metadata reprojection. Hash-check captured source bytes
        and preserve native POSIX locators; never open those locators on the host.
        The request cannot enable this mode through a JSON field or a native recipe.
        """
        return cls(path,schema,language,dialects,_captured=True)
    def source_path(self,path):return self.physical.get(PurePosixPath(path) if self.captured else Path(path).absolute())
    def diagnostic(self,source,severity,code,message,syntax=False):
        self.diagnostics.setdefault(source.path,[]).append(dict(severity=severity,code=code,message=message))
        if syntax:self.failed.add(source.path)
    def site(self,source,category,role,start=None,end=None,encoding='UTF8',native=None):
        if category not in CATEGORIES or source.path not in self.sources:raise ValueError('NATIVE_CENSUS_CATEGORY_OR_DOCUMENT')
        try:span=source.span(start,end,encoding) if start is not None else None
        except ValueError:
            span=None;role='opaque-location';self.diagnostic(source,'ERROR','NATIVE_RANGE_UNAVAILABLE','Native syntax occurrence has no full original source range')
        self.sites.append(dict(source=source,category=category,role=role,span=span,native=native,ordinal=len(self.sites)))
    def symbol(self,owner,signature,name,kind,origin,span=None,**extra):
        r=self.request
        if any(not isinstance(v,str) or not v.strip() for v in [r['language'],r['repositoryScope'],r['packageScope'],owner,signature]):raise ValueError('NATIVE_SYMBOL_SCOPE_REQUIRED')
        return dict(id=identity(['language-symbol:m8.4-v1',r['language'],r['repositoryScope'],r['packageScope'],owner,signature]),owner=owner,signature=signature,name=name,kind=kind,origin=origin,span=span,**extra)
    @staticmethod
    def fact(status,symbols=(),type=None,caller=None,reason=None,**extra):
        return dict(status=status,symbols=list(symbols),targets=sorted({s['id'] for s in symbols}),type=type,caller=caller,reason=reason,
                    uncertainty=[reason or 'NATIVE_BINDING_RETAINS_UNCERTAINTY'] if status in ('PARTIAL','CONDITIONAL','AMBIGUOUS','CANDIDATES') else [],**extra)
    def emit(self,attribute,stream=None):
        r=self.request
        by_source={path:set() for path in self.sources}
        for site in self.sites:by_source[site['source'].path].add(site['category'])
        for source in self.sources.values():
            if source.path in self.traversed and source.path not in self.failed and 'A08' in by_source[source.path]:self.site(source,'A08','implicit-generation-census')
            for category in ('A01','A11','A12'):self.site(source,category,'scope')
            for category in CATEGORIES:
                if category in ('A01','A11','A12'):continue
                if source.path not in self.traversed or source.path in self.failed:self.site(source,category,'opaque-syntax')
                elif category not in by_source[source.path]:self.site(source,category,'syntax-absent')
        def key(site):
            span=site['span'];return site['source'].path+':'+site['category']+':'+('%012d:%012d'%(span['start'],span['end']) if span else '-00000000001:-00000000001')+':'+site['role']+':%012d'%site['ordinal']
        census=[];observations=[]
        for site in sorted(self.sites,key=lambda s:order(key(s))):
            source=site['source'];category=site['category'];role=site['role'];span=site['span']
            obligation=dict(kind='CENSUS',key=key(site),scope=r['scope'],fields=dict(document=source.path,category=category,role=role,span=span));census.append(obligation)
            if category=='A11':value=self.fact('ERROR' if source.path not in self.traversed or any(d['severity']=='ERROR' for d in self.diagnostics.get(source.path,[])) else 'RESOLVED',reason='NATIVE_DIAGNOSTIC_TRAVERSAL_UNAVAILABLE' if source.path not in self.traversed else None,diagnostics=self.diagnostics.get(source.path,[]))
            elif role=='scope':value=self.fact('RESOLVED')
            elif role in ('opaque-syntax','opaque-location') or source.path in self.failed:value=self.fact('ERROR',reason='NATIVE_PARSE_OR_LOCATION_INCOMPLETE')
            elif role in ('syntax-absent','implicit-generation-census') and category=='A08':value=self.fact('UNSUPPORTED',reason='NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED')
            elif role=='syntax-absent':value=self.fact('NOT_APPLICABLE',reason='NATIVE_SYNTAX_CATEGORY_ABSENT')
            else:value=attribute(site)
            if value is None:continue # Preserve the independently captured obligation as omitted.
            value.update(obligation=identity(obligation),span=span,role=role);observations.append(dict(kind='OBSERVATION',key=key(site),scope=r['scope'],fields=value))
        def write(value):
            text=canonical(value)+'\n'
            if stream is None:sys.stdout.buffer.write(text.encode('utf8',errors='strict'))
            else:stream.write(text)
        write(dict(schema='language-capture-result:m8.4-v1',scope=r['scope'],language=r['language'],dialect=r['dialect'],provider=r['provider'],tool=r['tool'],trust='PRODUCER_ASSERTION'))
        for source in sorted(self.sources.values(),key=lambda s:order(s.path)):write(dict(kind='DOCUMENT',key=source.path,scope=r['scope'],fields=dict(content=dict(digest=source.digest,size=len(source.raw)),language=r['language'],role=source.role)))
        for row in census:write(row)
        for row in observations:write(row)
        write(dict(schema='language-capture-end:m8.4-v1',scope=r['scope'],documents=len(self.sources),census=len(census),observations=len(observations),diagnostics=[d for path in sorted(self.diagnostics,key=order) for d in self.diagnostics[path]]))
