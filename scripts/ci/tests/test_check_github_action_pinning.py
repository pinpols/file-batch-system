import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-github-action-pinning.py"
SPEC = importlib.util.spec_from_file_location("check_github_action_pinning", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class GitHubActionPinningTest(unittest.TestCase):
    def test_accepts_local_and_sha_pinned_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workflow.yml"
            path.write_text(
                "steps:\n"
                "  - uses: ./.github/actions/local\n"
                "  - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7\n",
                encoding="utf-8",
            )
            self.assertEqual([], MODULE.find_unpinned([path]))

    def test_accepts_quoted_reference_and_ignores_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workflow.yml"
            path.write_text(
                "steps:\n"
                "  # uses: actions/checkout@v7\n"
                "  - uses: 'actions/checkout@0123456789abcdef0123456789abcdef01234567' # v7\n",
                encoding="utf-8",
            )
            self.assertEqual([], MODULE.find_unpinned([path]))
    def test_rejects_mutable_version_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workflow.yml"
            path.write_text("steps:\n  - uses: actions/checkout@v7\n", encoding="utf-8")
            violations = MODULE.find_unpinned([path])
            self.assertEqual(1, len(violations))
            self.assertIn("actions/checkout@v7", violations[0])

    def test_rejects_sha_without_version_comment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workflow.yml"
            path.write_text(
                "steps:\n  - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567\n",
                encoding="utf-8",
            )
            violations = MODULE.find_unpinned([path])
            self.assertEqual(1, len(violations))
            self.assertIn("缺少版本注释", violations[0])

if __name__ == "__main__":
    unittest.main()
