import io
import unittest
import zipfile

from freeze_l2_source_snapshot import scan_archive


def archive(rows):
    captured = io.BytesIO()
    with zipfile.ZipFile(captured, "w") as output:
        for path, content in rows:
            output.writestr(path, content)
    captured.seek(0)
    return captured


class SourceSnapshotFreezeTest(unittest.TestCase):
    def test_same_entries_have_same_tree_digest_in_any_archive_order(self):
        rows = [("repo-revision/A.java", b"class A {}\n"),
                ("repo-revision/LICENSE", b"MIT\n")]
        left = scan_archive(archive(rows), "example/repo", "a" * 40, 10, 100, 100)
        right = scan_archive(archive(rows[::-1]), "example/repo", "a" * 40, 10, 100, 100)
        self.assertEqual(left["tree_sha256"], right["tree_sha256"])
        self.assertEqual(2, left["file_count"])

    def test_path_escape_and_duplicate_are_rejected(self):
        for rows in ([ ("repo-revision/../outside", b"x") ],
                     [ ("repo-revision/A.java", b"a"),
                       ("repo-revision/A.java", b"b") ]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                scan_archive(archive(rows), "example/repo", "a" * 40, 10, 100, 100)

    def test_expansion_budget_is_enforced_before_result(self):
        with self.assertRaises(ValueError):
            scan_archive(archive([("repo-revision/A.java", b"12345")]),
                         "example/repo", "a" * 40, 10, 4, 100)

    def test_entry_count_is_rejected_before_archive_iteration(self):
        with self.assertRaises(ValueError):
            scan_archive(archive([("repo-revision/A.java", b"a"),
                                  ("repo-revision/B.java", b"b")]),
                         "example/repo", "a" * 40, 1, 100, 100)


if __name__ == "__main__":
    unittest.main()
