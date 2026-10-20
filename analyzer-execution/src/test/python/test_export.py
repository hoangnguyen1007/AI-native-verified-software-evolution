import base64
import importlib.util
import json
import os
from pathlib import Path
import socket
import tempfile
import unittest


def load():
    configured = os.environ.get('EVO_GUEST_EXPORT_TEST_PATH')
    path = Path(configured) if configured else Path(__file__).resolve().parents[2] / 'main/resources/guest/export.py'
    spec = importlib.util.spec_from_file_location('evo_export', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def policy(**changes):
    return dict(maxRowBytes=4096, maxObjectBytes=1048576, maxCaptureBytes=1048576, **changes)


GUEST = os.name == 'posix' and getattr(os, 'geteuid', lambda: -1)() == 0


class ExportRequestTest(unittest.TestCase):
    def test_strict_request_and_checked_counters(self):
        module = load()
        value = dict(attempt='a' * 32, nonce='b' * 32, work='/work/' + 'a' * 32,
                     maxRowBytes=4096, maxObjectBytes=1, maxCaptureBytes=2)
        self.assertEqual(value, module.decode_request(json.dumps(value).encode()))
        for changes in [dict(maxRowBytes=True), dict(maxObjectBytes=-1), dict(maxCaptureBytes=1 << 63),
                        dict(work='/etc'), dict(attempt='../'), dict(unregistered=True)]:
            with self.assertRaises(ValueError):
                module.decode_request(json.dumps(dict(value, **changes)).encode())
        with self.assertRaises(ValueError):
            module.decode_request(b'{"attempt":"a","attempt":"b"}')
        with self.assertRaises(ValueError):
            module.checked_add((1 << 63) - 1, 1)
        self.assertEqual((1 << 63) - 1, module.checked_add((1 << 63) - 2, 1))


@unittest.skipUnless(GUEST, 'root POSIX guest export conformance')
class GuestExportTest(unittest.TestCase):
    def setup_tree(self, base):
        work = base / 'work'
        exports = base / 'exports'
        work.mkdir()
        exports.mkdir(mode=0o700)
        return work, exports

    def rows(self, destination):
        return [json.loads(line) for line in (destination / 'entries.ndjson').read_bytes().splitlines()]

    def test_raw_names_empty_directory_and_symlink_target_are_preserved(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            work, exports = self.setup_tree(Path(temporary).resolve())
            names = [b'normal.java', b'CON', b'x:y', b'case', b'CASE', '\u00e9'.encode(), 'e\u0301'.encode(), b'\xff.bin']
            for name in names:
                fd = os.open(os.fsencode(work) + b'/' + name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, 'wb') as out:
                    out.write(b'fixture')
            (work / 'empty').mkdir()
            (work / 'escape').symlink_to('/etc/shadow')
            result = module.capture(work, exports / 'one', policy())
            self.assertEqual(10, result['entries'])
            self.assertEqual(56, result['logicalBytes'])
            self.assertTrue(result['captureClosed'])
            rows = self.rows(exports / 'one')
            self.assertEqual(sorted(names + [b'empty', b'escape']), [base64.b64decode(row['path']) for row in rows])
            link = next(row for row in rows if row['kind'] == 'SYMLINK')
            self.assertEqual(b'/etc/shadow', base64.b64decode(link['linkTarget']))
            objects = list((exports / 'one/objects').iterdir())
            self.assertEqual(1, len(objects))
            self.assertEqual(b'fixture', objects[0].read_bytes())
            self.assertEqual('PRODUCER_ASSERTION', result['trust'])

    def test_special_files_are_classified_without_opening_or_blocking(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            work, exports = self.setup_tree(Path(temporary).resolve())
            os.mkfifo(work / 'pipe')
            with socket.socket(socket.AF_UNIX) as connection:
                connection.bind(str(work / 'socket'))
                result = module.capture(work, exports / 'one', policy())
            self.assertEqual(2, result['entries'])
            self.assertEqual(2, result['rejected'])
            self.assertTrue(result['inventoryClosed'])
            self.assertFalse(result['captureClosed'])
            self.assertEqual({'FIFO', 'SOCKET'}, {row['kind'] for row in self.rows(exports / 'one')})
            self.assertEqual([], list((exports / 'one/objects').iterdir()))

    def test_closed_hardlink_group_captures_bytes_but_external_group_stays_pending(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            work, exports = self.setup_tree(base)
            (work / 'a').write_bytes(b'shared')
            os.link(work / 'a', work / 'b')
            complete = module.capture(work, exports / 'one', policy())
            self.assertTrue(complete['captureClosed'])
            rows = self.rows(exports / 'one')
            self.assertEqual(['HARDLINK', 'HARDLINK'], [row['kind'] for row in rows])
            self.assertTrue(all(base64.b64decode(row['linkGroup']) == b'a' for row in rows))
            (base / 'outside').write_bytes(b'outside secret fixture')
            os.link(base / 'outside', work / 'unclosed')
            partial = module.capture(work, exports / 'two', policy())
            self.assertFalse(partial['captureClosed'])
            self.assertEqual(1, partial['pending'])
            row = next(r for r in self.rows(exports / 'two') if base64.b64decode(r['path']) == b'unclosed')
            self.assertEqual('HARDLINK_SCOPE_PROOF_REQUIRED', row['reason'])
            self.assertIsNone(row['content'])

    def test_budget_retains_every_row_and_bigger_worker_can_capture_them(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            work, exports = self.setup_tree(Path(temporary).resolve())
            (work / 'b').write_bytes(b'def')
            (work / 'a').write_bytes(b'abc')
            small = policy()
            small['maxCaptureBytes'] = 3
            partial = module.capture(work, exports / 'small', small)
            self.assertEqual(2, partial['entries'])
            self.assertEqual(6, partial['logicalBytes'])
            self.assertEqual(3, partial['capturedBytes'])
            self.assertEqual(1, partial['pending'])
            self.assertTrue(partial['inventoryClosed'])
            self.assertFalse(partial['captureClosed'])
            first = module.capture(work, exports / 'full', policy())
            again = module.capture(work, exports / 'replay', policy())
            self.assertTrue(first['captureClosed'])
            self.assertEqual(first['manifest'], again['manifest'])
            self.assertEqual(2, len(self.rows(exports / 'full')))

    def test_alias_and_nonce_reuse_are_rejected_and_deep_tree_is_iterative(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            work, exports = self.setup_tree(base)
            alias = base / 'alias'
            alias.symlink_to(work, target_is_directory=True)
            with self.assertRaises(ValueError):
                module.capture(alias, exports / 'alias', policy())
            cursor = work
            for _ in range(60):
                cursor = cursor / 'd'
                cursor.mkdir()
            (cursor / 'leaf').write_bytes(b'deep')
            result = module.capture(work, exports / 'deep', policy())
            self.assertEqual(61, result['entries'])
            before = (exports / 'deep/census.json').read_bytes()
            with self.assertRaises(FileExistsError):
                module.capture(work, exports / 'deep', policy())
            self.assertEqual(before, (exports / 'deep/census.json').read_bytes())

    def test_unreadable_directory_keeps_a_gap_while_independent_files_are_captured(self):
        module = load()
        with tempfile.TemporaryDirectory() as temporary:
            work, exports = self.setup_tree(Path(temporary).resolve())
            (work / 'gone').mkdir()
            (work / 'gone/unknown-child').write_bytes(b'not observed')
            (work / 'kept').write_bytes(b'ok')
            original = module.open_relative
            def unavailable(root_fd, relative, directory=False):
                if relative == b'gone':
                    raise FileNotFoundError('authored directory loss')
                return original(root_fd, relative, directory)
            module.open_relative = unavailable
            result = module.capture(work, exports / 'one', policy())
            self.assertFalse(result['inventoryClosed'])
            self.assertFalse(result['captureClosed'])
            self.assertEqual(2, result['entries'])
            rows = self.rows(exports / 'one')
            self.assertEqual('DIRECTORY_CENSUS_UNAVAILABLE', rows[0]['reason'])
            self.assertEqual('PRESENT', rows[1]['disposition'])
            self.assertEqual(2, rows[1]['content']['size'])


if __name__ == '__main__':
    unittest.main()
