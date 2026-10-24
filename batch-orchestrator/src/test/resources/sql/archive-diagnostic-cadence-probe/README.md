# 归档诊断成本手动探针

入口：`ArchiveDiagnosticCadenceProbeIT`，仅明确指定 `-Dtest` 时运行；没有新增跳过条件、依赖或永久环境。

```sh
./mvnw -o -ntp -pl batch-orchestrator -am \
  '-Dtest=ArchiveDiagnosticCadenceProbeIT' \
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- 复用现有归档 IT 的最小 Job/旧成功实例模式，创建 29 个成功实例和同租户、同批父子链。实际批次配置为 10，按 10/10/9 完成。
- 每对使用同一构建、schema、角色、索引、实例形状；A 调用真实 archiveOnce 三次，B 调用真实 scheduler.archive 一次。三个 A/B 配对交替顺序。
- 数据准备、ANALYZE 和相同 EXPLAIN 暖缓存在计时之外。保存真实 MappedStatement/BoundSql/ParameterHandler 的计划与绑定值；候选含 FOR UPDATE，使用可写事务并回滚释放行锁。
- 测量真实 Mapper 诊断/候选查询、首条 SQL 到事务完成的批耗时、总耗时与实际实例吞吐；冷热 instance 完整 JSON 必须逐行相等，旧实例及本轮清理必须为空。
- 自动定时入口仅在该探针配置中设 cron=-，避免后台归档与手动入口竞争；被测归档服务和调度业务方法保持启用，测试本身不会被跳过。全局发现其他旧实例即拒绝，不能删掉它们凑样本。
- 输出在 target/archive-diagnostic-cadence-probe/<UUID>/：PG版本、实际角色/RLS/索引/完整Flyway history、JVM可见资源、每臂计划/SQL/参数/原始计量。PG容器资源与主机争用须另记录；一次120秒查询保护预算不是生产SLA。
- AbstractIntegrationTest 默认 Testcontainers 角色可能为超级角色，实际值随报告保存；该结果不能证明应用等价角色隔离、分布式 ShedLock、多分区或目标生产容量。
- 本探针只测多批诊断成本，不复制完整树/DQ/回滚夹具。完整树和失败保热独立复用 B191 的 ArchiveColdStorageIntegrationTest 17/17：原件位于 tmp/log-artifacts/coordination/20261023-final-closure/remaining-conventions/b191-before-clean-reports.tar.gz，源身份见 b191-governance-source-before.json。来源改变后应重新验证，不能把旧报告无条件外推。
- 29 行是局部微基准，不能关闭代表性积压吞吐或 EX-01/03/04；若相对耗时被噪声覆盖，应照实报告，不用 API p95/500ms 预算或最快样本宣称性能通过。

