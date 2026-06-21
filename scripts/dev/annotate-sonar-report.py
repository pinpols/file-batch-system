#!/usr/bin/env python3
"""为 reports/sonar/latest/sonar-report.csv 增加两列：

- action — FIXED / ANNOTATION / DEFERRED / SKIP_FP / SKIP_SPI / SKIP_DOMAIN /
            SKIP_THRESHOLD / SKIP_BULK / KEEP
- note   — 中文说明本次为何处理 / 为何不处理

读 reports/sonar/latest/sonar-report.csv，写
reports/sonar/latest/sonar-report-annotated.csv。
"""

from __future__ import annotations
import csv
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "reports", "sonar", "latest", "sonar-report.csv")
DST = os.path.join(ROOT, "reports", "sonar", "latest", "sonar-report-annotated.csv")


# ── 真实修复的精确位置（rule, component basename or path-tail, line） ────────
FIXED = {
    # ── BLOCKER S2699 缺断言测试 ──
    ("java:S2699", "RedisQuotaRuntimeStateIntegrationTest.java", "86"):
        ("FIXED", "改用 assertThatCode().doesNotThrowAnyException() 显式断言"),
    ("java:S2699", "CronExpressionAdapterTest.java", "77"):
        ("FIXED", "改用 assertThatCode().doesNotThrowAnyException() 显式断言"),
    ("java:S2699", "AbstractWorkerLoopTest.java", "112"):
        ("FIXED", "改用 assertThatCode().doesNotThrowAnyException() 显式断言"),
    ("java:S2699", "ProcessMetricsTest.java", "13"):
        ("FIXED", "noop 路径包成 assertThatCode().doesNotThrowAnyException()"),

    # ── BLOCKER S3516 ──
    ("java:S3516", "ConsoleWebhookService.java", "105"):
        ("FIXED", "删除恒等 if normalized==\"*\" return 死分支"),

    # ── S2259 真 NPE 修复 ──
    ("java:S2259", "DatabaseQuotaRuntimeStateService.java", "104"):
        ("FIXED", "refreshState 返回 null 守卫，新增 effectivePolicy 消除 policy nullable"),
    ("java:S2259", "DatabaseQuotaRuntimeStateService.java", "111"):
        ("FIXED", "refreshState 返回 null 守卫"),
    ("java:S2259", "DatabaseQuotaRuntimeStateService.java", "270"):
        ("FIXED", "policy null fallback 引入 effectivePolicy 局部变量"),
    ("java:S2259", "RetryDispatchStep.java", "51"):
        ("FIXED", "context==null 早返"),
    ("java:S2259", "ConsoleAuthController.java", "60"):
        ("FIXED", "principal 强转改 pattern-match + 新增 error.auth.principal_missing i18n key"),

    # ── S112 controller 边界 ──
    ("java:S112", "TriggerManagementController.java", "98"):
        ("FIXED", "throws Exception 收窄到 throws SchedulerException"),
    ("java:S112", "TriggerManagementController.java", "103"):
        ("FIXED", "throws Exception 收窄到 throws SchedulerException"),
    ("java:S112", "TriggerManagementController.java", "109"):
        ("FIXED", "throws Exception 收窄到 throws SchedulerException"),

    # ── S3776 Tier 1（CC ≥ 30） ──
    ("java:S3776", "SqlTemplateExportSqlValidator.java", "86"):
        ("FIXED", "Tier1：拆 collectInitialBodies / rejectStarItems / enqueueNestedBodies"),
    ("java:S3776", "SqlTransformComputeSqlValidator.java", "82"):
        ("FIXED", "Tier1：同 SqlTemplateExportSqlValidator 模式"),
    ("java:S3776", "BatchStartupSelfCheck.java", "49"):
        ("FIXED", "Tier1：每检查项独立 method，主方法变线性串接"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "461"):
        ("FIXED", "Tier1：抽 validateStepRow + validateStageCode/RetryPolicy/PipelineLink"),
    ("java:S3776", "WorkflowNodePayloadBuilder.java", "95"):
        ("FIXED", "Tier1：拆 findLatestSuccessPartition / mergeWhitelistedOutputFields / fallbackFileIdLookup"),
    ("java:S3776", "DefaultConsoleFileTemplateApplicationService.java", "170"):
        ("FIXED", "Tier1：抽 buildBasic/Format/Query/Runtime/Security 子方法 + coalesceXxx 工具"),
    ("java:S3776", "LaunchBatchDayService.java", "104"):
        ("FIXED", "Tier1：拆 insertNewBatchDay / updateExistingBatchDay + BatchDayUpsertContext/UpdatePlan record"),
    ("java:S3776", "DefaultConsoleJobDefinitionExcelApplicationService.java", "351"):
        ("FIXED", "Tier1：拆 validateRow + validateIdentity/Existing/Enum/Numeric/FK/Json"),

    # ── S3776 Tier 2 ──
    ("java:S3776", "ConfigPackageExcelValidator.java", "268"):
        ("FIXED", "Tier2：抽 validateJobRow + 引入 SheetValidationHelpers 工具"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "322"):
        ("FIXED", "Tier2：抽 validateChannelRow"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "376"):
        ("FIXED", "Tier2：抽 validateRoutingRow"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "417"):
        ("FIXED", "Tier2：抽 validatePipelineRow"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "602"):
        ("FIXED", "Tier2：抽 validateWfDefRow"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "646"):
        ("FIXED", "Tier2：抽 validateWfNodeRow"),
    ("java:S3776", "ConfigPackageExcelValidator.java", "714"):
        ("FIXED", "Tier2：抽 validateWfEdgeRow + requireNodeRef"),
    ("java:S3776", "LaunchBatchDayService.java", "61"):
        ("FIXED", "Tier2：抽 logUpsertRetry 消除 8 个 request==null?... ternary"),
    ("java:S3776", "DefaultPartitionDispatchService.java", "91"):
        ("FIXED", "Tier2：拆 dispatchInitialDagNodes / dispatchByPlan / transitionToRunning/Waiting + DispatchOutcome record"),
    ("java:S3776", "ConfigPackageExcelWorkbookWriter.java", "363"):
        ("FIXED", "Tier2：拆 writeGuideHeader/Row + GuideStyles record + guideOrEmpty 工具"),
}


