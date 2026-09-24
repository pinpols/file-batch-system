# 开发辅助脚本

这里放只服务本地开发、调试和临时验证的脚本。

- `sonar-scan.sh`：本地 Sonar 全量/增量扫描入口；增量报告由 `sonar-incremental-report.py` 按 Git 变更行生成。
- `annotate-sonar-report.py`：辅助标注 Sonar 报告。
- `lean-loc-report.py`：生成低水分代码量统计，主指标按逻辑语句/配置项计数。
- `trigger-process-demo.sh`：本地触发 PROCESS demo 数据链路。

`sql/` 下是这些开发脚本使用的配套 SQL。
