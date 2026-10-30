# 用途：文本块边界守卫：同一行关闭与重新打开不得错位。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_java_text_block_style.py。
"""文本块边界守卫：同一行关闭与重新打开不得错位。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "text_block_style", Path(__file__).resolve().parents[1] / "check-java-text-block-style.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TextBlockStyleTest(unittest.TestCase):
    def scan(self, source):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            file = root / "Example.java"
            file.write_text(source, encoding="utf-8")
            with patch.object(MODULE, "ROOT", root):
                return MODULE.scan_file(file)

    def test_accepts_ternary_close_and_open_on_same_line(self):
        source = '''class Example {
  String sql = first ? """
      select 1
      """ : """
      select 2
      """;
  String next = """
      select 3
      """;
}'''
        self.assertEqual(self.scan(source), [])

    def test_rejects_content_before_real_closing_delimiter(self):
        source = '''class Example {
  String sql = """
      select 1""";
}'''
        self.assertEqual(len(self.scan(source)), 1)

    def test_keeps_rejecting_after_ternary_blocks(self):
        source = '''class Example {
  String sql = first ? """
      select 1
      """ : """
      select 2
      """;
  String bad = """
      select 3""";
}'''
        self.assertEqual(len(self.scan(source)), 1)

    def test_ignores_comment_delimiters(self):
        source = '''class Example {
  // """
  /* """ */
  String sql = """
      select 1
      """;
}'''
        self.assertEqual(self.scan(source), [])

    def test_ignores_escaped_quotes_inside_text_block(self):
        source = r'''class Example {
  String sql = """
      select '\"\"\"'
      """;
}'''
        self.assertEqual(self.scan(source), [])


if __name__ == "__main__":
    unittest.main()
