"""The preregistered authored labels run against compiler APIs before costly VM acceptance."""
import json
from pathlib import Path
from native_jvm_fixture import JvmFixture

CASES=json.loads((Path(__file__).resolve().parents[1]/'resources/polyglot/jvm-native-cases.json').read_bytes())


class GoldenCases:
    def test_absent_annotations_do_not_certify_absent_implicit_generation(self):
        rows=self.analyze('class Plain {}');keys={row['key'] for row in rows if row.get('kind')=='CENSUS' and row['fields']['category']=='A08'};fact=next(row['fields'] for row in rows if row.get('kind')=='OBSERVATION' and row['key'] in keys);self.assertEqual('UNSUPPORTED',fact['status']);self.assertEqual('NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED',fact['reason'])
    def test_registered_architectural_cases_and_separate_process_replay(self):
        for name,case in CASES['cases'].items():
            if case['language']!=self.language:continue
            with self.subTest(case=name):
                rows=self.analyze(case['files']);golden=case['expected'];observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];census=[r for r in rows if r.get('kind')=='CENSUS']
                self.assertEqual(len(census),len(observed));self.assertEqual(set(case['files']),{r['key'] for r in rows if r.get('kind')=='DOCUMENT'})
                if golden.get('syntaxError'):
                    self.assertTrue(any(f['status']=='ERROR' for f in observed));self.assertFalse(any(f['status']=='RESOLVED' and f['targets'] for f in observed))
                else:
                    call=next(f for f in observed if f['role']=='call' and f['span'] and case['files'][f['span']['path']].encode('utf-16-le')[f['span']['start']*2:f['span']['end']*2].decode('utf-16-le')==golden['call'])
                    self.assertEqual(golden.get('status','RESOLVED'),call['status'],call)
                    if 'targetName' in golden:
                        self.assertEqual(golden['targetName'],call['symbols'][0]['name']);self.assertEqual(golden['origin'],call['symbols'][0]['origin']);self.assertIn(golden['signatureContains'],call['symbols'][0]['signature'])
                if name in CASES['replays'].values():self.assertEqual(rows,self.analyze(case['files']))


class KotlinGolden(JvmFixture,GoldenCases):engine='KotlinEngine';language='kotlin';dialect='1.9.25';suffix='kt'
class ScalaGolden(JvmFixture,GoldenCases):engine='ScalaEngine';language='scala';dialect='2.13.16';suffix='scala'
class GroovyGolden(JvmFixture,GoldenCases):engine='GroovyEngine';language='groovy';dialect='4.0.27';suffix='groovy'
