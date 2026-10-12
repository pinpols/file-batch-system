# 开发辅助脚本

这里放只服务本地开发、调试和临时验证的脚本。

- `sonar-scan.sh`：本地 Sonar 全量/增量扫描入口；增量报告由 `sonar-incremental-report.py` 按 Git 变更行生成。
- `sonar_quality_gate.py`：核验 Sonar Quality Gate 配置与本次分析结果；当前固定 SonarQube 24.04，coverage/duplication 条件被省略时均要求 `new_lines < 20`，不依赖 `ignoredConditions` 标记；热点审查率条件缺失则要求 `new_security_hotspots = 0`。其它缺项、未知状态和门禁失败均拒绝，并报告缺失条件及对应证据要求。升级 Sonar 镜像时须复核小变更阈值语义。具体契约和回归测试见 [`docs/runbook/sonar.md`](../../docs/runbook/sonar.md) 与 `scripts/dev/tests/test_sonar_quality_gate.py`。
- `annotate-sonar-report.py`：辅助标注 Sonar 报告。
- `lean-loc-report.py`：生成低水分代码量统计，主指标按逻辑语句/配置项计数。
- `trigger-process-demo.sh`：本地触发 PROCESS demo 数据链路。

`sql/` 下是这些开发脚本使用的配套 SQL。
