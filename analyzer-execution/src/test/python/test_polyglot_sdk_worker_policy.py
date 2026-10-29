"""Registered SDK resource profiles, without provisioning a VM in host tests."""
from pathlib import Path
import sys
import unittest
import tempfile

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'main/resources/polyglot'))
import sdk_worker


class SdkWorkerPolicyControls(unittest.TestCase):
    def test_registered_native_scope_selects_only_its_required_image_runtimes(self):
        self.assertEqual(('python3',),sdk_worker.image_runtime_names(dict(probeScope='EXACT_NATIVE_CONTEXTS',probeNames=['swift','swift-parse-shape','swift-typed-shape'])));self.assertEqual(('java','python3'),sdk_worker.image_runtime_names({}));self.assertEqual(('java','python3'),sdk_worker.image_runtime_names(dict(probeScope='EXACT_NATIVE_CONTEXTS',probeNames=['kotlin'])))
    def test_each_worker_has_a_distinct_private_transport_identity_and_refuses_overwrite(self):
        from cryptography.hazmat.primitives import serialization
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);a=root/'a';b=root/'b';a.mkdir();b.mkdir();key_a,pin_a=sdk_worker.private_controller_identity(a);key_b,pin_b=sdk_worker.private_controller_identity(b);self.assertNotEqual(pin_a,pin_b)
            for key in (key_a,key_b):
                loaded=serialization.load_ssh_private_key(key.read_bytes(),password=None);self.assertEqual(loaded.public_key().public_bytes(serialization.Encoding.OpenSSH,serialization.PublicFormat.OpenSSH)+b'\n',Path(str(key)+'.pub').read_bytes())
            with self.assertRaises(FileExistsError):sdk_worker.private_controller_identity(a)
    def test_small_fixture_work_volume_is_explicitly_registered_and_invalid_budgets_reject(self):
        self.assertEqual(1073741824,sdk_worker.work_bytes(1073741824));self.assertEqual(2147483648,sdk_worker.work_bytes(2147483648))
        for invalid in (True,False,0,1073741823,'1073741824',4294967296):
            with self.assertRaises(ValueError):sdk_worker.work_bytes(invalid)


if __name__=='__main__':unittest.main()
