# 开发辅助脚本

这里放只服务本地开发、调试和临时验证的脚本。

- `sonar-scan.sh`：本地 Sonar 全量/增量扫描入口；增量报告由 `sonar-incremental-report.py` 按 Git 变更行生成。
- `sonar_quality_gate.py`：核验 Sonar Quality Gate 配置与本次分析结果；要求所有已配置条件均被评估，除非覆盖率/重复率条件有 `ignoredConditions=true` 和 `new_lines < 20` 证据，或热点审查率条件缺失且 `new_security_hotspots = 0`。其它缺项、未知状态和门禁失败均拒绝，并报告真实失败状态/指标。具体契约和回归测试见 [`docs/runbook/sonar.md`](../../docs/runbook/sonar.md) 与 `scripts/dev/tests/test_sonar_quality_gate.py`。
- `annotate-sonar-report.py`：辅助标注 Sonar 报告。
- `lean-loc-report.py`：生成低水分代码量统计，主指标按逻辑语句/配置项计数。
- `trigger-process-demo.sh`：本地触发 PROCESS demo 数据链路。

`sql/` 下是这些开发脚本使用的配套 SQL。
