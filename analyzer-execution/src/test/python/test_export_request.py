import unittest
from hyperv_export_fixture import export_request, unique, account_object


class ExportRequestTest(unittest.TestCase):
    def test_reference_accounting_preserves_zero_and_exact_budget(self):
        handle = dict(digest='sha256:' + 'a' * 64, size=5)
        self.assertEqual(10, account_object(5, handle, 5, 10))
        self.assertEqual(0, account_object(0, dict(handle, size=0), 0, 0))

    def test_bad_objects_cannot_bypass_per_object_or_aggregate_transport_budget(self):
        handle = dict(digest='sha256:' + 'a' * 64, size=5)
        for used, entry, one, total in [(8, handle, 5, 10), (0, handle, 4, 10),
                                        (0, dict(handle, size=True), 10, 10),
                                        (0, dict(handle, size=-1), 10, 10),
                                        (0, dict(handle, digest='sha256:' + 'A' * 64), 10, 10),
                                        (0, dict(handle, extra='ignored'), 10, 10)]:
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                account_object(used, entry, one, total)
    def test_exact_controller_scope_and_budget_are_preserved(self):
        attempt = 'a' * 32
        request = export_request(attempt)
        request['nonce'] = 'b' * 32
        self.assertEqual(request, export_request(attempt, request))
        self.assertIsNot(request, export_request(attempt, request))

    def test_foreign_nonce_path_fields_and_numeric_coercions_are_rejected(self):
        attempt = 'a' * 32
        request = export_request(attempt)
        for key, value in [('attempt', 'b' * 32), ('nonce', 'B' * 32), ('work', '/work/../other'),
                           ('maxRowBytes', True), ('maxObjectBytes', 1.5), ('maxCaptureBytes', -1),
                           ('maxCaptureBytes', 9223372036854775808), ('maxRowBytes', 1048577)]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                export_request(attempt, dict(request, **{key: value}))
        with self.assertRaises(ValueError):
            export_request(attempt, dict(request, command='ignored'))
        with self.assertRaises(ValueError):
            export_request(attempt, list(request.items()))
        with self.assertRaises(ValueError):
            unique([('nonce', 'a'), ('nonce', 'b')])


if __name__ == '__main__':
    unittest.main()
