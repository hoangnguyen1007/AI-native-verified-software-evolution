"""Native Scala compiler controls; authored fixture execution is not executor qualification."""
from native_jvm_fixture import JvmFixture


class ScalaNativeTest(JvmFixture):
    engine='ScalaEngine';language='scala';dialect='2.13.16';suffix='scala'

    def test_native_inheritance_annotation_and_synthetic_collocated_sites_keep_unique_obligations(self):
        import json
        from pathlib import Path
        case=json.loads((Path(__file__).resolve().parents[1]/'resources/polyglot/jvm-architecture-cases.json').read_text())['cases']['scala-architecture'];rows=self.analyze(case['files']['main.scala']);census=[r for r in rows if r.get('kind')=='CENSUS'];self.assertEqual(len(census),len({r['key'] for r in census}));self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in census});self.assertEqual(rows[-1]['census'],rows[-1]['observations'])

    def test_resolves_overload_and_preserves_original_span(self):
        text='''package app
object Counter {
  def twice(n: Int): Int = n * 2
  def twice(s: String): String = s + s
  def run(): Int = { val marker = "😀"; twice(3) }
}
'''
        rows=self.analyze(text)
        observations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION']
        call=next(r for r in observations if r['role']=='call' and r['span'] and text.encode('utf-16-le')[r['span']['start']*2:r['span']['end']*2].decode('utf-16-le')=='twice(3)')
        self.assertEqual('RESOLVED',call['status'],call)
        self.assertEqual('PROJECT',call['symbols'][0]['origin'])
        self.assertEqual('METHOD:twice(scala.Int)',call['symbols'][0]['signature'])
        self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'})
        self.assertEqual(rows[-1]['census'],rows[-1]['observations'])

    def test_missing_binding_remains_unresolved(self):
        rows=self.analyze('object Counter { def run(): Int = missing(3) }')
        observations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION']
        self.assertTrue(any(r['status']=='UNRESOLVED' and r['role']=='call' and not r['targets'] for r in observations))
        self.assertTrue(any(r.get('severity')=='ERROR' for r in rows[-1]['diagnostics']))

    def test_broken_syntax_retains_every_opaque_occurrence_without_key_collisions(self):
        rows=self.analyze('object Counter { def broken( {\n')
        observations=[r for r in rows if r.get('kind')=='OBSERVATION']
        self.assertEqual(len(observations),len({r['key'] for r in observations}))
        self.assertTrue(any(r['fields']['status']=='ERROR' for r in observations))
        self.assertFalse(any(r['fields']['status']=='RESOLVED' and r['fields']['targets'] for r in observations))
        self.assertTrue(rows[-1]['diagnostics'])
