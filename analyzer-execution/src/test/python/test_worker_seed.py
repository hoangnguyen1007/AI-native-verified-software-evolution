import importlib.util
import os
from pathlib import Path
import sys
import unittest
import base64

GUEST = Path(os.environ.get('EVO_GUEST_RESOURCE_ROOT', Path(__file__).resolve().parents[2] / 'main/resources/guest'))
PUBLIC = 'ssh-ed25519 ' + base64.b64encode(b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20' + b'\x01' * 32).decode() + ' fixture'


class WorkerSeedTest(unittest.TestCase):
    def load(self, name):
        sys.path.insert(0, str(GUEST))
        try:
            spec = importlib.util.spec_from_file_location('seed_test_' + name, GUEST / (name + '.py'))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module
        finally:
            sys.path.pop(0)

    def test_bootstrap_is_inert_and_rejects_foreign_coerced_or_unbounded_requests(self):
        module = self.load('bootstrap')
        request = dict(schema='guest-worker-bootstrap:m8.4-v1', attempt='a' * 32,
                       controllerKey=PUBLIC, workBytes=1073741824)
        self.assertEqual(request, module.validate(request))
        for change in [dict(schema='future'), dict(attempt='../other'), dict(controllerKey='ssh-ed25519 AAAA\nother'),
                       dict(workBytes=True), dict(workBytes=4096), dict(workBytes=16777217),
                       dict(workBytes=9223372036854775808), dict(extra='ignored')]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                module.validate(request | change)
        with self.assertRaises(ValueError):
            module.unique([('attempt', 'a'), ('attempt', 'b')])

    def test_controller_key_requires_a_complete_ed25519_ssh_wire_record(self):
        module = self.load('bootstrap')
        request = dict(schema='guest-worker-bootstrap:m8.4-v1', attempt='a' * 32,
                       controllerKey=PUBLIC, workBytes=1073741824)
        for key in ['ssh-ed25519 AAAA', 'ssh-ed25519 !!!!', PUBLIC + '\x00',
                    'ssh-ed25519 ' + base64.b64encode(b'wrong-key-type').decode()]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.validate(request | dict(controllerKey=key))

    @unittest.skipIf(os.name == 'nt', 'POSIX ownership and umask fixture')
    def test_root_owned_public_authorized_keys_remain_readable_under_a_private_umask(self):
        import tempfile
        import stat
        module = self.load('bootstrap')
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'public-keys'
            previous = os.umask(0o077)
            try:
                module.write_exclusive(path, 'ssh-ed25519 AAAA fixture\n', mode=0o644)
            finally:
                os.umask(previous)
            self.assertEqual(0o644, stat.S_IMODE(path.stat().st_mode))
            with self.assertRaises(FileExistsError):
                module.write_exclusive(path, 'replacement')

    def test_seed_pins_fresh_host_identity_and_separates_control_from_target(self):
        module = self.load('make_worker_seed')
        config, metadata, network = module.configuration('a' * 32, '00:15:5d:84:30:02', '172.30.84.3/24',
                                                       PUBLIC, '-----BEGIN OPENSSH PRIVATE KEY-----\nfixture',
                                                       PUBLIC, 1073741824)
        self.assertTrue(config['ssh_deletekeys'])
        self.assertFalse(config['ssh_pwauth'])
        self.assertEqual('evo-' + 'a' * 32, metadata['instance-id'])
        self.assertEqual(['172.30.84.3/24'], network['ethernets']['evo']['addresses'])
        users = {u['name']: u for u in config['users']}
        # cloud-init 25.1.4 rejects an empty array here (minItems=1); an empty string is valid.
        self.assertEqual('', users['evobuild']['groups'])
        self.assertNotIn('sudo', users['evobuild'])
        self.assertEqual('/usr/sbin/nologin', users['evobuild']['shell'])
        for entry in config['write_files']:
            self.assertEqual('root:root', entry['owner'])
            self.assertEqual(0, int(entry['permissions'], 8) & 0o077)
        self.assertIn('/root/evo-worker-request.json', config['runcmd'][0][-1])

    def test_seed_cannot_retarget_mac_address_or_reuse_malformed_host_keys(self):
        module = self.load('make_worker_seed')
        for mac, private in [('not-a-mac', '-----BEGIN OPENSSH PRIVATE KEY-----'),
                             ('00:15:5d:84:30:02', 'private-key-not-provided')]:
            with self.subTest(mac=mac), self.assertRaises(ValueError):
                module.configuration('a' * 32, mac, '172.30.84.3/24', 'ssh-ed25519 AAAA', private,
                                     'ssh-ed25519 BBBB', 1073741824)

    def test_seed_preserves_exact_pinned_tool_bytes_across_host_newline_conventions(self):
        from unittest.mock import patch
        module = self.load('make_worker_seed')
        raw = b'#!/usr/bin/python3\r\n# caf\xc3\xa9\r\nprint("exact")\n'
        with patch.object(Path, 'read_bytes', return_value=raw):
            config, _, _ = module.configuration('a' * 32, '00:15:5d:84:30:02', '172.30.84.3/24',
                                               PUBLIC, '-----BEGIN OPENSSH PRIVATE KEY-----\nfixture', PUBLIC, 1073741824)
        for entry in config['write_files']:
            if entry['path'].endswith('.py'):
                with self.subTest(path=entry['path']):
                    delivered = base64.b64decode(entry['content'], validate=True) if entry.get('encoding') == 'b64' else entry['content'].encode('utf-8')
                    self.assertEqual(raw, delivered)


if __name__ == '__main__':
    unittest.main()
