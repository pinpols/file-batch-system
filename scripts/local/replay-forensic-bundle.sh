#!/usr/bin/env bash
# =========================================================
# replay-forensic-bundle.sh — 拉 forensic 证据包 → 本地 sim 回放 → SQL 比对
#
# ADR-022 forensic_export 已能导生产证据包(job_instances + batch_day_audits +
# manifest + sha256),本脚本补"回放"侧:
#   1. 拉 forensic bundle zip(从 OSS / 本地路径)
#   2. 解包 → 还原到本地 sim 的 batch_business 库(临时 namespace)
#   3. 跑当前代码做 replay(同 bizDate + 同租户)
#   4. SQL 比对:replay 结果 vs 证据包里的真生产结果,字段级 diff
#
# 用法:
#   bash scripts/local/replay-forensic-bundle.sh <bundle.zip>
#   bash scripts/local/replay-forensic-bundle.sh --since=yesterday  # 拉 OSS 最新
#
# 价值:每周用昨日生产数据本地回放,看新代码是否会改判(早期发现回归)。
#
# 待补:
#   - 解包 / sha256 校验
#   - schema 映射(forensic 用 archive.*_archive,sim 用 batch.*)
#   - SQL 比对 harness(用 jq / sqlite-utils)
#   - 报告输出(html / markdown)
#
# 前置:
#   - sim 必须已起(`scripts/sim/02-start-sim.sh`)
#   - console-api 跑在 18080(`scripts/local/start-all.sh`)
# =========================================================

echo "TODO: r3 validation-infra — 实现 forensic replay。骨架已建,等填充。"
echo "依赖:ForensicExportService.findLog + zip 工具 + SQL diff harness。"
exit 1
