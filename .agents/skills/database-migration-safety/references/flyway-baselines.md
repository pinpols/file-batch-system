# Flyway基线与恢复边界

## MIG-1 固定部署模式再判可修改范围

区分已发布库原位升级、全新空库切换及隔离演练；不能因当前守卫放行注释/COMMENT ON就称旧库checksum兼容。当前迁移静态检查允许何种差异需读`flyway_sql_diff.py`与入口；注释也影响Flyway checksum，不自动repair或重建现有库。

多工作树/合并时核对有效迁移版本、依赖顺序、源码与编译资源/运行JAR，旧target资源不能当源码。迁移lint、真实空库migrate、存量升级和恢复是不同验证。

## MIG-2 结构变化的全部消费者

从热表、归档/分区、Mapper、约束、ON CONFLICT、RLS及清理SQL建立影响表。运行恢复要保留幂等ledger/receipt及版本围栏；只恢复业务主表会打开重复执行窗口。不要仅按日期清理去重记录或将旧手册的六个月假设升级为通用安全窗口。

## 当前来源

- [validate-flyway-schema.sh](../../../../scripts/ci/validate-flyway-schema.sh)
- [flyway_sql_diff.py](../../../../scripts/ci/flyway_sql_diff.py)
- [database-schema-governance.md](../../../../docs/design/database-schema-governance.md)
- [pg-table-partitioning.md](../../../../docs/runbook/pg-table-partitioning.md)
- [dedup-ledger-retention.md](../../../../docs/runbook/dedup-ledger-retention.md)

只沿本次任务读取必要章节；脚本参数和环境事实每次从现行文件确认。本文不授权部署、迁移、清理、压测或外部发送。
