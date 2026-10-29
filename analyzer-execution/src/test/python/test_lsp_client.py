"""Authored protocol peer controls, separate from language-engine qualification."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE))
spec=importlib.util.spec_from_file_location('lsp_transport_controls',BASE/'lsp_client.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
PEER='''import json,sys
def send(value):
 raw=json.dumps(dict(jsonrpc='2.0',**value),separators=(',',':')).encode();sys.stdout.buffer.write(('Content-Length: %d\\r\\n\\r\\n'%len(raw)).encode()+raw);sys.stdout.buffer.flush()
def read():
 size=0
 while True:
  line=sys.stdin.buffer.readline()
  if line==b'\\r\\n':break
  if line.lower().startswith(b'content-length:'):size=int(line.split(b':',1)[1])
 return json.loads(sys.stdin.buffer.read(size))
request=read()
'''


class LspTransportControls(unittest.TestCase):
    def run_peer(self,tail,action,**options):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);file=root/'peer.py';file.write_text(PEER+tail,encoding='utf8')
            with (root/'stderr').open('wb') as stderr:
                client=module.LspClient([sys.executable,'-I',str(file)],root,stderr,timeout=2,**options)
                try:action(client)
                finally:client.close()
                self.assertIsNotNone(client.process.returncode);self.assertFalse(client.reader.is_alive())
    def test_fragmented_frame_unicode_and_response_correlation(self):
        self.run_peer("send({'id':request['id'],'result':{'name':'😀'}})\n",lambda client:self.assertEqual({'name':'😀'},client.request('initialize',{})))
    def test_server_edit_is_refused_and_configuration_is_bound(self):
        tail="""send({'id':'edit','method':'workspace/applyEdit','params':{'edit':{'changes':{'file:///host/source':[]}}}})
answer=read();send({'id':request['id'],'result':answer['result']})
"""
        def check(client):
            result=client.request('initialize',{});self.assertEqual(False,result['applied']);self.assertEqual(['workspace/applyEdit'],client.unsupported)
        self.run_peer(tail,check)
    def test_foreign_or_boolean_response_id_is_rejected(self):
        for expression in ('999','True'):
            def check(client):
                with self.assertRaisesRegex(ValueError,'CORRELATION'):client.request('initialize',{})
            self.run_peer("send({'id':"+expression+",'result':None})\n",check)
    def test_bad_frame_duplicate_json_and_truncated_stream_do_not_hang(self):
        for raw in [b'Content-Length: 99999999\r\n\r\n',b'Content-Length: 10\r\n\r\n{}',b'Content-Length: 47\r\n\r\n{"jsonrpc":"2.0","id":1,"id":1,"result":null}']:
            def check(client):
                with self.assertRaises((ValueError,EOFError)):client.request('initialize',{})
            self.run_peer('sys.stdout.buffer.write('+repr(raw)+');sys.stdout.buffer.flush()\n',check)
    def test_large_request_requires_a_larger_worker_without_leaking_process(self):
        def check(client):
            with self.assertRaisesRegex(ValueError,'WORKER_REQUIRED'):client.request('initialize',{'text':'x'*1000})
        self.run_peer('',check,frame_bytes=128)
    def test_native_readiness_requires_positive_barrier_and_handles_server_requests(self):
        tail="""send({'method':'experimental/serverStatus','params':{'quiescent':False}})
send({'id':request['id'],'result':{}})
send({'id':'config','method':'workspace/configuration','params':{'items':[{'section':'native'}]}})
answer=read()
send({'method':'experimental/serverStatus','params':{'quiescent':answer['result']==[{}]}})
"""
        def check(client):
            client.request('initialize',{});self.assertTrue(client.wait_notification('experimental/serverStatus',lambda p:p.get('quiescent'))['quiescent'])
        self.run_peer(tail,check)
    def test_closed_stream_never_becomes_a_ready_native_workspace(self):
        def check(client):
            client.request('initialize',{})
            with self.assertRaises(EOFError):client.wait_notification('experimental/serverStatus',lambda p:p.get('quiescent'))
        self.run_peer("send({'id':request['id'],'result':{}})\nsend({'method':'experimental/serverStatus','params':{'quiescent':False}})\n",check)
