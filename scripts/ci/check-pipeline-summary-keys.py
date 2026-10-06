#!/usr/bin/env python3
# scripts/ci/check-pipeline-summary-keys.py
#
# Pipeline stage 摘要键(step_run.output_summary)与节点产出键(nodeOutputs)的契约守护。
#
# 背景:同一个语义 key 在仓库里以不同强度存在,治理目标不是「全部常量化」,而是
# **识别契约边界**——判断轴只有一句话:「这个 key 的名字,会不会被当前这个类之外的东西按字符串读?」
#
#   L4 纯展示(stepCode / stage / implCode / tenantId / workerId / success / code / message)
#      只被本类的日志与审计消费 → 保持字面量,常量化反而制造噪音,本守护不碰。
#   L1 契约 key(highWaterMarkOut / processedCount / stagedCount / publishedCount / fileId / recordCount …)
#      被 *本类之外的* 组件按字符串读:
#        - AbstractStageExecutor#carryForwardSkippedStageOutputs 按 key 名从上次 SUCCESS 的
#          output_summary 里 get(key) 再 putIfAbsent 回灌 attributes(阶段级续跑);
#        - ProcessStepExecutionAdapter 把它复制进 nodeOutputs,供 workflow DSL
#          ($.nodes.<X>.output.<key>)与 CountContinuityOutboxService 消费;
#        - batch-console 前端 pipelineStepSummary.ts 的 STAGE_COUNT_KEY / COUNT_KEY_FALLBACK
#          直接从 output_summary 里按名字取数。
#      → 必须收敛到 *RuntimeKeys / NodeOutputKeys 常量。
#
# 三类断链的共性是「编译通过、CI 全绿、运行时静默」,因此本守护按可判定性覆盖:
#
#   R1 CARRY_FORWARD_NOT_IN_SUMMARY
#      AbstractStageExecutor 子类的 skippedStageCarryForwardKeys() 必须是 buildOutputSummary()
#      写入键集的子集。二者靠「拼写一致」协作且跨两个独立书写点:一旦漂移,回灌取不到值 →
#      水位/计数静默丢失 → report 保留旧水位 → 下周期 INCREMENTAL 重读重发(见
#      AbstractStageExecutor 自述的「静默少发布」路径)。这是可完全静态判定、后果最重的一条。
#
#   R2 KEY_LITERAL_AND_CONSTANT
#      同一生产 Java 文件内,某个已常量化的 key 不得「读用常量、写用字面量」(或反之)。
#      典型:summary.put("highWaterMarkIn", context.get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN))
#      ——常量改名时读方跟着变、写方不动,summary 就悄悄换了个字段名。
#      判定要求同文件**同时**存在常量引用与字面量,因此只作用于「已经能引用该常量」的文件:
#      确实无法依赖该常量模块的调用方(如 batch-orchestrator 的 workflow DSL 契约表)零误报,
#      也不需要白名单;L4 展示键(如 batchKey)在没有常量引用的文件里同样不被波及。
#
#   R3 FRONTEND_COUNT_KEY_MISMATCH / FRONTEND_MIRROR_DRIFT
#      output_summary 是**跨仓库契约**:batch-console 的 src/utils/pipelineStepSummary.ts 硬编码了
#      计数键名单。后端改一个 key,前端静默显示「—」,两边都不报错。R3 断言
#      「后端 buildOutputSummary 实际写入的 *Count 键集 == 前端声明的键集」。
#      配对前端仓库不在本仓库 CI 的 checkout 范围内(workflows 无跨仓库 checkout),因此:
#        - 键集镜像 FRONTEND_COUNT_KEYS 登记在本脚本内,CI 用它守住后端侧(改后端必失败);
#        - 本地存在 ../batch-console/src/utils/pipelineStepSummary.ts 时,额外解析该文件并与镜像
#          逐键比对,把前端侧漂移也暴露出来(FRONTEND_MIRROR_DRIFT);
#        - 前端仓库缺席时打印显式提示,不静默跳过。
#
# 用法:
#   python3 scripts/ci/check-pipeline-summary-keys.py              # 全仓扫描
#   python3 scripts/ci/check-pipeline-summary-keys.py --self-test  # 规则自测(CI 内先跑)
#   python3 scripts/ci/check-pipeline-summary-keys.py --frontend /path/to/pipelineStepSummary.ts
#
# escape hatch(仅 dev 本地 debug):BATCH_CI_SKIP_SUMMARY_KEYS_GATE=1
#
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_GLOB = "**/src/main/java/**/*.java"
# 与 check-direct-config-key-access.py 同口径:测试 / 压测 / E2E / test-support 不参与契约守护。
EXCLUDED_PARTS = (
    "/target/",
    "/load-tests/",
    "/batch-e2e-tests/",
    "/batch-test-support/",
    "/generated-sources/",
)

