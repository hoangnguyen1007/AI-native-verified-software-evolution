"""Fetch exact, explicitly listed research jars. No transitive resolution or lifecycle."""
import hashlib, json, urllib.request, zipfile, io
from pathlib import Path
ROOT=Path(__file__).resolve().parent
CACHE=ROOT/'.cache'/'jars'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args): return None
def main():
    CACHE.mkdir(parents=True,exist_ok=True)
    rows=[]; total=0
    specs=[]
    for version in ('5.3.31','6.1.14','6.2.0'):
        for artifact in ('spring-core','spring-jcl','spring-beans','spring-aop','spring-expression','spring-context'):
            specs.append(('org.springframework',artifact,version))
    for version in ('2.7.18','3.3.5','3.4.0'):
        for artifact in ('spring-boot','spring-boot-autoconfigure'):
            specs.append(('org.springframework.boot',artifact,version))
    specs += [('javax.annotation','javax.annotation-api','1.3.2'),('jakarta.annotation','jakarta.annotation-api','2.1.1')]
    opener=urllib.request.build_opener(NoRedirect)
    for group,artifact,version in specs:
        name=f'{artifact}-{version}.jar'
        url='https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+artifact+'/'+version+'/'+name
        path=CACHE/name
        remaining=40_000_000-total
        assert remaining>0
        if path.exists():
            assert not path.is_symlink() and path.stat().st_size<=min(8_000_000,remaining)
            data=path.read_bytes()
        else:
            with opener.open(url,timeout=15) as r: data=r.read(min(8_000_000,remaining)+1)
            assert len(data)<=min(8_000_000,remaining)
            with zipfile.ZipFile(io.BytesIO(data)) as z: assert z.testzip() is None
            path.write_bytes(data)
        total+=len(data)
        assert total<=40_000_000
        rows.append({'coordinate':f'{group}:{artifact}:{version}', 'file':name,'url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    result={'schema':'m4-r0-artifact-lock-v1','artifacts':rows}
    lock=ROOT/'artifacts.lock.json'
    if lock.exists(): assert json.loads(lock.read_text('utf-8'))==result
    else: lock.write_text(json.dumps(result,sort_keys=True,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'artifacts':len(rows),'bytes':total}))
if __name__=='__main__': main()
