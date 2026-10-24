# 用途：验证脚本和 CI 索引的新增、改名与删除漂移。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_script_governance.py。
"""验证脚本和 CI 索引的新增、改名与删除漂移。"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "check_script_governance", Path(__file__).resolve().parents[1] / "check-script-governance.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ScriptAndWorkflowIndexTest(unittest.TestCase):
    def check_tree(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, content in files.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
            return MODULE.check_indexes(root, {Path(name) for name in files})

    def test_plain_script_name_is_not_registration(self) -> None:
        errors = self.check_tree({"scripts/README.md": "tool.py：检查入口", "scripts/tool.py": ""})
        self.assertTrue(any("tool.py" in error for error in errors))

    def test_link_without_purpose_is_incomplete(self) -> None:
        errors = self.check_tree({"scripts/README.md": "[工具](tool.py)", "scripts/tool.py": ""})
        self.assertTrue(any("tool.py" in error for error in errors))

    def test_deleted_script_leaves_stale_link(self) -> None:
        errors = self.check_tree({"scripts/README.md": "[旧工具](gone.py)：诊断工具"})
        self.assertTrue(any("gone.py" in error for error in errors))

    def test_nested_sql_is_registered_in_owning_index(self) -> None:
        errors = self.check_tree({
            "scripts/README.md": "[运维](ops/README.md)：运维入口",
            "scripts/ops/README.md": "[探针](sql/probe.sql)：只读数据库探针",
            "scripts/ops/sql/probe.sql": "select 1;",
        })
        self.assertEqual([], errors)

    def test_unlisted_sql_is_rejected_without_requiring_header_changes(self) -> None:
        errors = self.check_tree({
            "scripts/README.md": "[运维](ops/README.md)：运维入口",
            "scripts/ops/README.md": "# 运维",
            "scripts/ops/sql/probe.sql": "select 1;",
        })
        self.assertTrue(any("probe.sql" in error for error in errors))

    def test_child_directory_requires_actual_jump(self) -> None:
        errors = self.check_tree({
            "scripts/README.md": "ops/：运维入口",
            "scripts/ops/README.md": "[工具](tool.sh)：巡检入口",
            "scripts/ops/tool.sh": "",
        })
        self.assertTrue(any("ops/README.md" in error for error in errors))

    def test_workflow_and_composite_action_require_registration(self) -> None:
        errors = self.check_tree({
            ".github/workflows/README.md": "# 工作流",
            ".github/workflows/custom.yaml": "name: custom",
            ".github/actions/README.md": "# 共享动作",
            ".github/actions/setup/action.yml": "name: setup",
        })
        self.assertTrue(any("custom.yaml" in error for error in errors))
        self.assertTrue(any("setup/action.yml" in error for error in errors))

    def test_workflow_purpose_and_source_link_are_sufficient(self) -> None:
        errors = self.check_tree({
            ".github/workflows/README.md": "[检查](custom.yml)：PR 静态检查",
            ".github/workflows/custom.yml": "name: custom",
        })
        self.assertEqual([], errors)

    def test_versionable_inventory_excludes_ignored_outputs(self) -> None:
        errors = self.check_tree({
            "scripts/README.md": "# 脚本",
            "scripts/tests/test_tool.py": "",
            "scripts/__pycache__/tool.pyc": "",
        })
        self.assertEqual([], errors)

    def test_load_test_entrypoint_cannot_be_unlisted(self) -> None:
        errors = self.check_tree({
            "load-tests/README.md": "# 压测模块",
            "load-tests/scripts/run.sh": "",
        })
        self.assertTrue(any("load-tests/scripts/run.sh" in error for error in errors))

    def test_link_in_code_example_does_not_count_as_navigation(self) -> None:
        errors = self.check_tree({
            "scripts/README.md": "```md\n[工具](tool.py)：只是示例\n```",
            "scripts/tool.py": "",
        })
        self.assertTrue(any("tool.py" in error for error in errors))


class ScriptHeaderTest(unittest.TestCase):
    def check_header(self, content: str, path: str = "scripts/tool.py") -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            return MODULE.check_headers(root, {Path(path)})

    def test_missing_usage_is_rejected(self) -> None:
        self.assertTrue(self.check_header("# 用途：核对文档链接的有效性。\nprint(1)\n"))

    def test_fields_inside_body_do_not_replace_header(self) -> None:
        self.assertTrue(self.check_header("print(1)\n# 用途：检查本地环境。\n# 用法：python3 scripts/tool.py\n"))

    def test_placeholder_is_rejected(self) -> None:
        self.assertTrue(self.check_header("# 用途：TODO 待补充说明\n# 用法：python3 scripts/tool.py\n"))

    def test_shebang_encoding_and_future_import_are_preserved(self) -> None:
        self.assertEqual([], self.check_header(
            "#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n"
            "# 用途：检查本地环境的配置来源。\n# 用法：python3 scripts/tool.py\n"
            "from __future__ import annotations\n"
        ))

    def test_sourced_library_can_document_import_instead_of_execution(self) -> None:
        self.assertEqual([], self.check_header(
            "#!/usr/bin/env bash\n# 用途：提供环境读取公共函数。\n"
            "# 用法：由入口 source scripts/lib/env.sh 后调用公共函数。\n",
            "scripts/lib/env.sh",
        ))

    def test_sql_and_flyway_are_outside_header_policy(self) -> None:
        for path in ("scripts/db/probe.sql", "db/migration/V1__init.sql"):
            self.assertEqual([], self.check_header("select 1;\n", path))

    def test_python_docstring_and_inline_comment_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = Path("scripts/tool.py")
            (root / path).parent.mkdir()
            (root / path).write_text(
                '\"\"\"Return the results for each candidate.\"\"\"\n'
                'value = 1  # Keep this value because the parser needs it.\n'
            )
            errors = MODULE.check_script_comments(root, {path})
            self.assertEqual(2, len(errors))

    def test_code_strings_and_tool_directives_are_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = Path("scripts/tool.py")
            (root / path).parent.mkdir()
            (root / path).write_text(
                '\"\"\"提供工具入口。\"\"\"\n'
                '# noqa: E501\nvalue = "Keep this protocol string unchanged."\n'
            )
            self.assertEqual([], MODULE.check_script_comments(root, {path}))


if __name__ == "__main__":
    unittest.main()