# 键表来源:worker 各模块的 *RuntimeKeys + batch-common 的 NodeOutputKeys。
KEYS_CLASS_FILE = re.compile(r"(\w*RuntimeKeys|NodeOutputKeys)\.java$")
CONST_DECL = re.compile(
    r'public\s+static\s+final\s+String\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"\s*;'
)
# 限定形引用:SomeRuntimeKeys.CONSTANT / NodeOutputKeys.INPUT_COUNT
QUALIFIED_CONST_REF = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*\.\s*([A-Z][A-Z0-9_]*)\b")
# 裸引用(静态导入):只对常量表里登记过的名字生效,避免把任意大写标识符当 key。
BARE_CONST_REF = re.compile(r"(?<![\w.$])([A-Z][A-Z0-9_]{2,})\b")
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
PUT_CALL = re.compile(r"\b(?:put|putIfPresent)\s*\(")
# 常量声明右侧的字面量是**声明本身**,不是「另写一份字面量」——`static final String X = "x";`
# 必须放行,否则每个自定义键类(如 QuartzLaunchJob 的 JobDataMap 键)都会被误判。
CONST_DECL_RHS = re.compile(r"static\s+final\s+String\s+\w+\s*=\s*$")

EXECUTOR_MARKER = re.compile(r"\bextends\s+AbstractStageExecutor\b")

# 契约边界上的四个 executor,必须都能解析出 buildOutputSummary 的写入键(见 PARSE_FAILED)。
REQUIRED_EXECUTORS = 4

# 配对前端仓库(本仓库父目录的兄弟目录)中的契约声明位置。
FRONTEND_SUMMARY_RELATIVE = Path("batch-console/src/utils/pipelineStepSummary.ts")

# 前端 STAGE_COUNT_KEY + COUNT_KEY_FALLBACK 声明的计数键集(镜像)。
# 权威源:${FRONTEND_SUMMARY_RELATIVE};本地存在该文件时会逐键比对(R3 FRONTEND_MIRROR_DRIFT)。
FRONTEND_COUNT_KEYS = frozenset(
    {
        "parsedCount",  # import PARSE
        "validatedCount",  # import VALIDATE
        "loadedCount",  # import LOAD / FEEDBACK
        "recordCount",  # export GENERATE / STORE / REGISTER
        "processedCount",  # process COMPUTE
        "publishedCount",  # process COMMIT
        "stagedCount",  # process COUNT_KEY_FALLBACK
    }
)

# buildOutputSummary 里的 L4 展示键不参与 R3 计数键判定;计数键按前端既有命名约定以 Count 结尾。
COUNT_KEY_SUFFIX = "Count"

CARRY_FORWARD_NOT_IN_SUMMARY = "CARRY_FORWARD_NOT_IN_SUMMARY"
KEY_LITERAL_AND_CONSTANT = "KEY_LITERAL_AND_CONSTANT"
FRONTEND_COUNT_KEY_MISMATCH = "FRONTEND_COUNT_KEY_MISMATCH"
FRONTEND_MIRROR_DRIFT = "FRONTEND_MIRROR_DRIFT"
PARSE_FAILED = "PARSE_FAILED"


@dataclass(frozen=True)
class Violation:
    rule: str
    path: str
    line: int
    message: str

    def render(self) -> str:
        location = f"{self.path}:{self.line}" if self.line > 0 else self.path
        return f"  [{self.rule}] {location}  {self.message}"


