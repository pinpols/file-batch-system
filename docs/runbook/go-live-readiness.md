# 后端交付上线 · 完整性就绪检查表(Go-Live Readiness)

> 目标:把"已有的强测试覆盖"跑给上线看 + 补"上线特有、CI/sim 覆盖不到"的演练,逐维度签字。
> 适用参数(本次):**目标量级 5–20 jobs/s**(逼近单机控制面瓶颈)、**RTO < 2h / RPO < 15min**(常规 PITR + 异步备库)。
> 维护:每次上线复用本表;改动验收门同步本文件。

## 0. 已验证的测试覆盖(2026-06 深审实查,**不需重做,需在 staging 跑给上线看**)

| 维度 | 已有资产(实查存在) | 状态 |
|---|---|---|
| 功能完整 | 648 单测 + 114 IT + 27 e2e(`*E2eIT`)+ sim 25 阶段(import/export/process/dispatch/trigger/atomic)+ sim-4day | ✅ full-ci-gate 门禁 |
| **数据一致(关键约束)** | `ConcurrentTaskClaimIntegrationTest`(防双 claim)/ `ConcurrentTaskFinishIntegrationTest`(CAS 防双 finish)/ `OutboxEventToKafkaDispatch`·`OutboxPublish`(outbox 精确一次)/ `SqlConsistencyIntegrationTest` / `JobRetryFlowIntegrationTest` | ✅ IT 覆盖 |
| 多租隔离 | batch.*:`MultiTenantIsolationIntegrationTest` + `MapperXmlTenantGuardArchTest`(静态扫 mapper);biz.* RLS:`RlsTenantIsolationIntegrationTest`·`RlsStrictModePreflight`·`RlsTenantSession`·`RlsPhaseAMigrationCoverage`;路由:`BusinessMultiShardRouting*` | ✅ IT 覆盖(batch 列 + biz RLS + 分片路由) |
| 韧性 | `WorkerHeartbeatTimeoutScheduler`·`PartitionLeaseReclaim`·`StaleCompensationCommandReconciler`(lease/超时回收)、sim **stage25 checkpoint-crash 续跑**、3×`*ToxicIT`(混沌注入)、`DeadLetterController`(DLQ) | ✅ 逻辑层覆盖(端到端全 worker 组崩溃见 §2-C) |
| 容量 | load-tests 10 Gatling 场景(JobLaunch / CapacityBaseline / ControlPlaneMixedPressure / WorkerTaskLifecycle / SchedulingBacklog…)+ SLO 旋钮 `slo.write.p95ms` / `slo.read.p99ms` / `slo.maxErrorPct` / `users.peak` | ✅ 工具就绪(需跑到靶点,见 §2-B) |
| 迁移 | Flyway(platform)+ `ArchiveSchemaDriftCheck`(热表↔archive 镜像 fail-fast)+ 月分区 V172/V173 | ✅ 启动期门禁 |

> **结论**:测试代码覆盖**已很完整**;上线缺的主要是**操作演练 + staging 签字**,不是补单测。下面只列"真缺口"。

## 1. Phase 0 — 代码冻结门(CI)
- [ ] `full-ci-gate` 全部通过(PMD/Spotless/依赖边界 + 单测 + IT 分片 + **jacoco 覆盖率棘轮**)
- [ ] `codeql` + Trivy + 依赖 CVE 扫描:**无新引入高危**(依赖 CVE vs 真引入要分清)
- [ ] `strict-verify`(本地)+ `verify-biz-shard` 真数据路由通过

## 2. Phase 1 — staging 同构验收(**生产同构,非 testcontainers**)
每项需「跑通 + 记录 + 签字」。

### A 全链路功能
- [ ] 整套 sim **连跑**(非单跑——单跑绿≠连跑绿)+ sim-4day;收尾 `98-quiesce-schedules.sh` 静默
- [ ] e2e(`*E2eIT`)对 staging URL 跑通

### B 容量 / SLO 验收(目标 5–20 jobs/s)
- [ ] load-tests 跑到 `-Dusers.peak=20`,**SLO 门**:`slo.write.p95ms=500` / `slo.read.p99ms=300` / `slo.maxErrorPct=1.0`(按需校准)
- [ ] **控制面瓶颈核对**(已知:单机~20 jobs/s,PG 写有 10–15× 余量,瓶颈是 launch 单线程消费 + report/claim 争用):峰值时记录 launch 消费 lag、claim/report 锁等待、PG 连接/写 TPS、Kafka consumer lag、outbox 积压
- [ ] 判定:目标量级下 SLO 达标且无积压发散 → 通过;若逼近 20 jobs/s 上限,记录余量并定容量上限/扩展预案

