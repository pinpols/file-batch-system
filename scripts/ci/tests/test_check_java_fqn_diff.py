#!/usr/bin/env python3
# 用途：验证新增 Java 全限定类型名检查的代码与字符串边界。
# 用法：python3 -m unittest scripts/ci/tests/test_check_java_fqn_diff.py。
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'check-java-fqn-diff.py'
spec = importlib.util.spec_from_file_location('check_java_fqn_diff', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FqnDiffTest(unittest.TestCase):
    def test_code_type_is_rejected_and_annotation_string_is_allowed(self):
        source = '@ResourceLock("java.util.Locale.default")\njava.util.List<String> values;\n'
        lines = ['Example.java:1:' + source.splitlines()[0], 'Example.java:2:' + source.splitlines()[1]]
        self.assertEqual(module.findings(lines, lambda _: source), [lines[1]])

    def test_comments_text_blocks_and_imports_are_ignored(self):
        source = 'import java.util.List;\nString x = """\njava.util.List text\n""";\n// java.util.List\n'
        lines = [f'Example.java:{i}:{line}' for i, line in enumerate(source.splitlines(), 1)]
        self.assertEqual(module.findings(lines, lambda _: source), [])


if __name__ == '__main__':
    unittest.main()
