"""Read classfile annotation flags from pinned jars; never load or execute their classes."""
import hashlib, json, struct, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def u2(data, at): return struct.unpack_from('>H',data,at)[0]
def annotation_name(data):
    assert data[:4]==b'\xca\xfe\xba\xbe'
    pool={}; i=1; at=10; count=u2(data,8)
    while i<count:
        tag=data[at]; at+=1
        if tag==1:
            length=u2(data,at); at+=2
            pool[i]=data[at:at+length]; at+=length
        elif tag in (7,8,16,19,20):
            pool[i]=u2(data,at); at+=2
        elif tag in (3,4,9,10,11,12,17,18): at+=4
        elif tag in (5,6): at+=8; i+=1
        elif tag==15: at+=3
        else: raise ValueError('Unknown constant-pool tag')
        i+=1
    if u2(data,at)&0x2000:
        # Class names in the selected Spring artifacts are ASCII; reject other encodings explicitly.
        return pool[pool[u2(data,at+2)]].decode('ascii').replace('/','.')
    return None
def main():
    catalog=json.loads((ROOT/'mechanisms.json').read_text('utf-8'))
    aliases={}
    for row in catalog['mechanisms']:
        for name in row['annotationExamples']:
            aliases.setdefault(name,[]).append(row['id'])
    lock=json.loads((ROOT/'artifacts.lock.json').read_text('utf-8'))
    rows=[]; count=0
    for artifact in lock['artifacts']:
        path=ROOT/'.cache/jars'/artifact['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest()==artifact['sha256']
        with zipfile.ZipFile(path) as jar:
            assert len(jar.infolist())==len({i.filename for i in jar.infolist()})
            for name in sorted(jar.namelist()):
                if not name.endswith('.class'): continue
                count+=1; data=jar.read(name); fqn=annotation_name(data)
                if fqn:
                    # Examples only nominate family candidates, never resolve annotation semantics by a simple name.
                    families=aliases.get(fqn.rsplit('.',1)[-1],[])
                    rows.append({'artifact':artifact['coordinate'],'artifactSha256':artifact['sha256'],
                                 'entry':name,'entrySha256':hashlib.sha256(data).hexdigest(),'fqn':fqn,
                                 'candidateFamilies':families,'status':'REQUIRES_DECLARATION_REVIEW',
                                 'reason':'ANNOTATION_SEMANTICS_NOT_ADJUDICATED'})
    result={'schema':'m4-r0-annotation-census-v1','scope':'Declared annotation classes in exactly the 26 locked jars; not annotation uses, not every Spring artifact or historical version.',
            'classFiles':count,'annotations':rows}
    dest=ROOT/'annotation-census.json'
    raw=json.dumps(result,sort_keys=True,indent=2)+'\n'
    if dest.exists(): assert dest.read_text('utf-8')==raw
    else: dest.write_text(raw,encoding='utf-8',newline='\n')
    print(json.dumps({'classFiles':count,'annotationDeclarations':len(rows),'unmapped':sum(not r['candidateFamilies'] for r in rows)}))
if __name__=='__main__': main()
