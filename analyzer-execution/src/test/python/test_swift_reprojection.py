"""Container/lineage controls on authored DTOs; no native acceptance claim."""
import base64
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[2]/'main/resources/polyglot';sys.path.insert(0,str(BASE))
import test_swift_captured as fixtures
import reproject_swift_capture as projector
from native_protocol import canonical


class SwiftReprojectionControls(unittest.TestCase):
    def fixture(self,directory):
        origin=directory/'native';origin.mkdir();file,request,records=fixtures.CapturedSwiftControls().fixture(origin)
        root=directory/'worker';root.mkdir();capture=root/'capture';capture.mkdir();objects=capture/'objects';objects.mkdir()
        def save(raw):
            handle=projector.handle(raw);(objects/handle['digest'][7:]).write_bytes(raw);return handle
        rows={}
        def entry(path,raw):rows[path]=save(raw);return rows[path]
        prefix='native/swift-positive/'
        entry(prefix+'prepared-request.json',file.read_bytes());old=entry(prefix+'capture.ndjson',b'original projection stays unchanged\n')
        for ordinal,path in enumerate(sorted(records['syntax'])):entry(prefix+'state/'+str(ordinal)+'-syntax.json',(origin/'objects'/records['syntax'][path]['digest'][7:]).read_bytes())
        for key,path in [('ast','state/swift-ast.stdout'),('stderr','state/swift-ast.stderr'),('version','../../probes/swift/stdout')]:
            entry('probes/swift/stdout' if key=='version' else prefix+path,(origin/'objects'/records[key]['digest'][7:]).read_bytes())
        attempt='a'*32;owner=dict(attempt=attempt,vmId='vm',fence='fence',launch='launch')
        for name,value in [('owner-'+attempt+'.json',owner),('termination-result.json',dict(owner,state='Off')),('export-request.json',dict(request=dict(attempt=attempt))),('native-campaign-result.json',dict(results={'swift-positive':dict(exit=0,output=old)})),('guest-registration.json',dict(runtime={}))]:projector.write(root/name,value)
        campaign=dict(contexts={'swift-positive':dict(request=request)});pin=projector.write(root/'campaign.json',campaign);projector.write(root/'profile.json',dict(campaign=pin,controls={},sdk=dict(manifest=dict(digest='sha256:'+'4'*64,size=1))))
        manifest=b''.join(canonical(dict(path=base64.b64encode(path.encode()).decode(),kind='FILE',size=handle['size'],mode=292,blocks=0,links=1,content=handle,linkTarget=None,linkGroup=None,disposition='PRESENT',reason='')).encode()+b'\n' for path,handle in sorted(rows.items()))
        (capture/'entries.ndjson').write_bytes(manifest);scope=b'{}';(capture/'scope.json').write_bytes(scope)
        collector=dict(schema='guest-output-census:m8.4-v2',inventoryClosed=True,captureClosed=True,pending=0,rejected=0,entries=len(rows),logicalBytes=sum(h['size'] for h in rows.values()),capturedBytes=sum(h['size'] for h in rows.values()),manifest=projector.handle(manifest),scope=projector.handle(scope))
        collector_pin=projector.write(capture/'collector-result.json',collector)
        return root,collector_pin,rows
    def test_reprojection_never_invokes_tools_never_changes_original_and_replays_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            directory=Path(directory);root,pin,rows=self.fixture(directory);original={p:p.read_bytes() for p in root.rglob('*') if p.is_file()};outputs=[]
            for index in range(2):
                destination=directory/('derived-'+str(index))
                with patch.object(fixtures.swift_engine.subprocess,'run',side_effect=AssertionError('No native execution during data reprojection')):report=projector.reproject(root,destination,pin)
                self.assertFalse(report['qualified']);self.assertEqual('CAPTURED_NATIVE_METADATA_REPROJECTION',report['mode']);outputs.append(((destination/'swift-positive/capture.ndjson').read_bytes(),report['contexts']['swift-positive']['resolution']))
                with self.assertRaisesRegex(ValueError,'PROJECTION_DESTINATION_MUST_BE_NEW'):projector.reproject(root,destination,pin)
            self.assertEqual(outputs[0],outputs[1]);self.assertEqual(original,{p:p.read_bytes() for p in root.rglob('*') if p.is_file()})
    def test_changed_collector_or_native_object_is_rejected_before_output_creation(self):
        for corruption in ('collector','native'):
            with self.subTest(corruption=corruption),tempfile.TemporaryDirectory() as directory:
                directory=Path(directory);root,pin,rows=self.fixture(directory);destination=directory/'derived'
                path=root/'capture/collector-result.json' if corruption=='collector' else root/'capture/objects'/rows['probes/swift/stdout']['digest'][7:];path.write_bytes(b'changed')
                with self.assertRaises(ValueError):projector.reproject(root,destination,pin)
                self.assertFalse(destination.exists())


if __name__=='__main__':unittest.main()
