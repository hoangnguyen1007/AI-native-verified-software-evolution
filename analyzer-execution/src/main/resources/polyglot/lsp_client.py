"""Bounded, request-correlated LSP transport for registered native providers.

No editor mutation or arbitrary server-command execution is supported. The process must
be started by the fixed isolated recipe (or an explicitly authored platform fixture).
"""
import json
import queue
import subprocess
import threading
import time

from guest_semantic_runner import unique


class LspClient:
    def __init__(self,argv,cwd,stderr,configuration=None,frame_bytes=33554432,timeout=60):
        if type(frame_bytes) is not int or frame_bytes<1:raise ValueError('LSP_FRAME_WORKER_POLICY')
        self.process=subprocess.Popen(argv,cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr)
        self.configuration=configuration or {};self.frame_bytes=frame_bytes;self.timeout=timeout;self.events=queue.Queue(maxsize=32);self.serial=0;self.diagnostics={};self.notifications={};self.unsupported=[];self.closed=False
        self.reader=threading.Thread(target=self._read,daemon=True);self.reader.start()
    def _deliver(self,value):
        while not self.closed:
            try:self.events.put(value,timeout=.1);return True
            except queue.Full:continue
        return False
    def _read(self):
        try:
            while True:
                headers={};total=0
                while True:
                    line=self.process.stdout.readline(8193);total+=len(line)
                    if not line:raise EOFError('LSP_SERVER_STREAM_ENDED')
                    if total>8192:raise ValueError('LSP_HEADER_WORKER_REQUIRED')
                    if line==b'\r\n':break
                    key,separator,value=line.decode('ascii',errors='strict').partition(':')
                    if not separator or key.lower() in headers:raise ValueError('LSP_HEADER_INVALID')
                    headers[key.lower()]=value.strip()
                size=headers.get('content-length','')
                if not size.isascii() or not size.isdigit() or not 0<int(size)<=self.frame_bytes:raise ValueError('LSP_FRAME_WORKER_REQUIRED')
                raw=self.process.stdout.read(int(size))
                if len(raw)!=int(size):raise EOFError('LSP_FRAME_TRUNCATED')
                value=json.loads(raw,object_pairs_hook=unique)
                if not isinstance(value,dict) or value.get('jsonrpc')!='2.0':raise ValueError('LSP_MESSAGE_INVALID')
                if not self._deliver(value):return
        except (ValueError,OSError,EOFError) as failure:self._deliver(failure)
    def send(self,value):
        raw=json.dumps(dict(jsonrpc='2.0',**value),separators=(',',':'),ensure_ascii=False).encode('utf8',errors='strict')
        if len(raw)>self.frame_bytes:raise ValueError('LSP_REQUEST_WORKER_REQUIRED')
        self.process.stdin.write(('Content-Length: %d\r\n\r\n'%len(raw)).encode('ascii')+raw);self.process.stdin.flush()
    def notify(self,method,params):self.send(dict(method=method,params=params))
    def request(self,method,params):
        self.serial+=1;identifier=self.serial;self.send(dict(id=identifier,method=method,params=params));deadline=time.monotonic()+self.timeout
        while True:
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError('LSP_RESPONSE_WORKER_REQUIRED')
            try:value=self.events.get(timeout=remaining)
            except queue.Empty:raise TimeoutError('LSP_RESPONSE_WORKER_REQUIRED')
            if isinstance(value,Exception):raise value
            if 'method' in value:
                if 'id' in value:self._server_request(value)
                else:self._notification(value)
                continue
            if type(value.get('id')) is not int or value['id']!=identifier:raise ValueError('LSP_RESPONSE_CORRELATION_MISMATCH')
            if 'error' in value:raise ValueError('LSP_NATIVE_REQUEST_ERROR:'+str(value['error'].get('code')))
            if 'result' not in value:raise ValueError('LSP_RESPONSE_RESULT_REQUIRED')
            return value['result']
    def _notification(self,value):
        self.notifications[value['method']]=value.get('params')
        if value['method']=='textDocument/publishDiagnostics':
            params=value['params'];self.diagnostics[params['uri']]=params
    def wait_notification(self,method,predicate):
        """Wait for a native readiness barrier; elapsed time never proves readiness."""
        deadline=time.monotonic()+self.timeout
        while True:
            if method in self.notifications and predicate(self.notifications[method]):return self.notifications[method]
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError('LSP_NATIVE_READINESS_WORKER_REQUIRED')
            try:value=self.events.get(timeout=remaining)
            except queue.Empty:raise TimeoutError('LSP_NATIVE_READINESS_WORKER_REQUIRED')
            if isinstance(value,Exception):raise value
            if 'method' not in value:raise ValueError('LSP_UNEXPECTED_RESPONSE_AT_READINESS_BARRIER')
            if 'id' in value:self._server_request(value)
            else:self._notification(value)
    def _server_request(self,value):
        method=value['method'];params=value.get('params',{});result=None
        if method=='workspace/configuration':result=[self.configuration.get(item.get('section'),{}) for item in params['items']]
        elif method=='workspace/applyEdit':result={'applied':False,'failureReason':'Captured inputs are immutable'};self.unsupported.append(method)
        elif method not in ('client/registerCapability','window/workDoneProgress/create'):self.unsupported.append(method)
        self.send(dict(id=value['id'],result=result))
    def close(self):
        if self.closed:return
        self.closed=True
        # Terminate the registered language-server process even on malformed frames/timeouts.
        # The enclosing cgroup/whole-worker supervisor owns descendants and final quiescence.
        if self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)
        self.reader.join(timeout=5)
        if self.reader.is_alive():raise RuntimeError('LSP_DESCENDANT_QUIESCENCE_REQUIRES_WORKER_TERMINATION')
        for stream in (self.process.stdin,self.process.stdout):
            if stream:stream.close()
    def __enter__(self):return self
    def __exit__(self,*failure):self.close()
