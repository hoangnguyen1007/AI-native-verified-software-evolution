import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot/authored_worker.py'
spec=importlib.util.spec_from_file_location('authored_polyglot_worker',SOURCE)
worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)


class AuthoredWorkerControls(unittest.TestCase):
    def test_architecture_cases_are_sealed_as_data_before_native_execution(self):
        workspace=Path(__file__).resolve().parents[4];compiler=workspace/'.evo/tools/typescript-5.9.3/package/lib/typescript.js';cases=Path(__file__).resolve().parents[1]/'resources/polyglot/typescript-architecture-cases.json'
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);contexts=worker.fixture_inputs(root,compiler,cases);self.assertEqual({'typescript','typescript-replay','javascript','missing-import','typescript-architecture','javascript-architecture'},set(contexts));self.assertEqual(json.loads(cases.read_text(encoding='utf-8')),worker.read(root/'qualification-cases.json'))
            malformed=json.loads(cases.read_text(encoding='utf-8'));malformed['cases']['typescript-architecture']['command']='arbitrary-target-hook';bad=root/'bad.json';bad.write_text(json.dumps(malformed))
            with self.assertRaisesRegex(ValueError,'CASE_FIELDS'):worker.fixture_inputs(root,compiler,bad)
    def test_template_requires_registered_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'template';root.mkdir();catalog=Path(directory)/'registrations';catalog.mkdir()
            attempt='01234567-0123-4123-8123-0123456789ab'
            (root/'setup.json').write_text(json.dumps(dict(attempt=attempt)))
            for name in worker.TEMPLATE_FILES[1:]:(root/name).write_bytes(b'{}')
            record=dict(schema='core-mvp-registration:m8.4-v1',attempt=attempt,root=str(root),files={n:worker.digest(root/n) for n in worker.TEMPLATE_FILES})
            (catalog/(attempt+'.json')).write_text(json.dumps(record))
            worker.validate_template(root,catalog)
            (root/'profile.json').write_bytes(b'{"changed":true}')
            with self.assertRaises(ValueError):worker.validate_template(root,catalog)

    def test_upload_freezes_and_validates_before_disclosure(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'source';file.write_bytes(b'pinned')
            pin=worker.handle(file)
            with worker.frozen(file,pin) as captured:
                file.write_bytes(b'changed after snapshot')
                self.assertEqual(b'pinned',captured.read())
            file.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                with worker.frozen(file,pin):self.fail('changed bytes disclosed')

    def test_register_has_its_own_scope_and_no_general_qualification(self):
        value=worker.fixture_profile({'schema':'legacy','acceptanceScope':'CORE_MVP','approvedBy':'HUMAN','pipeline':{},'cacheInputs':{},'qualified':True})
        self.assertEqual('POLYGLOT_AUTHORED_FIXTURE',value['acceptanceScope'])
        self.assertFalse(value['qualified'])
        for name in ('approvedBy','pipeline','cacheInputs'):self.assertNotIn(name,value)

    def test_javascript_context_matches_the_neutral_request_frame(self):
        source='/** @param {number} value */\nexport function twice(value) { return value*2; }\nexport const answer=twice(4);\n'
        self.assertEqual('sha256:8e207a51bab33d0c5511469e83327308baaad59f1f2804d6a6dd7861ab148bf2',worker.fixture_context(source,'javascript','5.9.3')['scope'])

    def test_started_or_terminal_attempt_never_relaunches(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);worker.require_fresh_attempt(root)
            (root/'polyglot-launch-intent.json').write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError,'RECONCILIATION_REQUIRED'):worker.require_fresh_attempt(root)

    def test_control_sources_are_retained_as_exact_registered_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);pins=worker.snapshot_controls(root)
            self.assertEqual(set(worker.CONTROLS),set(pins))
            for name,pin in pins.items():self.assertEqual(pin,worker.digest(root/'control-sources'/name))


if __name__=='__main__':unittest.main()