# ── 按规则的默认处理 ──────────────────────────────────────────────────────
RULE_DEFAULTS = {
    "java:S2259": (
        "ANNOTATION",
        "Texts.hasText / Guard.require* 已加 @Contract（JetBrains），下次扫描应自动消除；如未消则单点修",
    ),
    "java:S112": (
        "KEEP",
        "Worker 阶段内部 helper：顶层 stage execute() 已统一 catch 包成 BizException / XxxStageResult.failure，内部 throws Exception 是合理'让上层一把抓'",
    ),
    "java:S3776": (
        "SKIP_THRESHOLD",
        "CC ≤ 21 自然过限，建议在 Sonar Quality Profile 把阈值放宽到 20；CC ≥ 22 不在 Tier 1/2 名单的为 DEFERRED",
    ),
    "java:S1192": (
        "SKIP_BULK",
        "字符串常量化噪音 256 条；DB 列名 / JSON key / 配置 key 已在 BatchFileConstants/PipelineRuntimeKeys/RESERVED_PARAMS 收敛；剩余多为日志模板/测试 fixture，提常量得不偿失",
    ),
    "java:S6213": (
        "SKIP_BULK",
        "_ 命名变量 56 条；批量改对运行时无影响，建议下次代码格式化统一时顺手",
    ),
    "java:S5838": (
        "SKIP_BULK",
        "MINOR：assertThat 链式断言增强建议；不影响正确性",
    ),
    "java:S5778": (
        "SKIP_BULK",
        "MAJOR：assertThatThrownBy 应只断言抛异常的语句；批量改风险高，下次改测试时顺手",
    ),
    "java:S6068": (
        "SKIP_BULK",
        "MINOR：assertThat 默认描述；信息量低",
    ),
    "java:S5853": (
        "SKIP_BULK",
        "MINOR：JSR-305 annotations consistency；项目已统一",
    ),
    "java:S135": (
        "SKIP_BULK",
        "MINOR：避免单方法多 break/continue；多在解析状态机里，是合理的",
    ),
    "java:S6813": (
        "SKIP_BULK",
        "MAJOR：字段注入改构造器；项目大量使用 Lombok @RequiredArgsConstructor，已大体一致；剩余多为测试夹具",
    ),
    "java:S3358": (
        "SKIP_BULK",
        "MAJOR：嵌套 ternary；不影响正确性，重构 ROI 低",
    ),
    "java:S6809": (
        "SKIP_BULK",
        "CRITICAL：@Cacheable 等不能从同类内部调用；项目已通过 selfProvider 模式规避",
    ),
    "java:S1854": (
        "SKIP_BULK",
        "MAJOR：dead store；多为防御赋值，删除收益小",
    ),
    "java:S1481": (
        "SKIP_BULK",
        "MINOR：未使用本地变量",
    ),
    "java:S1452": (
        "SKIP_BULK",
        "CRITICAL：通配符返回类型；项目 API 已稳定，重构有兼容风险",
    ),
    "java:S6541": (
        "SKIP_BULK",
        "INFO：方法 brain method 提示",
    ),
    "java:S2386": (
        "SKIP_BULK",
        "MINOR：public static array/collection 字段 final，项目均为不可变构造",
    ),
    "java:S108": (
        "SKIP_BULK",
        "MAJOR：空 catch；多为故意忽略，已加 ignored 命名",
    ),
    "java:S2925": (
        "SKIP_BULK",
        "MAJOR：测试中 Thread.sleep；并发测试必要",
    ),
    "java:S1172": (
        "SKIP_BULK",
        "MAJOR：未使用方法参数；多为接口契约要求",
    ),
    "java:S1125": (
        "SKIP_BULK",
        "MINOR：boolean 字面量冗余",
    ),
    "java:S6885": (
        "SKIP_BULK",
        "MAJOR：使用 Map.Entry 替代独立查询",
    ),
    "java:S125": (
        "SKIP_BULK",
        "MAJOR：注释掉的代码；保留是历史脉络",
    ),
    "java:S1168": (
        "SKIP_BULK",
        "MAJOR：返回 null 集合；多在 mapper 层，与 MyBatis 契约一致",
    ),
    "java:S1130": (
        "SKIP_BULK",
        "MINOR：throws 声明的异常未抛出",
    ),
    "java:S3077": (
        "SKIP_BULK",
        "MINOR：non-primitive volatile 字段",
    ),
    "java:S1874": (
        "SKIP_BULK",
        "MINOR：使用 deprecated API；ADR-010 路径 HttpOrchestratorTriggerAdapter 是已知遗留",
    ),
    "java:S6204": (
        "SKIP_BULK",
        "MAJOR：Stream.toList() 替代 collect(toList())",
    ),
    "java:S1659": (
        "SKIP_BULK",
        "MINOR：多个变量同行声明",
    ),
    "java:S1126": (
        "SKIP_BULK",
        "MINOR：if-then-else 可改 single return",
    ),
}


