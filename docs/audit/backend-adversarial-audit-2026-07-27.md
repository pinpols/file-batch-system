# 后端对抗性深度审计报告

日期：2026-07-27
范围：BFS 后端全模块、数据库迁移、Helm 部署边界、Docker/Testcontainers 验证链路。
基线：本地 `main`，审计开始时与 `origin/main` 同步。

## 结论

本轮未发现新的 P0。发现并修复 1 个 P1：`retry_schedule` 的状态推进缺少租户条件和当前状态 CAS，旧调度实例或错误租户参数可能覆盖重试状态。修复已同时覆盖 Mapper、服务调用方、租户 SQL 架构守护和真实 PG IT。

其余高风险点已核实为已有设计或部署证据缺口，不在本轮改变业务语义：

- Redis 配额后端故障时 fail-open，业务继续但配额约束暂时失效；已有 WARN、熔断和切回 database runbook，仍需生产告警验证。
- Atomic 生产隔离依赖生产 Helm overlay；基础 `values.yaml` 保持开发兼容，发布必须使用生产 overlay 并保持 `productionIsolationRequired=true`。
- RLS-bypass 查询只用于内部调度器先解析租户，再绑定 RLS 上下文；未发现 Console 路由暴露该 Mapper。
- 备份/PITR、Kafka/PG 主备切换等属于运维落地证据，仓库已有脚本和 runbook，但不能由静态代码审计替代真实演练。

## 已修复问题

### P1：retry_schedule 状态推进缺少租户与 CAS

旧实现的 `markSuccess`、`markFailed`、`resetToWaiting` 仅按主键更新，`markRunning` 虽有状态条件但没有租户条件。这样会削弱数据库层租户防线，也允许迟到的旧调度结果覆盖新状态。

修复内容：

- 所有状态更新增加 `tenant_id` 条件。
- 所有状态更新增加 `fromStatus` 条件，形成显式状态 CAS。
- 服务层将实体租户和预期状态传入 Mapper。
- `MapperXmlTenantGuardArchTest` 删除对应的历史豁免。
- 新增真实 PG 回归用例：错误租户不能推进状态；状态已变更后旧状态不能推进成功。

代码位置：

- [RetryScheduleMapper.xml](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/resources/mapper/RetryScheduleMapper.xml:81)
- [RetryScheduleMapper.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/mapper/RetryScheduleMapper.java:18)
- [DefaultRetryGovernanceService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java:230)
- [RetryScheduleIntegrationTest.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryScheduleIntegrationTest.java:112)

## 审查覆盖

- 租户隔离：RLS session 注入、strict/fail-fast、Mapper tenant guard、内部 bypass 边界。
- 状态一致性：Outbox、lease、retry、终态 CAS、重试/死信链路。
- 安全边界：Console/Trigger 鉴权、CSRF、内部密钥、Atomic isolation、NAS 沙箱、SSRF/响应大小限制。
- 资源治理：NAS 派发有界线程池、超时和拒绝策略；Console 周期线程均有 ContextClosed/PreDestroy 关闭钩子。
- 数据库与迁移：Flyway 版本连续性、MyBatis XML、RLS 业务表闭世界规则、归档边界。
- 运行和运维：Redis/PG/Kafka/MinIO 依赖降级、Helm overlay、备份/PITR 和故障演练证据。

## 验证结果

通过：

- 全仓 `test-compile + PMD + Spotless`：成功。
- 定向单测：`DefaultRetryGovernanceServiceTest`，19/19 通过。
- Docker Testcontainers 真实 PG/Kafka/MinIO/Valkey IT：`RetryScheduleIntegrationTest`，9/9 通过。
- 定向 Maven reactor 总计：28 tests，0 failures，0 errors。
- 真实迁移从空库成功执行 192 个迁移，最终版本 v193。
- 源码迁移目录未发现重复版本号。
- `git diff --check`：通过。

首次增量 IT 曾因旧 `target/classes` 残留已删除的 V192 文件而误报 Flyway 重复版本；执行 Maven `clean` 后复验通过。该现象不是源码迁移重复，但验证迁移变更时必须使用 clean 工作区或 CI 的干净构建。

## 残余风险与上线前动作

1. Redis quota fail-open 需要接入明确告警阈值，并演练切换 `batch.quota.runtime-store=database`。
2. 生产部署必须固定使用 Helm 生产 overlay，禁止仅使用基础 values；CI 应继续验证 isolation、ServiceAccount、Secret 和 NetworkPolicy。
3. staging 需要真实执行一次 PG PITR、Kafka 不可用、worker 崩溃续跑和对象存储失败恢复，并保留 RTO/RPO 证据。
4. 保持 `mvn clean` 作为迁移/分支切换后的验证入口，避免增量 `target` 污染结果。

本轮没有为上述明确的产品降级或运维证据问题强行改动核心语义，避免把批量运行控制面扩张成新的治理平台。
