"""Turn identified research coverage gaps into stable provider observations for GapExport."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def main():
    rows=[]
    def add(mechanism,reason,requirement,kind,identity,criterion,limitation):
        fields=[mechanism,reason,requirement,kind,identity,criterion,limitation]
        assert all('\t' not in f and '\n' not in f for f in fields)
        rows.append('\t'.join(fields))
    census=json.loads((ROOT/'annotation-census.json').read_text('utf-8'))
    for a in census['annotations']:
        add('spring.mechanism.unclassified','ANNOTATION_SEMANTICS_NOT_ADJUDICATED','DEPENDENCY_ARTIFACT','ARTIFACT',
            'sha256:'+a['artifactSha256']+'!'+a['entry']+'#sha256:'+a['entrySha256'],
            'Review exact declaration metadata and semantic role against source and framework version.',
            'Annotation declaration census only; family hints do not establish semantics or application uses.')
    catalog=json.loads((ROOT/'mechanisms.json').read_text('utf-8'))
    for m in catalog['mechanisms']:
        add(m['id'],'PRODUCTION_MECHANISM_NOT_IMPLEMENTED','ALTERNATE_FRONTEND','CATEGORY',m['id'],
            'Implement and independently verify this mechanism within the approved M4 slice.',
            'R0 target only; no production Spring extraction claim.')
    for s in json.loads((ROOT/'sources-acquired.lock.json').read_text('utf-8'))['sources']:
        if s['status']!='ACQUIRED':
            add('spring.mechanism.unclassified','RESEARCH_SOURCE_UNAVAILABLE','REPOSITORY_CONTENT','REPOSITORY_PATH',s['url'],
                'Acquire authoritative versioned source bytes and verify the content digest.',
                'Source '+s['id']+' acquisition outcome: '+s['reason']+'. No source digest fabricated.')
    for version in ['framework-1.x','framework-2.0','framework-2.5','framework-3.0','framework-3.1','framework-3.2','framework-4.0-4.2','framework-4.3','framework-5.0-5.2','boot-1.0-1.5','boot-2.0-2.6','boot-3.0-3.2','boot-3.4-other-patches']:
        add('spring.mechanism.unclassified','VERSION_FRAGMENT_NOT_VALIDATED','DEPENDENCY_ARTIFACT','CATEGORY','spring.version.'+version,
            'Pin exact artifacts and adjudicate version-specific positive negative and order fixtures.',
            'Historical research envelope row; selected modern runtime fixtures do not certify this range.')
    for mechanism,reason,criterion in [
        ('spring.registration.programmatic','PARTIAL_ORDER_ORACLE_NOT_IMPLEMENTED','Verify finite legal-order enumeration and distinguish epistemic order uncertainty.'),
        ('spring.condition.custom-expression','OPAQUE_CORRELATION_ORACLE_NOT_IMPLEMENTED','Verify shared opaque predicates and conservative effects of registry mutations.'),
        ('spring.condition.property','CONFIG_DATA_LOADER_NOT_VALIDATED','Replay precedence imports groups and startup-error cases on pinned loaders.'),
        ('spring.disambiguation.priority','INDEPENDENT_ADJUDICATION_PENDING','Independently review the registered labels raw observations and version-limited claims.'),
        ('spring.condition.bean-state','SPRING_SAT_ENCODING_NOT_BENCHMARKED','Compare a validated transition encoding and a pinned Java SAT or BDD backend on identical worlds.')
    ]:
        add(mechanism,reason,'ALTERNATE_FRONTEND','CATEGORY','spring.research.'+reason.lower(),
            criterion,'Explicit R0 gate qualification; no empirical acceptance inferred.')
    dest=ROOT/'gap-observations.tsv'
    raw='\n'.join(sorted(rows))+'\n'
    if dest.exists(): assert dest.read_text('utf-8')==raw
    else: dest.write_text(raw,encoding='utf-8',newline='\n')
    print(json.dumps({'researchGapObservations':len(rows)}))
if __name__=='__main__': main()