### C DR / 韧性演练(**真缺口,见`scripts/sim/dr-drill-fleet-crash.sh`**)
- [ ] **全 worker 组崩溃"精确一次"演练**:跑载荷 → kill 整组 worker → 等 lease/task 超时回收 + 重投 → 重启 worker → 断言**终态精确一次**(无重复 outbox/side-effect、单一 SUCCESS、无 job_instance 复活)。脚本 + 验收 SQL 见下。
- [ ] **PITR 恢复演练**(RTO<2h / RPO<15min):备份恢复到某时间点 → 断言已提交数据不丢(RPO)+ 恢复总耗时(RTO);流程见 §4。
- [ ] PG failover(主备切换)+ Kafka 短时不可用降级 + DLQ 重放

### D 安全签收
- [ ] 越权矩阵:batch 列级 + biz RLS(`SET LOCAL app.tenant_id` 跨租返 0 行)+ 路由分片——以 §0 的 IT 为证据,**并在 staging 真数据补一次跨租查询验证**
- [ ] **prod profile 强制 `batch.security.bypass-mode=off`**(认证/加解密/审批不放行)
- [ ] atomic worker RCE 隔离(ADR-029,shell/sql/http/storedproc 特权隔离)、SSRF/sensitive-data 拦截、Kafka topic/consumer-group ACL 防跨租漂移、密钥注入(非明文写入数据库)

### E 可观测验收
- [ ] OTel trace 端到端贯通:trigger → orchestrator → worker → report
- [ ] 关键 SLO 大盘 + 告警规则(吞吐/延迟/outbox 积压/DLQ/PG 锁/Kafka lag)
- [ ] health / liveness 探针 + 优雅停机预算实测(drain 期间 lease 保活、不丢在飞)

## 3. Phase 2 — 割接演练
- [ ] Flyway 在 **prod-sized 数据**上 dry-run:记录耗时 + 锁影响;关键约束复核 `grep -r 'on conflict'` 全量(56 处 UNIQUE 幂等语义迁移后不变)
- [ ] **回滚脚本演练**(迁移可逆 / 数据可回退)
- [ ] 配置开关核对:bypass-mode、Citus(默认关)、读写分离仅 console-api

## 4. PITR 恢复演练程序(RTO<2h / RPO<15min)
1. 记录基线:`SELECT max(updated_at), count(*) FROM batch.job_instance;` + 当前 LSN / 备份时间戳。
2. 持续写入一批已知 job(记录其 dedup_key 集合)。
3. 选一个恢复目标时间点 T(在最后一次备份后、某批写入之间)。
4. 从备份 + WAL 恢复到 T(记录开始/结束时间 → **RTO**)。
5. 断言:T 之前已提交的 job **全部存在**(无丢失);T 之后的写入丢失量 ≤ RPO 窗口(< 15min)。
6. 断言一致性:`ArchiveSchemaDriftCheck` 启动通过、outbox 无悬挂、无 job_instance 处于不可达中间态。
> 落地脚本:`scripts/sim/dr-drill-pitr.sh`(**备份工具无关**——演练逻辑/断言通用,实际恢复动作做成 `RESTORE_CMD` 钩子,你接 pgBackRest / WAL-G / 云托管 PIT(RDS/Aurora)均可)。它自动选 T0、快照 T0 前已提交集合(count + 指纹)、触发 `RESTORE_CMD` 计 RTO、断言 RPO(T0 前数据不丢)+ RTO ≤ 预算 + 恢复后无重复。仅在 DR/staging 跑。

## 5. Go / No-Go 门
| 门 | 判据 |
|---|---|
| Phase 0 | CI 全部通过 + 安全扫描清零 |
| Phase 1 | A–E 全部签字 + B 的 SLO 达标 + C 的全 worker 组崩溃精确一次通过 |
| Phase 2 | 迁移耗时可接受 + 回滚验证通过 + RTO/RPO 达标 |
| 上线 | 金丝雀/灰度无异常 + 监控基线 + 回滚预案 + 值班就位 |

## 6. 灰度 / 回滚
- 灰度:按租户或按 job 类型分批放量;每批观察 SLO 大盘 + DLQ + outbox 积压基线。
- 回滚预案:配置开关即时降级(bypass、暂停消费)、镜像回退、迁移回滚脚本;触发条件写明(错误率/延迟/积压阈值)。
