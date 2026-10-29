"""First-boot account policy for existing operator and clean public images."""
from pathlib import Path
import importlib.util
from types import SimpleNamespace
import unittest

FILE=Path(__file__).resolve().parents[2]/'main/resources/guest/bootstrap.py'
spec=importlib.util.spec_from_file_location('source_account_policy',FILE);bootstrap=importlib.util.module_from_spec(spec);spec.loader.exec_module(bootstrap)


class GuestSourceAccountControls(unittest.TestCase):
    def test_account_policy_does_not_change_strict_controller_public_key_validation(self):
        for invalid in ('ssh-ed25519 AAAA','ssh-ed25519 ====','ssh-ed25519 AA','ssh-rsa AAAA'):
            with self.assertRaises(ValueError):bootstrap.validate_public_key(invalid)
    def test_existing_source_accounts_are_locked_and_missing_named_source_users_are_valid(self):
        accounts=[SimpleNamespace(pw_name=name,pw_uid=uid) for name,uid in [('root',0),('daemon',1),('ubuntu',1000),('nguyen',1001),('evoctl',1002),('evobuild',1003),('nobody',65534)]];self.assertEqual(['nguyen','ubuntu'],bootstrap.source_accounts(accounts));self.assertEqual([],bootstrap.source_accounts([a for a in accounts if a.pw_name in ('root','daemon','evoctl','evobuild','nobody')]))


if __name__=='__main__':unittest.main()
