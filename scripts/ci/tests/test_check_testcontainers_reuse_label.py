#!/usr/bin/env python3
"""check-testcontainers-reuse-label.py 的分类规则回归测试。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "check-testcontainers-reuse-label.py"
SPEC = importlib.util.spec_from_file_location("check_testcontainers_reuse_label", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


CORRECT_PREDICATE = """\
orphans=$(docker ps -aq --filter "label=org.testcontainers=true" 2>/dev/null | while read -r cid; do
  if ! docker inspect "$cid" --format '{{json .Config.Labels}}' 2>/dev/null | grep -q "org.testcontainers.hash"; then
    printf '%s\\n' "$cid"
  fi
done)
"""


class TestcontainersReuseLabelTest(unittest.TestCase):

    def test_correct_predicate_passes(self) -> None:
        self.assertEqual(MODULE.violations("scripts/local/x.sh", CORRECT_PREDICATE), [])

    def test_phantom_literal_is_rejected(self) -> None:
        text = CORRECT_PREDICATE.replace("org.testcontainers.hash", "reuse-hash")
        errors = MODULE.violations("scripts/local/x.sh", text)
        self.assertTrue(errors)
        self.assertTrue(any("reuse-hash" in error for error in errors))

    def test_filter_without_hash_label_is_rejected(self) -> None:
        text = 'orphans=$(docker ps -aq --filter "label=org.testcontainers=true")\n'
        errors = MODULE.violations("scripts/local/x.sh", text)
        self.assertEqual(len(errors), 1)
        self.assertIn("org.testcontainers.hash", errors[0])

    def test_unrelated_script_is_ignored(self) -> None:
        self.assertEqual(MODULE.violations("scripts/local/y.sh", "echo hello\n"), [])


if __name__ == "__main__":
    unittest.main()