# ── S112 的特殊源（注意 KEEP 默认是 worker 内部）──────────────────────────
S112_OVERRIDES = {
    "ConsoleSecurityConfiguration.java":
        ("SKIP_FP", "Spring Security WebSecurityConfigurerAdapter 风格；SecurityFilterChain bean 必须 throws Exception"),
    "ExportDataPlugin.java":
        ("SKIP_SPI", "Plugin SPI 接口设计上需要宽泛 throws Exception"),
    "ImportLoadPlugin.java":
        ("SKIP_SPI", "Plugin SPI 接口设计上需要宽泛 throws Exception"),
    "ExportFormatStrategy.java":
        ("SKIP_SPI", "Plugin SPI 接口设计上需要宽泛 throws Exception"),
    "FormatParser.java":
        ("SKIP_SPI", "Plugin SPI 接口设计上需要宽泛 throws Exception"),
    "DnsResolveGuard.java":
        ("SKIP", "实际是 S1126/if-then-else 提示，不是 S112"),
    "AbstractTaskConsumer.java":
        ("KEEP", "abstract 模板方法基类内部 throws Exception，子类按需窄化"),
}


# ── S3776 残余分级 ──────────────────────────────────────────────────────
def s3776_classify(message: str, component: str) -> tuple[str, str]:
    """从 message 里抽 'Cognitive Complexity from N to ...' 分级。"""
    import re
    m = re.search(r"Cognitive Complexity from (\d+)", message)
    cc = int(m.group(1)) if m else 0
    base = component.split("/")[-1]

    # CC ≥ 22 不在 Tier 1/2 → DEFERRED
    if cc >= 22:
        # 自然边界：format parser / SQL grammar walker / 状态机
        natural = (
            "DelimitedFormatParser.java", "FixedWidthFormatParser.java",
            "JsonFormatParser.java", "XmlFormatParser.java", "ExcelFormatParser.java",
        )
        if base in natural:
            return ("KEEP_DOMAIN",
                    f"CC={cc}：format parser 复杂度来自 CSV/固定宽度/XML 语法本身；硬拆破坏单一连贯状态机")
        return ("DEFERRED",
                f"CC={cc}：业务关键路径，下次迭代到此功能时'经过即重构'")

    # CC 16-21
    return ("SKIP_THRESHOLD",
            f"CC={cc}：边缘越限，建议把 Sonar Quality Profile S3776 阈值从 15 放宽到 20")


