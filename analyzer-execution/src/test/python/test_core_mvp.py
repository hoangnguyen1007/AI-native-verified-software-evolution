import importlib.util
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]/'main/resources/mvp'

class CoreMvpTest(unittest.TestCase):
    def load(self):
        sys.path.insert(0,str(ROOT))
        try:
            spec=importlib.util.spec_from_file_location('core_mvp_launcher',ROOT/'launch.py')
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
        finally:sys.path.remove(str(ROOT))
    def test_launcher_pipeline_order_includes_termination(self):
        module=self.load();events=[]
        module.sequence(*(lambda name=name:events.append(name) for name in ('prepare','supervise','start','build','capture','stop')))
        self.assertEqual(['prepare','supervise','start','build','capture','stop'],events)
    def test_build_failure_stops_without_publishing_a_capture(self):
        module=self.load();events=[]
        def fail():events.append('build');raise RuntimeError('BUILD_FAILED')
        with self.assertRaisesRegex(RuntimeError,'BUILD_FAILED'):
            module.sequence(lambda:events.append('prepare'),lambda:events.append('supervise'),lambda:events.append('start'),fail,lambda:events.append('capture'),lambda:events.append('stop'))
        self.assertEqual(['prepare','supervise','start','build','stop'],events)
    def test_prepare_failure_also_attempts_reconciliation(self):
        module=self.load();events=[]
        def fail():raise RuntimeError('PREPARE_FAILED')
        with self.assertRaisesRegex(RuntimeError,'PREPARE_FAILED'):
            module.sequence(fail,lambda:events.append('supervise'),lambda:events.append('start'),lambda:events.append('build'),lambda:events.append('capture'),lambda:events.append('stop'))
        self.assertEqual(['stop'],events)

if __name__=='__main__':unittest.main()