@dataclass(frozen=True)
class KeyTable:
    """键表常量:限定引用 → 值、裸引用名 → 值、值 → 常量登记名。"""

    by_reference: dict[str, str]
    by_name: dict[str, set[str]]
    by_value: dict[str, set[str]]

    def resolve(self, expression: str) -> str | None:
        """把 put(...) 的首参表达式解析成 key 值;解析不出返回 None。"""
        token = expression.strip()
        literal = STRING_LITERAL.fullmatch(token)
        if literal:
            return literal.group(1)
        qualified = QUALIFIED_CONST_REF.fullmatch(token)
        if qualified:
            return self.by_reference.get(f"{qualified.group(1)}.{qualified.group(2)}")
        bare = BARE_CONST_REF.fullmatch(token)
        if bare:
            values = self.by_name.get(bare.group(1), set())
            if len(values) == 1:
                return next(iter(values))
        return None

    def referenced_values(self, text: str) -> dict[str, int]:
        """text 中通过常量引用到的 key 值 → 首次出现行号。"""
        found: dict[str, int] = {}
        for match, value in self.iter_references(text):
            found.setdefault(value, text.count("\n", 0, match.start()) + 1)
        return found

    def iter_references(self, text: str):
        for match in QUALIFIED_CONST_REF.finditer(text):
            value = self.by_reference.get(f"{match.group(1)}.{match.group(2)}")
            if value is not None:
                yield match, value
        # 裸引用:排除「NAME =」声明/赋值形态,避免把同名常量声明本身当引用。
        for match in BARE_CONST_REF.finditer(text):
            if re.match(r"\s*=", text[match.end() : match.end() + 3]):
                continue
            values = self.by_name.get(match.group(1), set())
            if len(values) == 1:
                yield match, next(iter(values))


