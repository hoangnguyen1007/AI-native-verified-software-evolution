import importlib.util
from pathlib import Path
import json
import unittest


def load():
    path = Path(__file__).with_name('hyperv_closing_campaign.py')
    spec = importlib.util.spec_from_file_location('closing_campaign', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BootstrapReadinessTest(unittest.TestCase):
    def test_native_helper_command_is_accepted_by_the_actual_helper_parser(self):
        import hyperv_publication_native
        root=Path('authored-worker')
        command=load().maven_fixture_command('python',root,root/'owner.json',root/'native-request.json')
        parsed=hyperv_publication_native.parse_arguments(command[2:])
        self.assertEqual(root/'native-request.json',parsed.request)
        self.assertEqual(root/'owner.json',parsed.owner)
        self.assertTrue(parsed.preallocated)

    def test_ssh_ready_before_bootstrap_does_not_skip_first_boot_sanitization(self):
        module = load()
        marker = dict(schema='guest-worker-bootstrap-observation:m8.4-v1', attempt='a'*32, work='/work/'+'a'*32)
        responses = iter([(1,b'',b''),(1,b'',b''),(0,json.dumps(marker).encode(),b'')])
        now = [0.0]
        result = module.wait_bootstrap(lambda: next(responses), 'a'*32, marker['work'], 3,
                                       clock=lambda: now[0], pause=lambda seconds: now.__setitem__(0,now[0]+seconds))
        self.assertEqual(marker,result)
        self.assertEqual(2,now[0])

    def test_bootstrap_must_finish_within_the_registered_readiness_policy(self):
        module = load()
        with self.assertRaisesRegex(RuntimeError,'WORKER_BOOTSTRAP_NOT_READY'):
            module.wait_bootstrap(lambda:(1,b'',b''),'a'*32,'/work/'+'a'*32,0,clock=lambda:0,pause=lambda seconds:None)

    def test_a_late_success_cannot_extend_the_readiness_deadline(self):
        marker=dict(schema='guest-worker-bootstrap-observation:m8.4-v1',attempt='a'*32,work='/work/'+'a'*32)
        now=[0]
        def late_reader():
            now[0]=3
            return (0,json.dumps(marker).encode(),b'')
        with self.assertRaisesRegex(RuntimeError,'WORKER_BOOTSTRAP_NOT_READY'):
            load().wait_bootstrap(late_reader,'a'*32,marker['work'],2,clock=lambda:now[0],pause=lambda seconds:None)

    def test_foreign_or_duplicate_bootstrap_observations_are_not_retried_as_readiness(self):
        module = load()
        for raw in [b'{"attempt":"a","attempt":"b"}',
                    json.dumps(dict(schema='guest-worker-bootstrap-observation:m8.4-v1',attempt='b'*32,work='/work/'+'a'*32)).encode()]:
            with self.subTest(raw=raw),self.assertRaises((ValueError,RuntimeError)):
                module.wait_bootstrap(lambda:(0,raw,b''),'a'*32,'/work/'+'a'*32,5,clock=lambda:0,
                                      pause=lambda seconds:self.fail('foreign evidence must not be retried'))


if __name__ == '__main__':
    unittest.main()
