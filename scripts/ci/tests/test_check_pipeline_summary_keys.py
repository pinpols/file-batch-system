#!/usr/bin/env python3
"""check-pipeline-summary-keys.py 的契约规则回归测试。

重点是三件「编译通过、CI 全绿、运行时静默」的事:

1. R1 —— ``skippedStageCarryForwardKeys()`` 与 ``buildOutputSummary()`` 跨两个书写点靠拼写一致
   协作,漂移即静默丢失水位/计数;
2. R2 —— 同一文件内同一 key 既写字面量又写常量,常量改名时写方不动;
3. R3 —— **跨仓库契约**:后端 ``output_summary`` 写入的计数键集 == batch-console
   ``pipelineStepSummary.ts`` 声明的键集(权威源片段见 FRONTEND_SUMMARY_EXCERPT)。
   另有一条针对**本仓库真实源码**的断言,确保契约在当前树上成立,而不只是夹具里成立。
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-pipeline-summary-keys.py"
SPEC = importlib.util.spec_from_file_location("check_pipeline_summary_keys", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
# dataclass 装饰器在定义 Violation / KeyTable 时依赖 cls.__module__ 能在 sys.modules 中解析。
sys.modules["check_pipeline_summary_keys"] = MODULE
SPEC.loader.exec_module(MODULE)


# 逐字摘自 ../batch-console/src/utils/pipelineStepSummary.ts(StageCountKey / CountKeyFallback 两段声明)。
FRONTEND_SUMMARY_EXCERPT = """\
export const STAGE_COUNT_KEY: Record<string, string> = {
  PARSE: 'parsedCount',
  VALIDATE: 'validatedCount',
  LOAD: 'loadedCount',
  FEEDBACK: 'loadedCount',
  GENERATE: 'recordCount',
  STORE: 'recordCount',
  REGISTER: 'recordCount',
  COMPUTE: 'processedCount',
  COMMIT: 'publishedCount',
}

export const COUNT_KEY_FALLBACK = [
  'loadedCount',
  'recordCount',
  'processedCount',
  'publishedCount',
  'validatedCount',
  'parsedCount',
  'stagedCount',
] as const
"""

TABLE = MODULE.KeyTable(
    by_reference={
        "PipelineRuntimeKeys.HIGH_WATER_MARK_OUT": "highWaterMarkOut",
        "PipelineRuntimeKeys.HIGH_WATER_MARK_IN": "highWaterMarkIn",
        "PipelineRuntimeKeys.JOB_CODE": "jobCode",
        "ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT": "processedCount",
    },
    by_name={
        "HIGH_WATER_MARK_OUT": {"highWaterMarkOut"},
        "PROCESS_PROCESSED_COUNT": {"processedCount"},
    },
    by_value={
        "highWaterMarkOut": {"PipelineRuntimeKeys.HIGH_WATER_MARK_OUT"},
        "highWaterMarkIn": {"PipelineRuntimeKeys.HIGH_WATER_MARK_IN"},
        "jobCode": {"PipelineRuntimeKeys.JOB_CODE"},
        "processedCount": {"ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT"},
    },
)

# 修复前的 PROCESS 写法:回灌声明了 highWaterMarkOut,但 buildOutputSummary 只写了 processedCount。
PREFIX_CARRY_FORWARD = """
public class DemoExecutor extends AbstractStageExecutor<Ctx, Res> {
  private static final Set<String> SKIP_CARRY_FORWARD_KEYS =
      Set.of(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT);

  @Override
  protected Set<String> skippedStageCarryForwardKeys() {
    return SKIP_CARRY_FORWARD_KEYS;
  }

