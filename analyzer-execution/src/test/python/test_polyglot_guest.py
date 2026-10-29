import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot/guest_semantic_runner.py'
spec=importlib.util.spec_from_file_location('semantic_guest_controls',SOURCE)
guest=importlib.util.module_from_spec(spec);spec.loader.exec_module(guest)


class SemanticGuestControls(unittest.TestCase):
    def test_only_registered_context_and_exact_attempt_are_accepted(self):
        registration=dict(attempt='a'*32,contexts={'typescript':{'input':'captured'}})
        request=dict(schema='polyglot-guest-request:m8.4-v1',attempt='a'*32,context='typescript')
        self.assertEqual({'input':'captured'},guest.validate_request(request,registration))
        for changed in [dict(request,attempt='b'*32),dict(request,context='shell'),dict(request,argv=['cmd']),dict(request,schema='other')]:
            with self.assertRaises(ValueError):guest.validate_request(changed,registration)

    def test_tool_and_input_bytes_are_checked_before_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'tool';file.write_bytes(b'actual pinned bytes')
            pin=dict(size=file.stat().st_size,digest=guest.digest(file))
            self.assertEqual(file,guest.require_file(file,pin))
            file.write_bytes(b'changed')
            with self.assertRaises(ValueError):guest.require_file(file,pin)

    def test_duplicate_registration_fields_fail(self):
        with self.assertRaises(ValueError):guest.unique([('tool',1),('tool',2)])

    def test_digest_runs_on_pre311_guest_python_without_file_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'input';file.write_bytes(b'abc')
            with patch.object(guest.hashlib,'file_digest',side_effect=AssertionError('not available on Python 3.10')):
                self.assertEqual('sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',guest.digest(file))


if __name__=='__main__':unittest.main()
