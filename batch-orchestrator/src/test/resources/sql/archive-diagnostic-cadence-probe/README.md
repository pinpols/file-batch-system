# 归档诊断成本手动探针

入口：`ArchiveDiagnosticCadenceProbeIT`，仅明确指定 `-Dtest` 时运行；没有新增跳过条件、依赖或永久环境。

```sh
./mvnw -o -ntp -pl batch-orchestrator -am \
  '-Dtest=ArchiveDiagnosticCadenceProbeIT' \
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- 默认冒烟为29个成功实例、批10，按10/10/9完成。代表性积压显式传入 `-Darchive.probe.instances=50000 -Darchive.probe.batch-size=1000`，每臂50个批次；三组交替对照共六臂。参数约束1–50000实例、1–1000批大小，且总批数不得超过真实调度器单轮50批预算。复用现有最小Job/旧成功实例和同租户、同批父子链，不关闭约束。
- 每对使用同一构建、schema、角色、索引、实例形状；A 按实际批数调用真实 archiveOnce，B 调用真实 scheduler.archive 一次。三个 A/B 配对交替顺序。
- 数据准备、ANALYZE 和相同 EXPLAIN 暖缓存在计时之外。保存真实 MappedStatement/BoundSql/ParameterHandler 的计划与绑定值；候选含 FOR UPDATE，使用可写事务并回滚释放行锁。
- 测量真实 Mapper 诊断/候选查询、首条SQL到事务完成的批耗时、总耗时与实际实例吞吐；每100ms采样同一隔离库的活跃客户端等待状态，采样失败导致测试失败。采样次数不是精确等待时长，后台调度与宿主机争用另记。冷热instance完整JSON必须逐行相等，旧实例及本轮清理必须为空。
- 归档事务在查询前使用实际Mapper的 `set_config('jit', 'off', true)`，EXPLAIN使用同一映射SQL和同一物理连接应用该设置；A/B双方保持一致。独立设置用例核对同一物理连接提交、回滚后的恢复，不用另一条连接代证；不改PG全局参数。原JIT开启的未完成大样本及计划仍保留为失败/中断诊断证据。
- 自动定时入口仅在该探针配置中设 cron=-，避免后台归档与手动入口竞争；被测归档服务和调度业务方法保持启用，测试本身不会被跳过。全局发现其他旧实例即拒绝，不能删掉它们凑样本。
- 输出在 target/archive-diagnostic-cadence-probe/<UUID>/：PG版本、实际角色/RLS/索引/完整Flyway history、JVM可见资源、每臂计划/SQL/参数/原始计量。PG容器资源与主机争用须另记录；一次120秒查询保护预算不是生产SLA。
- AbstractIntegrationTest 默认 Testcontainers 角色可能为超级角色，实际值随报告保存；该结果不能证明应用等价角色隔离、分布式 ShedLock、多分区或目标生产容量。
- 本探针只测根实例多批诊断成本，不复制完整树/DQ/回滚夹具。B201随后独立执行既有五类归档组合47/47（含完整树/DQ/位点冲突回滚及新增执行时间字段），与本探针去重共49例；结果、源身份与容量边界见 docs/verifications/wdi-local-closure-2026-10-25.md。来源改变后应按影响重新验证，不能无条件外推旧报告。
- 29 行是局部微基准，不能关闭代表性积压吞吐或 EX-01/03/04；若相对耗时被噪声覆盖，应照实报告，不用 API p95/500ms 预算或最快样本宣称性能通过。