  @Override
  protected Map<String, Object> buildOutputSummary(Ctx context, Res result) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("success", result.success());
    summary.put(ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT, context.get(ATTR));
    return summary;
  }
}
"""

FIXED_CARRY_FORWARD = PREFIX_CARRY_FORWARD.replace(
    '    summary.put("success", result.success());',
    '    summary.put("success", result.success());\n'
    "    summary.put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, context.get(WATERMARK));",
)


def carry_forward_violations(text: str) -> list:
    return MODULE.find_carry_forward_violations(MODULE.strip_comments(text), "Demo.java", TABLE)


def mixing_violations(text: str) -> list:
    return MODULE.find_mixing_violations(MODULE.strip_comments(text), "Demo.java", TABLE)


class StripCommentsTest(unittest.TestCase):
    """注释不是代码:剥离后必须保留长度与行号,且不动字符串字面量。"""

    def test_keeps_length_and_line_numbers(self) -> None:
        text = 'int a = 1; // "highWaterMarkOut"\nint b = 2;\n'
        stripped = MODULE.strip_comments(text)
        self.assertEqual(len(stripped), len(text))
        self.assertEqual(stripped.count("\n"), text.count("\n"))

    def test_double_slash_inside_string_literal_survives(self) -> None:
        text = 'String url = "http://example.test/x"; // trailing\n'
        stripped = MODULE.strip_comments(text)
        self.assertIn("http://example.test/x", stripped)
        self.assertNotIn("trailing", stripped)


class CarryForwardSubsetTest(unittest.TestCase):
    """R1:回灌键必须是 buildOutputSummary 写入键的子集。"""

    def test_carry_forward_key_missing_from_summary_is_flagged(self) -> None:
        rules = {violation.rule for violation in carry_forward_violations(PREFIX_CARRY_FORWARD)}
        self.assertIn(MODULE.CARRY_FORWARD_NOT_IN_SUMMARY, rules)
        messages = " ".join(v.message for v in carry_forward_violations(PREFIX_CARRY_FORWARD))
        self.assertIn("highWaterMarkOut", messages)

    def test_summary_writing_both_keys_passes(self) -> None:
        self.assertEqual(carry_forward_violations(FIXED_CARRY_FORWARD), [])

    def test_unparseable_summary_fails_loudly(self) -> None:
        """解析失效必须报 PARSE_FAILED,禁止以 0 命中充当通过。"""
        rules = {
            violation.rule
            for violation in carry_forward_violations(
                "public class X extends AbstractStageExecutor<A, B> { }"
            )
        }
        self.assertEqual(rules, {MODULE.PARSE_FAILED})

    def test_inline_set_of_without_field_is_supported(self) -> None:
        fixture = """
public class DemoExecutor extends AbstractStageExecutor<Ctx, Res> {
  @Override
  protected Set<String> skippedStageCarryForwardKeys() {
    return Set.of(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT);
  }

