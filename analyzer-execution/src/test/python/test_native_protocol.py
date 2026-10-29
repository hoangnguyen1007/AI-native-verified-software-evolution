import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE))
spec=importlib.util.spec_from_file_location('native_protocol_controls',BASE/'native_protocol.py');protocol=importlib.util.module_from_spec(spec);spec.loader.exec_module(protocol)


class NativeProtocolControls(unittest.TestCase):
    def test_written_attributes_do_not_close_the_separate_implicit_generation_obligation(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'int x;');source=output.sources['main.c'];output.traversed.add('main.c');output.site(source,'A08','attribute',0,3);stream=io.StringIO();output.emit(lambda site:output.fact('RESOLVED'),stream);rows=[json.loads(line) for line in stream.getvalue().splitlines()];implicit=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields'].get('role')=='implicit-generation-census'];self.assertEqual(1,len(implicit));self.assertEqual('UNSUPPORTED',implicit[0]['status']);self.assertEqual('NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED',implicit[0]['reason'])
    def fixture(self,root,text):
        (root/'main.c').write_bytes(text.encode('utf8'))
        value=dict(schema='test-native:m8.4-v1',language='c',dialect='c17',scope='sha256:'+'1'*64,tool='sha256:'+'2'*64,repositoryScope='repo',packageScope='pkg',provider={'id':'native.c','version':'v1'},sourceRoot=str(root),files=[dict(path='main.c',text=text,sha256=protocol.sha(text.encode('utf8')))])
        file=root/'request.json';file.write_text(json.dumps(value),encoding='utf8');return protocol.Output(file,'test-native:m8.4-v1','c',('c17',))
    def test_original_bom_crlf_nonbmp_and_all_coordinate_encodings(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'\ufeff😀\r\ncall()');source=output.sources['main.c'];start=len('\ufeff😀\r\n'.encode('utf8'));span=source.span(start,start+6,'UTF8')
            self.assertEqual(dict(path='main.c',start=5,end=11,startLine=2,startColumn=1,endLine=2,endColumn=7),span)
            self.assertEqual(span,source.span(5,11,'UTF16'));self.assertEqual(span,source.span(4,10,'UTF32'))
            with self.assertRaises(ValueError):source.span(2,11,'UTF16')
            with self.assertRaises(ValueError):source.span(4,11,'UTF8')
    def test_missing_traversal_is_opaque_instead_of_category_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'int run(void) { return 1; }');stream=io.StringIO();output.emit(lambda site:self.fail('Opaque scopes must not invoke attribution'),stream)
            rows=[json.loads(line) for line in stream.getvalue().splitlines()];observations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION']
            self.assertEqual(12,len(observations));self.assertEqual(10,sum(r['status']=='ERROR' for r in observations));self.assertEqual('ERROR',next(r['status'] for r in observations if r['role']=='scope' and r['reason']=='NATIVE_DIAGNOSTIC_TRAVERSAL_UNAVAILABLE'));self.assertFalse(any(r['status']=='NOT_APPLICABLE' for r in observations));self.assertEqual(rows[-1]['census'],rows[-1]['observations'])
    def test_absent_written_attributes_do_not_prove_absent_implicit_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'int run(void);');output.traversed.add('main.c');stream=io.StringIO();output.emit(lambda site:self.fail('Only scope/absence rows expected'),stream)
            rows=[json.loads(line) for line in stream.getvalue().splitlines()];census={row['key']:row['fields'] for row in rows if row.get('kind')=='CENSUS'}
            generated=next(row['fields'] for row in rows if row.get('kind')=='OBSERVATION' and census[row['key']]['category']=='A08')
            self.assertEqual('UNSUPPORTED',generated['status']);self.assertEqual('NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED',generated['reason'])
    def test_opaque_ranges_are_unique_and_native_uncertainty_survives(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'int run(void);');source=output.sources['main.c'];output.traversed.add(source.path)
            for _ in range(2):output.site(source,'A06','call',-1,999)
            output.site(source,'A03','reference',4,7)
            symbol=output.symbol('scope','FUNCTION:run()','run','FUNCTION','PROJECT');stream=io.StringIO();output.emit(lambda site:output.fact('PARTIAL',[symbol],reason='NATIVE_UNCERTAINTY'),stream)
            rows=[json.loads(line) for line in stream.getvalue().splitlines()];census=[r for r in rows if r.get('kind')=='CENSUS'];observations=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION']
            self.assertEqual(len(census),len({r['key'] for r in census}));self.assertEqual(2,sum(r['status']=='ERROR' and r['role']=='opaque-location' for r in observations));self.assertTrue(next(r for r in observations if r['status']=='PARTIAL')['uncertainty'])
    def test_symbol_key_is_scoped_and_independent_of_provider_version(self):
        with tempfile.TemporaryDirectory() as directory:
            output=self.fixture(Path(directory),'int run(void);');first=output.symbol('scope','FUNCTION:run()','run','FUNCTION','PROJECT')['id'];output.request['provider']['version']='v2'
            self.assertEqual(first,output.symbol('scope','FUNCTION:run()','run','FUNCTION','PROJECT')['id']);output.request['packageScope']='other';self.assertNotEqual(first,output.symbol('scope','FUNCTION:run()','run','FUNCTION','PROJECT')['id'])
    def test_canonical_order_uses_jvm_utf16_and_unescaped_unicode(self):
        self.assertEqual('{"😀":"\u2028","\ue000":"x"}',protocol.canonical({'\ue000':'x','😀':'\u2028'}))
        with self.assertRaises(UnicodeEncodeError):protocol.identity('\ud800')
    def test_captured_source_locators_never_read_host_files_but_still_check_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root,'int run(void);');file=root/'request.json';request=json.loads(file.read_bytes());request['sourceRoot']='/captured/immutable';file.write_text(json.dumps(request),encoding='utf8')
            original=Path.read_bytes
            def read(path):
                self.assertEqual(file,path,'Only the request may be opened, never a native source locator');return original(path)
            with patch.object(Path,'read_bytes',read):
                output=protocol.Output.from_captured(file,'test-native:m8.4-v1','c',('c17',))
                self.assertEqual('main.c',output.source_path('/captured/immutable/main.c').path)
                self.assertIsNone(output.source_path('/CAPTURED/immutable/main.c'))
            request['files'][0]['text']='changed';file.write_text(json.dumps(request),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'NATIVE_SOURCE_HASH'):protocol.Output.from_captured(file,'test-native:m8.4-v1','c',('c17',))
    def test_captured_mode_is_explicit_and_cannot_be_enabled_by_request_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root,'int run(void);');file=root/'request.json';request=json.loads(file.read_bytes());request['sourceRoot']='/absent/captured';request['_captured']=True;file.write_text(json.dumps(request),encoding='utf8')
            with self.assertRaises((OSError,ValueError)):protocol.Output(file,'test-native:m8.4-v1','c',('c17',))
            for bad in ('relative','/captured/../host','/captured//source','/captured\\source'):
                request['sourceRoot']=bad;file.write_text(json.dumps(request),encoding='utf8')
                with self.assertRaisesRegex(ValueError,'CAPTURED_NATIVE_ROOT_LOCATOR'):protocol.Output.from_captured(file,'test-native:m8.4-v1','c',('c17',))


if __name__=='__main__':unittest.main()
