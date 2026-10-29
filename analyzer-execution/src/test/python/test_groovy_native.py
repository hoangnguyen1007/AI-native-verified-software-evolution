import unittest
from native_jvm_fixture import JvmFixture


class GroovyCompilerControls(JvmFixture):
    engine='GroovyEngine';language='groovy';dialect='4.0.27';suffix='groovy'
    def test_static_compiler_call_target_and_original_span_are_not_guessed(self):
        source='import groovy.transform.CompileStatic\n@CompileStatic\nclass Counter {\n static int twice(int value){value*2}\n static int run(){twice(4)}\n}\n'
        rows=self.analyze(source);calls=[row for row in rows if row.get('kind')=='OBSERVATION' and row['fields'].get('role')=='call'];self.assertEqual(1,len(calls));call=calls[0]['fields'];self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertEqual('twice(4)',source[call['span']['start']:call['span']['end']]);self.assertEqual({f'A{i:02}' for i in range(1,13)},{row['fields']['category'] for row in rows if row.get('kind')=='CENSUS'})

    def test_dynamic_call_is_retained_without_promoting_a_name_match(self):
        rows=self.analyze('class Dynamic { def run(target){target.work()} }\n');calls=[row for row in rows if row.get('kind')=='OBSERVATION' and row['fields'].get('role')=='call'];self.assertEqual(1,len(calls));self.assertEqual('UNRESOLVED',calls[0]['fields']['status']);self.assertEqual([],calls[0]['fields']['targets'])

    def test_native_codepoint_columns_map_to_original_utf16_after_nonbmp_on_crlf_line(self):
        source='import groovy.transform.CompileStatic\r\n@CompileStatic\r\nclass Counter {\r\n static int twice(int value){value*2}\r\n static int run(){def marker="😀"; twice(4)}\r\n}\r\n'
        rows=self.analyze(source);call=next(row['fields'] for row in rows if row.get('kind')=='OBSERVATION' and row['fields'].get('role')=='call');span=call['span']
        self.assertEqual('twice(4)',source.encode('utf-16-le')[span['start']*2:span['end']*2].decode('utf-16-le'));self.assertEqual('RESOLVED',call['status'])


if __name__=='__main__':unittest.main()