  @Override
  protected Map<String, Object> buildOutputSummary(Ctx context, Res result) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, context.get(WATERMARK));
    return summary;
  }
}
"""
        self.assertEqual(carry_forward_violations(fixture), [])


class LiteralConstantMixingTest(unittest.TestCase):
    """R2:同一文件内同一 key 不得既写字面量又写常量。"""

    def test_read_constant_write_literal_is_flagged(self) -> None:
        fixture = (
            '  summary.put(\n      "highWaterMarkIn",'
            " context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));\n"
        )
        rules = {violation.rule for violation in mixing_violations(fixture)}
        self.assertEqual(rules, {MODULE.KEY_LITERAL_AND_CONSTANT})

    def test_read_and_write_both_constants_pass(self) -> None:
        fixture = (
            "  summary.put(\n      PipelineRuntimeKeys.HIGH_WATER_MARK_IN,\n"
            "      context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));\n"
        )
        self.assertEqual(mixing_violations(fixture), [])

    def test_l4_literal_without_constant_reference_is_not_flagged(self) -> None:
        """L4 展示键(batchKey / stepCode)保持字面量是对的,不能制造噪音。"""
        fixture = (
            '  summary.put("batchKey", context.getBatchKey());\n'
            '  summary.put("stepCode", step.stepCode());\n'
            '  summary.put("implCode", step.implCode());\n'
        )
        self.assertEqual(mixing_violations(fixture), [])

    def test_constant_declaration_itself_is_not_flagged(self) -> None:
        """`static final String X = "x";` 是声明,不是另写一份字面量。"""
        fixture = (
            "public class QuartzLaunchJob implements Job {\n"
            '  public static final String JOB_CODE = "jobCode";\n'
            "  String read(Map<String, Object> attrs) {\n"
            "    return String.valueOf(attrs.get(PipelineRuntimeKeys.JOB_CODE));\n"
            "  }\n"
            "}\n"
        )
        self.assertEqual(mixing_violations(fixture), [])

    def test_literal_only_in_comment_is_not_flagged(self) -> None:
        fixture = (
            '// 曾经写成 "highWaterMarkIn",现已收敛到常量\n'
            "  summary.put(\n      PipelineRuntimeKeys.HIGH_WATER_MARK_IN,\n"
            "      context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));\n"
        )
        self.assertEqual(mixing_violations(fixture), [])


class FrontendCountKeyContractTest(unittest.TestCase):
    """R3:output_summary 的跨仓库计数键契约。"""

    def test_frontend_excerpt_declares_exactly_the_mirror(self) -> None:
        """夹具逐字来自 batch-console;解析结果必须与脚本镜像完全一致。"""
        parsed = MODULE.parse_frontend_keys(FRONTEND_SUMMARY_EXCERPT)
        self.assertEqual(parsed, set(MODULE.FRONTEND_COUNT_KEYS))

    def test_backend_dropping_a_key_is_flagged(self) -> None:
        """后端不再写入前端读取的计数键 → UI 静默显示「—」,必须拦截。"""
        missing = set(MODULE.FRONTEND_COUNT_KEYS) - {"publishedCount"}
        violations = MODULE.find_contract_violations({"a/Executor.java": missing})
        self.assertTrue(violations)
        self.assertTrue(any("publishedCount" in violation.message for violation in violations))

    def test_backend_adding_an_undeclared_count_key_is_flagged(self) -> None:
        violations = MODULE.find_contract_violations(
            {"a/Executor.java": set(MODULE.FRONTEND_COUNT_KEYS) | {"newlyAddedCount"}}
        )
        self.assertTrue(violations)
        self.assertTrue(any("newlyAddedCount" in violation.message for violation in violations))

    def test_non_count_keys_do_not_participate(self) -> None:
        """fileId / batchKey / highWaterMarkOut 等非 *Count 键不参与计数键判定。"""
        violations = MODULE.find_contract_violations(
            {
                "a/Executor.java": set(MODULE.FRONTEND_COUNT_KEYS)
                | {"fileId", "batchKey", "highWaterMarkOut", "objectName"}
            }
        )
        self.assertEqual(violations, [])


class RealRepositoryContractTest(unittest.TestCase):
    """契约必须在当前树上成立,而不只是在夹具里成立。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.violations, cls.notes = MODULE.scan()

    def test_real_repo_has_no_contract_drift(self) -> None:
        self.assertEqual(
            [violation.render() for violation in self.violations],
            [],
            "pipeline 摘要键契约漂移;运行 python3 scripts/ci/check-pipeline-summary-keys.py 查看",
        )

    def test_real_repo_executors_cover_every_frontend_count_key(self) -> None:
        """后端四个 executor 实际写入的 *Count 键必须覆盖前端声明的全部计数键。"""
        sources = MODULE.java_sources()
        table = MODULE.build_key_table(sources)
        found = 0
        backend: set[str] = set()
        for path in sources:
            text = MODULE.strip_comments(path.read_text(encoding="utf-8"))
            if not MODULE.EXECUTOR_MARKER.search(text):
                continue
            found += 1
            backend |= MODULE.count_keys(
                set(MODULE.keys_written_by(text, table, method="buildOutputSummary") or [])
            )
        self.assertEqual(found, MODULE.REQUIRED_EXECUTORS)
        self.assertEqual(backend, set(MODULE.FRONTEND_COUNT_KEYS))


if __name__ == "__main__":
    unittest.main()
