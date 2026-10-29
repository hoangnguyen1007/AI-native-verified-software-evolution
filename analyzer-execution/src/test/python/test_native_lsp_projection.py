"""Original-byte native LSP joins; provider identity is never guessed from names."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE));sys.path.insert(0,str(Path(__file__).parent))
import test_native_protocol as protocol_controls
from native_lsp_projection import parameters,definitions


class NativeLspProjectionControls(unittest.TestCase):
    def test_explicit_sorbet_byte_wire_and_standard_utf16_differ_after_nonbmp(self):
        with tempfile.TemporaryDirectory() as directory:
            out=protocol_controls.NativeProtocolControls().fixture(Path(directory),'\ufeff\r\n😀 twice(4)');source=out.sources['main.c'];offset=len('\ufeff\r\n😀 '.encode('utf8'))
            self.assertEqual({'line':1,'character':3},parameters(source,offset)['position'])
            self.assertEqual({'line':1,'character':5},parameters(source,offset,position_encoding='UTF8')['position'])
    def test_byte_column_definition_joins_native_span_with_no_name_matching(self):
        with tempfile.TemporaryDirectory() as directory:
            out=protocol_controls.NativeProtocolControls().fixture(Path(directory),'😀 twice(4)');source=out.sources['main.c'];span=source.span(5,10,'UTF8')
            declarations={'main.c':[dict(span=span,name='native-declaration-label')]}
            symbol=lambda source,d:out.symbol('scope','FUNCTION:twice','twice','FUNCTION','PROJECT',d['span'])
            location={'uri':source.physical.as_uri(),'range':{'start':{'line':0,'character':5},'end':{'line':0,'character':10}}}
            self.assertEqual('twice',definitions(out,[location],declarations,symbol,position_encoding='UTF8')[0]['name'])
            location['range']['start']['character']=1
            with self.assertRaisesRegex(ValueError,'COLUMN'):definitions(out,[location],declarations,symbol,position_encoding='UTF8')
    def test_ambiguous_native_ranges_and_foreign_documents_never_choose_one_target(self):
        with tempfile.TemporaryDirectory() as directory:
            out=protocol_controls.NativeProtocolControls().fixture(Path(directory),'twice(4)');source=out.sources['main.c'];span=source.span(0,5,'UTF8');location={'uri':source.physical.as_uri(),'range':{'start':{'line':0,'character':1},'end':{'line':0,'character':2}}}
            self.assertEqual([],definitions(out,[location],{'main.c':[dict(span=span),dict(span=span)]},lambda *args:self.fail('Ambiguous positions must remain unresolved')))
            location['uri']='https://example.invalid/source';self.assertEqual([],definitions(out,[location],{'main.c':[]},lambda *args:self.fail('Foreign source is not a captured declaration')))


if __name__=='__main__':unittest.main()