def strip_comments(text: str) -> str:
    """把 Java 注释替换为等长空白(保留换行),使行号与原文一致。

    注释里的 ``"highWaterMarkOut"`` 只是文档说明(本仓库大量 javadoc 在解释键契约,
    包括本守护自身要守护的那几处)。若把注释计入命中,等于逼作者删掉解释文字。
    逐字符扫描并跳过字符串 / 字符字面量,避免 ``"http://host"`` 里的 ``//`` 被误判为行注释。
    """
    out: list[str] = []
    i = 0
    length = len(text)
    state = "code"
    while i < length:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < length else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"
                out.append("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            out.append(ch)
            i += 1
            continue
        if state == "line_comment":
            if ch == "\n":
                state = "code"
                out.append(ch)
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if ch == "\\" and i + 1 < length:
            out.append(ch)
            out.append(text[i + 1])
            i += 2
            continue
        if (state == "string" and ch == '"') or (state == "char" and ch == "'"):
            state = "code"
        elif ch == "\n":
            # 未闭合字面量属非法 Java;保守回退,避免把后续整段代码当字面量吞掉。
            state = "code"
        out.append(ch)
        i += 1
    return "".join(out)


def _matched_group(text: str, open_index: int, opener: str, closer: str) -> str | None:
    """返回 text[open_index] 处括号的配对内容(不含括号);不平衡返回 None。"""
    if open_index >= len(text) or text[open_index] != opener:
        return None
    depth = 0
    in_string = False
    i = open_index
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
        elif ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                return text[open_index + 1 : i]
        i += 1
    return None


def _split_top_level(arguments: str) -> list[str]:
    """按顶层逗号切分实参(忽略括号与字符串内的逗号)。"""
    parts: list[str] = []
    depth = 0
    current = ""
    in_string = False
    index = 0
    while index < len(arguments):
        ch = arguments[index]
        if in_string:
            current += ch
            if ch == "\\" and index + 1 < len(arguments):
                current += arguments[index + 1]
                index += 2
                continue
            if ch == '"':
                in_string = False
            index += 1
            continue
        if ch == '"':
            in_string = True
            current += ch
            index += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += ch
        index += 1
    if current.strip():
        parts.append(current.strip())
    return parts


@dataclass(frozen=True)
class MethodBody:
    body: str
    declaration_offset: int


def _find_method_body(text: str, name: str) -> MethodBody | None:
    """定位 text 中 name(...) { ... } 的方法体。"""
    signature = re.compile(r"\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*\{")
    for match in signature.finditer(text):
        body = _matched_group(text, match.end() - 1, "{", "}")
        if body is not None:
            return MethodBody(body, match.start())
    return None


def keys_written_by(text: str, table: KeyTable, *, method: str) -> list[str] | None:
    """提取 method 体里所有 put/putIfPresent 的首参 key 值;None 表示结构未识别。"""
    found = _find_method_body(text, method)
    if found is None:
        return None
    keys: list[str] = []
    for call in PUT_CALL.finditer(found.body):
        arguments = _matched_group(found.body, call.end() - 1, "(", ")")
        if arguments is None:
            continue
        parts = _split_top_level(arguments)
        if not parts:
            continue
        value = table.resolve(parts[0])
        if value is not None:
            keys.append(value)
    return keys


def carry_forward_keys(text: str, table: KeyTable) -> dict[str, int] | None:
    """提取 skippedStageCarryForwardKeys() 声明的 key → 行号;None 表示结构未识别。"""
    found = _find_method_body(text, "skippedStageCarryForwardKeys")
    if found is None:
        # 未 override → 基类默认空集,不是解析失败。
        return {}
    line = text.count("\n", 0, found.declaration_offset) + 1
    inline = re.search(r"\breturn\s+Set\.of\s*\(", found.body)
    if inline is not None:
        arguments = _matched_group(found.body, inline.end() - 1, "(", ")")
        if arguments is None:
            return None
        return {
            value: line
            for value in (table.resolve(part) for part in _split_top_level(arguments))
            if value
        }
    field = re.search(r"\breturn\s+([A-Za-z_][A-Za-z0-9_]*)\s*;", found.body)
    if field is None:
        return None
    declaration = re.search(r"\b" + re.escape(field.group(1)) + r"\s*=\s*Set\.of\s*\(", text)
    if declaration is None:
        return None
    arguments = _matched_group(text, declaration.end() - 1, "(", ")")
    if arguments is None:
        return None
    declaration_line = text.count("\n", 0, declaration.start()) + 1
    return {
        value: declaration_line
        for value in (table.resolve(part) for part in _split_top_level(arguments))
        if value
    }


def find_carry_forward_violations(text: str, relative: str, table: KeyTable) -> list[Violation]:
    """R1:carry-forward 键必须都出现在 buildOutputSummary 的写入键里。"""
    summary_keys = keys_written_by(text, table, method="buildOutputSummary")
    if not summary_keys:
        return [
            Violation(
                PARSE_FAILED,
                relative,
                0,
                "AbstractStageExecutor 子类的 buildOutputSummary 写入键解析失败"
                "(守护解析失效,禁止以 0 命中视为通过)",
            )
        ]
    declared = carry_forward_keys(text, table)
    if declared is None:
        return [
            Violation(
                PARSE_FAILED,
                relative,
                0,
                "skippedStageCarryForwardKeys() 声明的键解析失败"
                "(守护解析失效,禁止以 0 命中视为通过)",
            )
        ]
    written = set(summary_keys)
    return [
        Violation(
            CARRY_FORWARD_NOT_IN_SUMMARY,
            relative,
            line,
            f"回灌键 {key!r} 不在 buildOutputSummary 写入键 {sorted(written)} 内 —— "
            "阶段级续跑跳过时该值取不到,水位/计数会静默丢失",
        )
        for key, line in sorted(declared.items())
        if key not in written
    ]


def find_mixing_violations(text: str, relative: str, table: KeyTable) -> list[Violation]:
    """R2:同一文件内已常量化的 key 不得同时以字面量出现。"""
    referenced = table.referenced_values(text)
    if not referenced:
        return []
    violations: list[Violation] = []
    for match in STRING_LITERAL.finditer(text):
        value = match.group(1)
        if value not in referenced:
            continue
        if CONST_DECL_RHS.search(text[: match.start()]):
            continue
        constants = sorted(table.by_value.get(value, set()))
        violations.append(
            Violation(
                KEY_LITERAL_AND_CONSTANT,
                relative,
                text.count("\n", 0, match.start()) + 1,
                f"{value!r} 在同文件内既写字面量又写常量"
                f"({', '.join(constants) or '同名常量'},首次引用于第 {referenced[value]} 行)—— "
                "常量改名时写方不动,键名会静默漂移",
            )
        )
    return violations


def count_keys(keys: set[str]) -> set[str]:
    """按前端既有命名约定,取以 Count 结尾的计数键。"""
    return {key for key in keys if key.endswith(COUNT_KEY_SUFFIX) and len(key) > len(COUNT_KEY_SUFFIX)}


def find_contract_violations(
    executor_keys: dict[str, set[str]],
    frontend_keys: frozenset[str] = FRONTEND_COUNT_KEYS,
) -> list[Violation]:
    """R3:后端 buildOutputSummary 实际写入的 *Count 键集必须等于前端声明的键集。"""
    backend = count_keys({key for keys in executor_keys.values() for key in keys})
    violations: list[Violation] = []
    for key in sorted(frontend_keys - backend):
        violations.append(
            Violation(
                FRONTEND_COUNT_KEY_MISMATCH,
                "batch-worker",
                0,
                f"前端声明并读取的计数键 {key!r} 已无任何 buildOutputSummary 写入 —— "
                "前端会静默显示「—」而不报错",
            )
        )
    for key in sorted(backend - frontend_keys):
        violations.append(
            Violation(
                FRONTEND_COUNT_KEY_MISMATCH,
                "batch-worker",
                0,
                f"buildOutputSummary 新增计数键 {key!r} 未被前端声明 —— "
                "需同步 batch-console pipelineStepSummary.ts",
            )
        )
    return violations


def parse_frontend_keys(text: str) -> set[str] | None:
    """解析 pipelineStepSummary.ts 的 STAGE_COUNT_KEY 取值 + COUNT_KEY_FALLBACK 条目。"""
    stage = re.search(r"STAGE_COUNT_KEY[^=]*=\s*\{(.*?)\}", text, re.S)
    fallback = re.search(r"COUNT_KEY_FALLBACK[^=]*=\s*\[(.*?)\]", text, re.S)
    if stage is None or fallback is None:
        return None
    keys = set(re.findall(r"""['"]([^'"]+)['"]""", stage.group(1)))
    keys |= set(re.findall(r"""['"]([^'"]+)['"]""", fallback.group(1)))
    return keys


def java_sources() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.glob(SOURCE_GLOB):
        posix = path.as_posix()
        if any(part in posix for part in EXCLUDED_PARTS):
            continue
        files.append(path)
    return sorted(files)


def build_key_table(paths: list[Path]) -> KeyTable:
    by_reference: dict[str, str] = {}
    by_name: dict[str, set[str]] = {}
    by_value: dict[str, set[str]] = {}
    for path in paths:
        if not KEYS_CLASS_FILE.search(path.name):
            continue
        try:
            text = strip_comments(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError):
            continue
        simple_class = path.stem
        for match in CONST_DECL.finditer(text):
            name, value = match.group(1), match.group(2)
            by_reference[f"{simple_class}.{name}"] = value
            by_name.setdefault(name, set()).add(value)
            by_value.setdefault(value, set()).add(f"{simple_class}.{name}")
    return KeyTable(by_reference, by_name, by_value)


def frontend_path() -> Path:
    return ROOT.parent / FRONTEND_SUMMARY_RELATIVE


def scan(explicit_frontend: Path | None = None) -> tuple[list[Violation], list[str]]:
    notes: list[str] = []
    paths = java_sources()
    table = build_key_table(paths)
    violations: list[Violation] = []
    executor_keys: dict[str, set[str]] = {}

    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        try:
            text = strip_comments(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError):
            continue
        violations.extend(find_mixing_violations(text, relative, table))
        if EXECUTOR_MARKER.search(text):
            violations.extend(find_carry_forward_violations(text, relative, table))
            keys = keys_written_by(text, table, method="buildOutputSummary")
            if keys:
                executor_keys[relative] = set(keys)

    if len(executor_keys) < REQUIRED_EXECUTORS:
        violations.append(
            Violation(
                PARSE_FAILED,
                "batch-worker",
                0,
                f"仅解析出 {len(executor_keys)} 个 AbstractStageExecutor 子类的 output_summary 写入键"
                f"(期望 {REQUIRED_EXECUTORS})——守护解析失效,禁止以 0 命中视为通过",
            )
        )

    violations.extend(find_contract_violations(executor_keys))

    frontend = explicit_frontend or frontend_path()
    if not frontend.is_file():
        notes.append(
            f"提示:配对前端仓库缺席({frontend}),本次只验证后端侧键集;"
            "前端侧漂移需在检出 batch-console 的环境中复跑本守护。"
        )
        return violations, notes
    parsed = parse_frontend_keys(frontend.read_text(encoding="utf-8"))
    if parsed is None:
        violations.append(
            Violation(
                PARSE_FAILED,
                str(frontend),
                0,
                "无法从 pipelineStepSummary.ts 解析 STAGE_COUNT_KEY / COUNT_KEY_FALLBACK"
                "(守护解析失效,禁止以 0 命中视为通过)",
            )
        )
        return violations, notes
    drift = sorted(parsed.symmetric_difference(FRONTEND_COUNT_KEYS))
    if drift:
        violations.append(
            Violation(
                FRONTEND_MIRROR_DRIFT,
                str(frontend),
                0,
                f"前端声明的计数键 {sorted(parsed)} 与本守护镜像 {sorted(FRONTEND_COUNT_KEYS)} 不一致,"
                f"差异 {drift} —— 请同步本脚本的 FRONTEND_COUNT_KEYS",
            )
        )
    else:
        notes.append(f"已核对前端契约:{FRONTEND_SUMMARY_RELATIVE} 声明 {len(parsed)} 个计数键,与镜像一致。")
    return violations, notes


# ---- 自测:规则对漂移前代码必须能报,对收敛后代码不得误报 ----
_PREFIX_CARRY_FORWARD = """
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
    summary.put(ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT, context.get(ATTR_PROCESSED));
    return summary;
  }
}
"""

_FIXED_CARRY_FORWARD = """
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
    summary.put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, context.get(ATTR_WATERMARK));
    summary.put(ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT, context.get(ATTR_PROCESSED));
    return summary;
  }
}
"""

# 写用字面量、读用常量(历史 highWaterMarkIn 写法)。
_PREFIX_MIXING = """
  summary.put(
      "highWaterMarkIn", context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));
"""

_FIXED_MIXING = """
  summary.put(
      PipelineRuntimeKeys.HIGH_WATER_MARK_IN,
      context.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN));
"""

# L4 展示键:无常量引用,即使与某个已登记 key 同名也不得误报。
_L4_LITERAL_ONLY = """
  summary.put("batchKey", context.getBatchKey());
  summary.put("stepCode", step.stepCode());
  summary.put("tenantId", context.getTenantId());
"""

# 常量声明自身(如 QuartzLaunchJob 的 JobDataMap 键):是声明而非另写一份字面量,不得误报。
_DECLARATION_SITE = """
public class QuartzLaunchJob implements Job {
  public static final String JOB_CODE = "jobCode";

  String read(Map<String, Object> attrs) {
    return String.valueOf(attrs.get(PipelineRuntimeKeys.JOB_CODE));
  }
}
"""


# 逐字摘自 ../batch-console/src/utils/pipelineStepSummary.ts 的 StageCountKey / CountKeyFallback。
_FRONTEND_FIXTURE = """
export const STAGE_COUNT_KEY: Record<string, string> = {
  PARSE: 'parsedCount',
  VALIDATE: 'validatedCount',
  LOAD: 'loadedCount',
  FEEDBACK: 'loadedCount',
  GENERATE: 'recordCount',
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


def _self_test() -> int:
    table = KeyTable(
        by_reference={
            "PipelineRuntimeKeys.HIGH_WATER_MARK_OUT": "highWaterMarkOut",
            "PipelineRuntimeKeys.HIGH_WATER_MARK_IN": "highWaterMarkIn",
            "ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT": "processedCount",
            "NodeOutputKeys.BATCH_KEY": "batchKey",
            "PipelineRuntimeKeys.JOB_CODE": "jobCode",
        },
        by_name={
            "HIGH_WATER_MARK_OUT": {"highWaterMarkOut"},
            "HIGH_WATER_MARK_IN": {"highWaterMarkIn"},
            "PROCESS_PROCESSED_COUNT": {"processedCount"},
        },
        by_value={
            "highWaterMarkOut": {"PipelineRuntimeKeys.HIGH_WATER_MARK_OUT"},
            "highWaterMarkIn": {"PipelineRuntimeKeys.HIGH_WATER_MARK_IN"},
            "processedCount": {"ProcessRuntimeKeys.PROCESS_PROCESSED_COUNT"},
            "batchKey": {"NodeOutputKeys.BATCH_KEY"},
            "jobCode": {"PipelineRuntimeKeys.JOB_CODE"},
        },
    )
    ok = True

    def expect(name: str, got: bool, want: bool) -> None:
        nonlocal ok
        if got != want:
            ok = False
        print(f"  [{'PASS' if got == want else 'FAIL'}] {name}: result={got} expected={want}")

    def carries(fixture: str) -> bool:
        return bool(
            [
                violation
                for violation in find_carry_forward_violations(
                    strip_comments(fixture), "Demo.java", table
                )
                if violation.rule == CARRY_FORWARD_NOT_IN_SUMMARY
            ]
        )

    def mixes(fixture: str) -> bool:
        return bool(find_mixing_violations(strip_comments(fixture), "Demo.java", table))

    def parse_failed(fixture: str) -> bool:
        return bool(
            [
                violation
                for violation in find_carry_forward_violations(
                    strip_comments(fixture), "Demo.java", table
                )
                if violation.rule == PARSE_FAILED
            ]
        )

    def contract_clean(executor_keys: dict[str, set[str]]) -> bool:
        return not find_contract_violations(executor_keys)

    print("[summary-keys-gate] self-test:")
    expect("R1 回灌键未写入 summary 必须命中", carries(_PREFIX_CARRY_FORWARD), True)
    expect("R1 两侧引用同一常量不得误报", carries(_FIXED_CARRY_FORWARD), False)
    expect(
        "R1 解析不出写入键必须报 PARSE_FAILED",
        parse_failed("public class X extends AbstractStageExecutor<A, B> { }"),
        True,
    )
    expect("R2 读用常量写用字面量必须命中", mixes(_PREFIX_MIXING), True)
    expect("R2 读写同用常量不得误报", mixes(_FIXED_MIXING), False)
    expect("R2 无常量引用的 L4 字面量不得误报", mixes(_L4_LITERAL_ONLY), False)
    expect("R2 常量声明自身不得误报", mixes(_DECLARATION_SITE), False)
    expect(
        "R2 注释里的字面量不得误报",
        mixes('// 曾经写成 "highWaterMarkIn",现已收敛\n' + _FIXED_MIXING),
        False,
    )
    expect(
        "R3 前端键缺失必须命中",
        bool(find_contract_violations({"a/Executor.java": {"processedCount"}})),
        True,
    )
    expect(
        "R3 键集完全一致不得误报",
        contract_clean({"a/Executor.java": set(FRONTEND_COUNT_KEYS)}),
        True,
    )
    expect(
        "R3 后端新增未声明计数键必须命中",
        bool(find_contract_violations({"a/Executor.java": set(FRONTEND_COUNT_KEYS) | {"newCount"}})),
        True,
    )
    expect(
        "R3 L4 展示键(非 *Count)不参与判定",
        contract_clean(
            {"a/Executor.java": set(FRONTEND_COUNT_KEYS) | {"batchKey", "fileId", "success"}}
        ),
        True,
    )
    expect(
        "前端 TS 解析:STAGE_COUNT_KEY 取值 + COUNT_KEY_FALLBACK 条目",
        parse_frontend_keys(_FRONTEND_FIXTURE) == set(FRONTEND_COUNT_KEYS),
        True,
    )

    if ok:
        print("[summary-keys-gate] self-test PASS")
        return 0
    print("[summary-keys-gate] self-test FAIL —— 守护规则失效,禁止合入")
    return 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return _self_test()

    if os.environ.get("BATCH_CI_SKIP_SUMMARY_KEYS_GATE", "0") == "1":
        print("[summary-keys-gate] BATCH_CI_SKIP_SUMMARY_KEYS_GATE=1 —— 跳过(仅 dev 本地 debug 应使用)")
        return 0

    # 先自测:规则本身失效则直接失败(避免「静默匹配为空 = 假绿」)。
    if _self_test() != 0:
        return 1

    explicit_frontend = None
    if "--frontend" in argv:
        index = argv.index("--frontend")
        if index + 1 >= len(argv):
            print("--frontend 需要路径参数", file=sys.stderr)
            return 2
        explicit_frontend = Path(argv[index + 1]).resolve()

    violations, notes = scan(explicit_frontend)
    for note in notes:
        print(f"[summary-keys-gate] {note}")
    if violations:
        print("\n[summary-keys-gate] 发现 pipeline 摘要键契约漂移:", file=sys.stderr)
        for violation in sorted(violations, key=lambda item: (item.rule, item.path, item.line)):
            print(violation.render(), file=sys.stderr)
        print(
            "\n修法:契约键(会被本类之外按字符串读的 key)统一引用 *RuntimeKeys / NodeOutputKeys 常量;"
            "L4 纯展示键(stepCode / stage / implCode / tenantId / workerId / success / code / message)"
            "保持字面量即可。",
            file=sys.stderr,
        )
        print(f"共 {len(violations)} 处。", file=sys.stderr)
        return 1

    print("[summary-keys-gate] OK —— 摘要键 / 回灌键 / 前端计数键契约一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
