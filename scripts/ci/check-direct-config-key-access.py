#!/usr/bin/env python3
"""扫描生产 Java 中通过字符串 key 读取配置的写法，输出治理 inventory。

对应 docs/runbook/config-key-access-governance.md 的“阶段 0：报告模式”。本脚本只报告，
不改写源码，也不把命中数量本身判定为缺陷。默认 ``--report`` 恒以 0 退出，作为 Full Gate /
nightly 的治理看板；历史存量不因本脚本失败。

覆盖三类读取方式：

- ``DIRECT_GET_PROPERTY``：``Environment#getProperty`` / ``getRequiredProperty``；
- ``SYSTEM_PROPERTY``：``System.getProperty``；
- ``VALUE_INJECTION``：``@Value("${...}")``。

每条命中给出分类建议：``PROJECT_CONFIG``、``SECRET_CONFIG``、``SPRING_INFRA``、``JVM_SYSTEM``、
``TEST_ONLY``，并标记是否命中白名单。分类口径与治理文档一致，允许保留的例外不会被强行判违规。
``TEST_ONLY`` 同时覆盖「测试 / 构建运行期属性」——如 ``local.server.port``（Spring Boot RANDOM_PORT
注入）、``maven.multiModuleProjectDirectory``（Maven 构建属性）、``boundedContext.report``（仓库扫描型
约定测试的输出路径）；这些 key 没有应用侧归属类，被误判为 ``PROJECT_CONFIG`` 只会污染治理看板。

除生产源码外，``--report`` 与 ``--json`` 还会附带一份 ``src/test/java`` 的 **TEST_SOURCE** 快照，
用于看清「测试里新硬编码了哪个 key」。该快照**只做可见性**：不进入基线、不参与
``--check-baseline``、恒不影响退出码（治理文档 §2 明确 ``src/test`` 不纳入 CI 拦截）。

匹配前先剥离 ``//`` 与 ``/* */`` 注释（:func:`strip_comments`）：注释里的 ``@Value("${...}")``
只是文档，不是配置读取。否则“把旧写法迁移为 @ConfigurationProperties”的说明文字反而会让检查
失败，等于用检查口径逼作者删掉解释。

用法::

    python3 scripts/ci/check-direct-config-key-access.py --report
    python3 scripts/ci/check-direct-config-key-access.py --json build/config-key-access.json
    python3 scripts/ci/check-direct-config-key-access.py --write-baseline \
        docs/governance/direct-config-key-access-baseline.txt
    python3 scripts/ci/check-direct-config-key-access.py --check-baseline \
        docs/governance/direct-config-key-access-baseline.txt

``--check-baseline`` 是阶段 1（增量拦截，CFGKEY-3）的入口：只对相对基线新增的高风险
``PROJECT_CONFIG`` / ``SECRET_CONFIG`` 命中失败，历史存量与允许保留的例外不阻断。已接入 PR Gate
（``PR_DIRECT_CONFIG_KEY_BASELINE``）和 Full Gate（``FULL_DIRECT_CONFIG_KEY_BASELINE``），
基线文件为 ``docs/governance/direct-config-key-access-baseline.txt``。

阶段 2（``docs/runbook/config-key-access-governance.md`` §6）的拦截口径由本脚本的同一入口承担：

- ``batch.*`` 直接读取（``DIRECT_GET_PROPERTY`` / ``SYSTEM_PROPERTY``）默认失败；
- ``batch.*`` 高风险 ``@Value`` 默认失败；
- 明确允许项必须在 ``ALLOWLIST`` 中写明 ``reason`` / ``owner`` / ``review``，缺项即失败；
- ``spring.*``、JVM 系统属性继续只报告、不阻断。

阶段 2 与阶段 1 的差别只在白名单纪律：阶段 1 起高风险命中已全量进入拦截范围（``batch.*`` 经
:func:`classify` 一律归为 ``PROJECT_CONFIG``，敏感 key 归为 ``SECRET_CONFIG``），因此阶段 2
新增的可执行约束是“白名单条目必须携带原因 / owner / 复查条件”，其余口径沿用。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

# 生产源码范围：各模块 src/main/java。测试、压测、E2E、test-support 不进基线。
SOURCE_GLOB = "**/src/main/java/**/*.java"
# 测试源码范围：各模块 src/test/java。只做可见性报告（TEST_SOURCE），不进入基线、不阻断 CI。
TEST_SOURCE_GLOB = "**/src/test/java/**/*.java"
EXCLUDED_PARTS = ("/target/", "/load-tests/", "/batch-e2e-tests/", "/batch-test-support/")

# 读取方式识别。ENV_PROPERTY 用 (?<!System) 排除 System.getProperty，避免与 SYSTEM_PROPERTY 重复计数。
SYSTEM_PROPERTY = re.compile(r"\bSystem\s*\.\s*getProperty\s*\(\s*\"([^\"]+)\"")
ENV_PROPERTY = re.compile(
    r"(?<!System)\.\s*get(?:Required)?Property\s*\(\s*\"([^\"]+)\""
)
# key 只取标识符形态（字母 / 数字 / 下划线开头，后接点或短横线），因此
# ``@Value("${" + SomeProperties.KEY + "}")`` 这种「已收敛到常量」的写法不会被误判为字面量 key
# ——``${`` 之后紧跟的是引号而非标识符，正则整体不匹配。收敛到常量正是治理的目标状态。
VALUE_INJECTION = re.compile(r"@Value\s*\(\s*\"\$\{\s*([A-Za-z0-9_][A-Za-z0-9_.\-]*)")
# 测试夹具写法：MockEnvironment#withProperty("key", value)。只在测试源码里出现，
# 属"夹具按字面量设置 key"，不是配置读取，但同样会造成 key 字符串漂移。
TEST_PROPERTY_FIXTURE = re.compile(r"\bwithProperty\s*\(\s*\"([^\"]+)\"")
# 测试夹具写法：@SpringBootTest(properties = {"key=value"}) / @TestPropertySource 里的字面量。
TEST_PROPERTY_ENTRY = re.compile(r"\"([a-z][a-z0-9]*(?:\.[a-z0-9-]+)+)=([^\"]*)\"")

DIRECT_GET_PROPERTY = "DIRECT_GET_PROPERTY"
SYSTEM_PROPERTY_TYPE = "SYSTEM_PROPERTY"
VALUE_INJECTION_TYPE = "VALUE_INJECTION"
TEST_PROPERTY_FIXTURE_TYPE = "TEST_PROPERTY_FIXTURE"
TEST_PROPERTY_ENTRY_TYPE = "TEST_PROPERTY_ENTRY"

PROJECT_CONFIG = "PROJECT_CONFIG"
SECRET_CONFIG = "SECRET_CONFIG"
SPRING_INFRA = "SPRING_INFRA"
JVM_SYSTEM = "JVM_SYSTEM"
TEST_ONLY = "TEST_ONLY"

SENSITIVE_KEY = re.compile(
    r"(password|secret|token|credential|private[-_.]?key|kms|signing)", re.IGNORECASE
)
# JVM 自带系统属性前缀，不纳入生产配置治理。
JVM_SYSTEM_PREFIXES = ("java.", "javax.", "sun.", "os.", "user.", "line.separator",
                       "file.separator", "path.separator", "file.encoding")
# Spring 基础设施 / 运行期测试端口，允许保留。
SPRING_INFRA_PREFIXES = ("spring.", "management.", "server.", "logging.")
# 测试 / 构建运行期属性：由 Spring Boot RANDOM_PORT、Maven 或测试自身注入，不属应用配置。
# ``maven.multiModuleProjectDirectory`` / ``boundedContext.report`` 只被「仓库扫描型约定测试」读取，
# 若归为 PROJECT_CONFIG 会让报告把构建工具属性当成待收敛的应用配置，故显式豁免。
TEST_ONLY_KEYS = (
    "local.server.port",
    "maven.multiModuleProjectDirectory",
    "boundedContext.report",
)
TEST_ONLY_PREFIXES = ("batch.test.",)

# 白名单：明确允许保留的读取点（文件相对路径 + key）。命中只做标记，不判违规。
# 治理文档 §3 允许保留的框架桥接、JVM 路径与测试运行期属性无需逐条登记，分类已覆盖；
# 本白名单只登记“分类为 PROJECT_CONFIG / SECRET_CONFIG 但确有理由暂留”的例外，
# 每条必须写明原因、owner 与复查条件（阶段 1 起 PR 描述需同步说明）。
ALLOWLIST: dict[str, dict[str, str]] = {
    "batch-common/src/main/java/io/github/pinpols/batch/common/config/"
    "BatchSecurityProperties.java#spring.datasource.password": {
        "reason": (
            "生产 profile 弱口令守护必须读取 Spring 实际生效的 spring.datasource.password；"
            "按治理文档 §2/§3 属允许保留的框架配置。收敛成自定义 properties 会把 spring.* 复制一份，"
            "制造第二份事实来源（§7 明确不建议）。"
        ),
        "owner": "平台/基础设施组",
        "review": "封装只读 runtime inspector 后复查（§5.3）；在此之前保持直读 + 生产 fail-fast 校验。",
    },
}

# 阶段 2：白名单条目必须齐备的三项元数据，缺项或空值即判失败。
ALLOWLIST_REQUIRED_FIELDS = ("reason", "owner", "review")

HIGH_RISK_CLASSIFICATIONS = frozenset({PROJECT_CONFIG, SECRET_CONFIG})

# 测试源码报告里，聚合段每个 key 最多列出的文件数（其余折叠为计数，保持可扫读）。
FILE_LIST_LIMIT = 3


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    read_type: str
    key: str
    classification: str
    allowlisted: bool

    @property
    def identity(self) -> str:
        """基线比对用的稳定标识，忽略行号漂移。"""
        return f"{self.path}#{self.read_type}#{self.key}"

    def to_dict(self) -> dict[str, object]:
        return {
            "path": self.path,
            "line": self.line,
            "readType": self.read_type,
            "key": self.key,
            "classification": self.classification,
            "allowlisted": self.allowlisted,
        }


def classify(key: str) -> str:
    """按治理文档 §3 给 key 分类，敏感级别优先，其次框架 / JVM / 测试例外。"""
    if SENSITIVE_KEY.search(key):
        return SECRET_CONFIG
    if key in TEST_ONLY_KEYS or key.startswith(TEST_ONLY_PREFIXES):
        return TEST_ONLY
    if key.startswith(JVM_SYSTEM_PREFIXES):
        return JVM_SYSTEM
    if key.startswith(SPRING_INFRA_PREFIXES):
        return SPRING_INFRA
    return PROJECT_CONFIG


def java_files(glob: str) -> list[Path]:
    """按 glob 收集 Java 源码，剔除 target / 压测 / E2E / test-support。"""
    files: list[Path] = []
    for path in ROOT.glob(glob):
        posix = path.as_posix()
        if any(part in posix for part in EXCLUDED_PARTS):
            continue
        files.append(path)
    return sorted(files)


def production_java_files() -> list[Path]:
    return java_files(SOURCE_GLOB)


def test_java_files() -> list[Path]:
    return java_files(TEST_SOURCE_GLOB)


def strip_comments(text: str) -> str:
    """把 Java 注释（``//`` 与 ``/* */``）替换为等长空白，保留换行以稳定行号。

    注释里的 ``@Value("${...}")`` 只是文档，不是配置读取：迁移说明、反例示例、
    “已废弃写法”注解都会出现这种字面量。若把它们计入命中，治理就会逼着作者删掉
    说明文字，属于把检查口径凌驾于事实之上。本函数是纯函数，便于单测。

    逐字符扫描并跳过字符串 / 字符字面量，避免把 ``"http://host"`` 里的 ``//``
    误判为行注释。替换保持 1:1 长度（注释起始符也替换为同宽空白），因此
    :func:`findings_from_text` 用替换后文本计算的行号与原文一致。
    """
    out: list[str] = []
    i = 0
    length = len(text)
    # code | line_comment | block_comment | string | char
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
        # 字符串 / 字符字面量内部：转义序列整体跳过，避免 \" 提前结束字面量。
        if ch == "\\" and i + 1 < length:
            out.append(ch)
            out.append(text[i + 1])
            i += 2
            continue
        if (state == "string" and ch == '"') or (state == "char" and ch == "'"):
            state = "code"
        elif ch == "\n":
            # 未闭合字面量属非法 Java；保守回退，避免把后续整段代码当字面量吞掉。
            state = "code"
        out.append(ch)
        i += 1
        continue
    return "".join(out)


def findings_from_text(
    text: str, relative: str, *, test_source: bool = False
) -> list[Finding]:
    """从单个 Java 源码文本提取命中，纯函数，便于单测。

    匹配前先剥离注释：命中必须来自可执行代码，注释里的字面量只是文档。

    ``test_source=True`` 时额外识别测试夹具写法（``withProperty("key", ...)`` 与
    ``"key=value"`` 字面量）。这两类只在测试源码出现，故不进生产扫描，避免口径混用。
    """
    findings: list[Finding] = []
    scannable = strip_comments(text)
    patterns = [
        (SYSTEM_PROPERTY_TYPE, SYSTEM_PROPERTY),
        (DIRECT_GET_PROPERTY, ENV_PROPERTY),
        (VALUE_INJECTION_TYPE, VALUE_INJECTION),
    ]
    if test_source:
        patterns.append((TEST_PROPERTY_FIXTURE_TYPE, TEST_PROPERTY_FIXTURE))
        patterns.append((TEST_PROPERTY_ENTRY_TYPE, TEST_PROPERTY_ENTRY))
    for read_type, pattern in patterns:
        for match in pattern.finditer(scannable):
            key = match.group(1).strip()
            if not key:
                continue
            line = scannable.count("\n", 0, match.start()) + 1
            classification = classify(key)
            allowlisted = f"{relative}#{key}" in ALLOWLIST
            findings.append(
                Finding(relative, line, read_type, key, classification, allowlisted)
            )

    findings.sort(key=lambda item: (item.path, item.line, item.key))
    return findings


def scan_file(path: Path, *, test_source: bool = False) -> list[Finding]:
    relative = path.relative_to(ROOT).as_posix()
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []
    return findings_from_text(text, relative, test_source=test_source)


def scan_paths(paths: list[Path], *, test_source: bool = False) -> list[Finding]:
    findings: list[Finding] = []
    for path in paths:
        findings.extend(scan_file(path, test_source=test_source))
    return findings


def scan() -> list[Finding]:
    """生产源码命中；基线比对与拦截只用这一份。"""
    return scan_paths(production_java_files())


def scan_test_sources() -> list[Finding]:
    """测试源码命中（TEST_SOURCE）。只进报告，不进基线，不参与拦截。"""
    return scan_paths(test_java_files(), test_source=True)


def summarize(findings: list[Finding]) -> dict[str, int]:
    summary: dict[str, int] = {
        "total": len(findings),
        PROJECT_CONFIG: 0,
        SECRET_CONFIG: 0,
        SPRING_INFRA: 0,
        JVM_SYSTEM: 0,
        TEST_ONLY: 0,
        "allowlisted": 0,
    }
    for finding in findings:
        summary[finding.classification] += 1
        if finding.allowlisted:
            summary["allowlisted"] += 1
    return summary


def render_report(findings: list[Finding]) -> str:
    summary = summarize(findings)
    lines = [
        "直接配置 Key 读取快照（生产 Java）",
        "",
        f"命中总数: {summary['total']}",
        f"  PROJECT_CONFIG : {summary[PROJECT_CONFIG]}",
        f"  SECRET_CONFIG  : {summary[SECRET_CONFIG]}",
        f"  SPRING_INFRA   : {summary[SPRING_INFRA]}",
        f"  JVM_SYSTEM     : {summary[JVM_SYSTEM]}",
        f"  TEST_ONLY      : {summary[TEST_ONLY]}",
        f"  命中白名单     : {summary['allowlisted']}",
        "",
        "高风险明细（PROJECT_CONFIG / SECRET_CONFIG，非白名单）:",
    ]
    high_risk = [
        finding
        for finding in findings
        if finding.classification in HIGH_RISK_CLASSIFICATIONS and not finding.allowlisted
    ]
    if not high_risk:
        lines.append("  （无）")
    for finding in high_risk:
        lines.append(
            f"  {finding.path}:{finding.line} [{finding.read_type}] "
            f"{finding.classification} {finding.key}"
        )
    return "\n".join(lines) + "\n"


def render_test_report(findings: list[Finding]) -> str:
    """测试源码（TEST_SOURCE）快照：只做可见性，不判违规、不进基线。

    治理文档 §2 明确 ``src/test`` 不纳入 CI 拦截——测试本来就要用
    ``MockEnvironment#withProperty(...)`` / ``@SpringBootTest(properties = ...)``
    设置 key，强行阻断会逼出大量豁免。本段的价值是让「测试里新硬编码了哪个 key」
    可见，便于按需收敛到共享常量。

    两类夹具写法分开呈现：``withProperty(...)`` 逐条列出（可精确改到常量）；
    ``"key=value"`` 注解字面量按 key 聚合（高度重复，逐条会淹没可执行信息）。
    """
    summary = summarize(findings)
    by_type: dict[str, int] = {}
    for finding in findings:
        by_type[finding.read_type] = by_type.get(finding.read_type, 0) + 1
    lines = [
        "测试源码直接配置 Key 读取快照（TEST_SOURCE，仅报告）",
        "",
        "治理文档 §2：src/test 不纳入 CI 拦截，也不进入基线；本段只提供可见性。",
        "",
        f"命中总数: {summary['total']}",
        f"  PROJECT_CONFIG : {summary[PROJECT_CONFIG]}",
        f"  SECRET_CONFIG  : {summary[SECRET_CONFIG]}",
        f"  SPRING_INFRA   : {summary[SPRING_INFRA]}",
        f"  JVM_SYSTEM     : {summary[JVM_SYSTEM]}",
        f"  TEST_ONLY      : {summary[TEST_ONLY]}",
        f"  命中白名单     : {summary['allowlisted']}",
        "",
        "按读取方式:",
    ]
    for read_type in (
        SYSTEM_PROPERTY_TYPE,
        DIRECT_GET_PROPERTY,
        VALUE_INJECTION_TYPE,
        TEST_PROPERTY_FIXTURE_TYPE,
        TEST_PROPERTY_ENTRY_TYPE,
    ):
        lines.append(f"  {read_type:22} {by_type.get(read_type, 0)}")

    precise = [
        finding
        for finding in findings
        if finding.read_type != TEST_PROPERTY_ENTRY_TYPE
    ]
    lines += ["", "明细（文件:行 [读取方式] 分类 key）:"]
    if not precise:
        lines.append("  （无）")
    for finding in precise:
        lines.append(
            f"  {finding.path}:{finding.line} [{finding.read_type}] "
            f"{finding.classification} {finding.key}"
        )

    entries = [
        finding for finding in findings if finding.read_type == TEST_PROPERTY_ENTRY_TYPE
    ]
    lines += [
        "",
        "夹具配置项字面量（@SpringBootTest properties / @TestPropertySource，按 key 聚合）:",
        "  这些字面量本身不报错，但 key 改名后会静默失效（Spring 默认忽略未知属性），故纳入可见性。",
    ]
    if not entries:
        lines.append("  （无）")
    aggregated: dict[str, list[Finding]] = {}
    for finding in entries:
        aggregated.setdefault(finding.key, []).append(finding)
    for key in sorted(aggregated, key=lambda k: (-len(aggregated[k]), k)):
        occurrences = aggregated[key]
        files = sorted({item.path for item in occurrences})
        lines.append(
            f"  {key} × {len(occurrences)}（{len(files)} 个文件）"
            f" [{occurrences[0].classification}]"
        )
        for path in files[:FILE_LIST_LIMIT]:
            lines.append(f"      {path}")
        if len(files) > FILE_LIST_LIMIT:
            lines.append(f"      … 另有 {len(files) - FILE_LIST_LIMIT} 个文件")
    return "\n".join(lines) + "\n"


def baseline_identities(findings: list[Finding]) -> list[str]:
    """基线只登记高风险命中；框架 / JVM / 测试例外由分类稳定覆盖，无需进基线。

    已登记 ``ALLOWLIST`` 的条目不进基线：白名单是“明确允许的例外”，基线是“尚未收敛的存量”，
    两者语义不同（治理文档 §6 阶段 2 / §9）。若白名单条目仍留在基线，等于把例外又当成存量。
    """
    identities = sorted(
        {
            finding.identity
            for finding in findings
            if finding.classification in HIGH_RISK_CLASSIFICATIONS
            and not finding.allowlisted
        }
    )
    return identities


def validate_allowlist() -> list[str]:
    """阶段 2 白名单纪律：每条必须写明原因 / owner / 复查条件。

    返回问题描述列表；空列表表示白名单合规。这是纯结构校验，不判断理由是否成立。
    """
    problems: list[str] = []
    for entry, metadata in ALLOWLIST.items():
        missing = [
            field
            for field in ALLOWLIST_REQUIRED_FIELDS
            if not str(metadata.get(field, "")).strip()
        ]
        if missing:
            problems.append(f"{entry}: 缺少 {'/'.join(missing)}")
    return sorted(problems)


def read_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    entries: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries.add(line)
    return entries


def write_baseline(path: Path, findings: list[Finding]) -> None:
    header = [
        "# 直接配置 Key 读取基线（高风险 PROJECT_CONFIG / SECRET_CONFIG）。",
        "# 由 scripts/ci/check-direct-config-key-access.py --write-baseline 生成。",
        "# 阶段 1（CFGKEY-3）据此只对新增高风险命中失败；收敛一项即从本文件删除对应行。",
        "# 标识格式：<相对路径>#<读取方式>#<key>，忽略行号漂移。",
    ]
    identities = baseline_identities(findings)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(header + identities) + "\n", encoding="utf-8")


def check_baseline(path: Path, findings: list[Finding]) -> int:
    allowlist_problems = validate_allowlist()
    if allowlist_problems:
        print(
            "❌ 白名单条目未写明原因 / owner / 复查条件"
            "（治理文档 §6 阶段 2）：",
            file=sys.stderr,
        )
        for problem in allowlist_problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    baseline = read_baseline(path)
    current = set(baseline_identities(findings))
    added = sorted(current - baseline)
    if not added:
        print(
            "✅ 直接配置 Key 读取无新增高风险命中 "
            f"（基线 {len(baseline)} 项，当前高风险 {len(current)} 项，"
            f"白名单 {len(ALLOWLIST)} 项）"
        )
        return 0
    print("❌ 检测到新增高风险直接配置 Key 读取（应改用 @ConfigurationProperties）：", file=sys.stderr)
    for identity in added:
        print(f"  - {identity}", file=sys.stderr)
    print(
        f"\n如为合理例外，请按治理文档 §6 阶段 2 在白名单中写明原因 / owner / 复查条件，"
        f"并更新基线：\n"
        f"  python3 scripts/ci/check-direct-config-key-access.py --write-baseline {path}",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--report",
        action="store_true",
        help="打印人读快照（默认行为），恒以 0 退出，不阻断历史存量",
    )
    parser.add_argument("--json", metavar="PATH", help="输出机器可读 JSON 到指定路径")
    parser.add_argument(
        "--write-baseline",
        metavar="PATH",
        help="按当前高风险命中重建基线文件",
    )
    parser.add_argument(
        "--check-baseline",
        metavar="PATH",
        help="阶段 1 增量拦截：只对相对基线新增的高风险命中失败",
    )
    args = parser.parse_args()

    findings = scan()
    test_findings = scan_test_sources()

    if args.json:
        payload = {
            "schemaVersion": 1,
            "summary": summarize(findings),
            "findings": [finding.to_dict() for finding in findings],
            "testSourceSummary": summarize(test_findings),
            "testSourceFindings": [finding.to_dict() for finding in test_findings],
        }
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(
            f"已写入 JSON 快照: {out} "
            f"（生产 {len(findings)} 条 / 测试源码 {len(test_findings)} 条命中）"
        )

    if args.write_baseline:
        write_baseline(Path(args.write_baseline), findings)
        print(
            f"已重建基线: {args.write_baseline} "
            f"（{len(baseline_identities(findings))} 项高风险命中；测试源码不进入基线）"
        )
        return 0

    if args.check_baseline:
        return check_baseline(Path(args.check_baseline), findings)

    # 默认 / --report：报告模式，恒不阻断。
    sys.stdout.write(render_report(findings))
    sys.stdout.write("\n")
    sys.stdout.write(render_test_report(test_findings))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
