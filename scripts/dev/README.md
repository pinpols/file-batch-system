# 开发辅助脚本

这里放只服务本地开发、调试和临时验证的脚本。

- `sonar-scan.sh`：本地 Sonar 全量/增量扫描入口；增量报告由 `sonar-incremental-report.py` 按 Git 变更行生成。
- `sonar_quality_gate.py`：核验 Sonar Quality Gate 配置与本次分析结果；仅在指标无数据且查询证据符合 Sonar 的小变更/零热点规则时，接受被忽略的条件。
- `annotate-sonar-report.py`：辅助标注 Sonar 报告。
- `lean-loc-report.py`：生成低水分代码量统计，主指标按逻辑语句/配置项计数。
- `trigger-process-demo.sh`：本地触发 PROCESS demo 数据链路。

`sql/` 下是这些开发脚本使用的配套 SQL。