def annotate(rule: str, component: str, line: str, message: str) -> tuple[str, str]:
    """主分派：按 rule + 精确 line 查表，否则按规则默认。"""
    base = component.split("/")[-1]

    # 1. 精确命中
    key = (rule, base, line)
    if key in FIXED:
        return FIXED[key]

    # 2. S112 特殊源
    if rule == "java:S112" and base in S112_OVERRIDES:
        return S112_OVERRIDES[base]

    # 3. S3776 分级
    if rule == "java:S3776":
        return s3776_classify(message, component)

    # 4. 规则默认
    if rule in RULE_DEFAULTS:
        return RULE_DEFAULTS[rule]

    # 5. 未登记
    return ("SKIP_BULK", "未单独评估，归入批量低优先")


def main():
    fixed_count = 0
    annotation_count = 0
    deferred_count = 0
    skip_count = 0
    keep_count = 0

    with open(SRC, encoding="utf-8") as fin, open(DST, "w", encoding="utf-8", newline="") as fout:
        reader = csv.reader(fin)
        writer = csv.writer(fout)
        header = next(reader)
        writer.writerow(header + ["action", "note"])
        for row in reader:
            severity, type_, component, line, rule, message, *_ = row
            action, note = annotate(rule, component, line, message)
            writer.writerow(row + [action, note])
            if action == "FIXED":
                fixed_count += 1
            elif action == "ANNOTATION":
                annotation_count += 1
            elif action == "DEFERRED":
                deferred_count += 1
            elif action == "KEEP" or action.startswith("KEEP_"):
                keep_count += 1
            else:
                skip_count += 1

    print(f"FIXED:      {fixed_count:4d}  代码已改")
    print(f"ANNOTATION: {annotation_count:4d}  helper 加 @Contract，下次扫描应自动消")
    print(f"DEFERRED:   {deferred_count:4d}  下次迭代顺手收")
    print(f"KEEP:       {keep_count:4d}  设计内合理，不动")
    print(f"SKIP:       {skip_count:4d}  低 ROI，整体放过")
    print(f"\n输出：{DST}")


if __name__ == "__main__":
    main()
