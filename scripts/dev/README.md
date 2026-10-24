# 开发辅助脚本

这里放只服务本地开发、调试和临时验证的脚本。

- `sonar-scan.sh`：本地 Sonar 全量/增量扫描入口；增量报告由 `sonar-incremental-report.py` 按 Git 变更行生成。
- `sonar_quality_gate.py`：核验 Sonar Quality Gate 配置与本次分析结果；当前固定 SonarQube 24.04，coverage/duplication 条件被省略时均要求 `new_lines < 20`，不依赖 `ignoredConditions` 标记；热点审查率条件缺失则要求 `new_security_hotspots = 0`。其它缺项、未知状态和门禁失败均拒绝，并报告缺失条件及对应证据要求。升级 Sonar 镜像时须复核小变更阈值语义。具体契约和回归测试见 [`docs/runbook/sonar.md`](../../docs/runbook/sonar.md) 与 `scripts/dev/tests/test_sonar_quality_gate.py`。
- `annotate-sonar-report.py`：辅助标注 Sonar 报告。
- `lean-loc-report.py`：生成低水分代码量统计，主指标按逻辑语句/配置项计数。
- `trigger-process-demo.sh`：本地触发 PROCESS demo 数据链路。

`sql/` 下是这些开发脚本使用的配套 SQL。

## 源码维护工具

- [异常日志补充工具](inject_swallowed_exception_logs.py)：批量为指定 catch 候选补充日志；运行前检查源码范围，运行后必须复核 git diff。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [annotate-sonar-report.py](annotate-sonar-report.py) | 为 Sonar CSV 报告补充治理范围与候选标记。 |
| [lean-loc-report.py](lean-loc-report.py) | 按生产、测试与辅助资产生成代码量统计。 |
| [sonar-incremental-report.py](sonar-incremental-report.py) | 从 Sonar 全量分析结果中导出 Git 变更行级增量报告。 |
| [sonar-scan.sh](sonar-scan.sh) | 本地统一 SonarQube 扫描 + 导出报告。 |
| [sonar_quality_gate.py](sonar_quality_gate.py) | 核对 Sonar 实际评估条件与配置的 Quality Gate。 |
| [sql/sonar-reset-password-flag.sql](sql/sonar-reset-password-flag.sql) | 供 [sonar-scan.sh](sonar-scan.sh) 使用的配套 SQL。 |
| [sql/trigger-process-demo-business.sql](sql/trigger-process-demo-business.sql) | 供 [trigger-process-demo.sh](trigger-process-demo.sh) 使用的配套 SQL。 |
| [sql/trigger-process-demo-platform.sql](sql/trigger-process-demo-platform.sql) | 供 [trigger-process-demo.sh](trigger-process-demo.sh) 使用的配套 SQL。 |
| [sql/trigger-process-demo-result.sql](sql/trigger-process-demo-result.sql) | 供 [trigger-process-demo.sh](trigger-process-demo.sh) 使用的配套 SQL。 |
| [trigger-process-demo.sh](trigger-process-demo.sh) | 触发真实 Process 作业并核查处理结果。 |
