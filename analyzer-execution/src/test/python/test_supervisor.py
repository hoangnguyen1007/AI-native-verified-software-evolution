import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'main/resources/hyperv/supervisor.py'


def load():
    spec = importlib.util.spec_from_file_location('controller_supervisor', SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SupervisorTest(unittest.TestCase):
    def test_stop_causes_use_exact_closed_boundaries_and_loss_wins(self):
        m = load()
        self.assertEqual('', m.boundary_reason(9, 10, 10, 10, 10, 10, True, False))
        self.assertEqual('RUNTIME_BUDGET_EXHAUSTED', m.boundary_reason(10, 10, 0, 10, 0, 10, True, False))
        self.assertEqual('OVERLAY_BUDGET_EXHAUSTED', m.boundary_reason(0, 10, 11, 10, 0, 10, True, False))
        self.assertEqual('LOG_BUDGET_EXHAUSTED', m.boundary_reason(0, 10, 0, 10, 11, 10, True, False))
        self.assertEqual('CONTROLLER_PROTOCOL_COMPLETE', m.boundary_reason(0, 10, 0, 10, 0, 10, True, True))
        self.assertEqual('CONTROL_PROCESS_LOST', m.boundary_reason(10, 10, 11, 10, 11, 10, False, True))
        large = 9007199254740993
        self.assertEqual('', m.boundary_reason(large - 1, large, 0, 10, 0, 10, True, False))
        self.assertEqual('RUNTIME_BUDGET_EXHAUSTED', m.boundary_reason(large, large, 0, 10, 0, 10, True, False))

    def test_config_rejects_coercion_duplicate_logs_and_escaped_storage(self):
        m = load()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            config = dict(schema='hyperv-supervisor-config:m8.4-v1', root=str(root), owner=str(root/'owner.json'),
                          powershell=sys.executable, powershellPin='sha256:'+'a'*64, driver=str(root/'driver.ps1'),
                          driverPin='sha256:'+'b'*64, controlPid=os.getpid(), runtimeMillis=100, overlayBytes=1024,
                          logBytes=1024, logs=[str(root/'native.log')], pollMillis=100)
            self.assertEqual(config, m.validate(config))
            for change in [dict(runtimeMillis=True), dict(controlPid=4294967296), dict(runtimeMillis=0),
                           dict(overlayBytes=-1), dict(pollMillis=1001), dict(extra='ignored'),
                           dict(logs=[str(root/'x'),str(root/'x')]), dict(logs=[[]]),
                           dict(logs=[str(root/'..'/'foreign')]), dict(owner=str(root.parent/'foreign'))]:
                with self.subTest(change=change), self.assertRaises((ValueError, TypeError)):
                    m.validate(config | change)

    def test_unregistered_actions_and_duplicate_metadata_cannot_reach_a_host_process(self):
        m = load()
        with self.assertRaises(ValueError):
            m.control({}, {}, "Stop'; arbitrary command")
        with self.assertRaises(ValueError):
            m.unique([('attempt', 'a'), ('attempt', 'b')])

    @unittest.skipUnless(os.name == 'nt', 'Windows kernel process-object control')
    def test_original_process_object_records_exit_without_pid_reopen(self):
        m = load()
        child = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(0.4)'],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        monitor = m.WindowsProcess(child.pid)
        try:
            self.assertTrue(monitor.alive())
            self.assertEqual(0, child.wait(timeout=5))
            self.assertFalse(monitor.alive())
        finally:
            monitor.close()
            if child.poll() is None:
                child.kill()
                child.wait()


if __name__ == '__main__':
    unittest.main()
