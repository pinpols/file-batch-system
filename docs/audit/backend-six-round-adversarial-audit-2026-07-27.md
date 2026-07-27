# 后端六轮对抗性审查报告

日期：2026-07-27  
范围：BFS 后端 `main`，基线提交 `ccab46674`   
审查方式：六个独立审查面逐轮扫描，结合仓库静态门禁、配置校验、迁移校验和既有 CI 证据。

## 结论

本轮未发现新的 P0/P1 代码缺陷，也未发现新的跨租户越权、终态复活、重复成功或资源无界增长问题。当前代码可以继续进入既定 CI 和 staging 验收流程。

但“代码门禁通过”不等于“上线证据完整”。以下事项仍必须在生产同构 staging 完成并留档：PG/PITR 恢复、主备切换、Kafka 短时不可用与 DLQ 重放、全 worker 组崩溃后的精确一次收敛，以及目标负载下的容量/SLO 验收。

## 六轮结果

| 轮次 | 审查面 | 结果 | 主要证据 |
|---|---|---|---|
| 1 | 架构与模块边界 | 通过 | 模块拓扑、POM 依赖、SDK 隔离、核心/适配层边界无新增越界 |
| 2 | 安全、认证与租户隔离 | 通过 | secret 扫描通过；认证、bypass guard、RLS、危险执行器边界无新增高风险 |
| 3 | 数据库、迁移、事务与 MyBatis | 通过 | Flyway 192 个迁移顺序/唯一性/校验和通过；SQL 边界、RLS、位置插入检查通过 |
| 4 | 并发、状态机、Outbox、Kafka 与幂等 | 通过 | CAS、锁、lease、Outbox、重试和幂等路径无新增高风险 |
| 5 | 资源、性能、线程池与外部依赖 | 通过 | HTTP 响应上限、线程池边界、超时、Kafka/S3/PG 保护均存在 |
| 6 | 部署、运维、测试与恢复证据 | 代码门禁通过；staging 证据待补 | Helm/env/Kafka/告警/E2E 清单通过；灾备和容量需同构环境实跑 |

## 已执行的门禁

- `scripts/ci/check-e2e-shard-coverage.sh`：27 个 E2E 类与 `full-ci-gate.yml`、`staging-gate.yml` 清单一致。
- `scripts/ci/check-module-test-coverage.sh`：所有有测试的 reactor 模块均被 CI `-pl` 覆盖。
- `scripts/ci/check-version-alignment.sh`：预发版本、Chart GA 版本、生产镜像版本及 SDK 版本符合仓库规则。
- `scripts/ci/check-env-prod-sync.sh`：65 个关键变量同步通过；生产专用密码、密钥和限流变量的差异为预期告警。
- `scripts/ci/check-helm-env-sync.py`：应用配置与 Helm `BATCH_*` 消费入口一致。
- `scripts/ci/check-config-defaults-sync.py`：应用与 compose 参数同步通过，8 个网络拓扑差异和 1 个实例名差异均在规则内。
- `scripts/ci/validate-kafka-topics.sh`：10 个 topic、环境变量与 Java 常量双向一致。
- `scripts/ci/check-helm-prometheusrule-sync.sh`：告警规则副本与 canonical 一致。
- `scripts/ci/validate-flyway-schema.sh`：192 个迁移通过名称、顺序、非空、编码和校验和检查；V31→V32 的历史版本间隔已识别，不属于本轮新增漂移。
- `scripts/ci/check-sql-config-boundaries.sh`、`scripts/ci/check-migration-safety.sh`、业务表 RLS 检查、禁止位置插入检查：均通过。
- 安全 secret 扫描及安全扫描测试：通过，未发现新增泄露。

## 未完成的上线证据

这些不是本轮发现的代码 bug，而是不能仅凭仓库静态检查证明的运行环境事项：

1. **PITR/备份恢复**：已有 `scripts/db/backup/dr-drill.sh`、`scripts/sim/dr-drill-pitr.sh` 和 runbook，但 `docs/runbook/ha-readiness.md` 明确记录真实恢复演练尚未完成。需要在 staging 接入实际备份工具，记录 RTO/RPO 和恢复后数据指纹。
2. **主备与外部依赖故障**：需要实跑 PG failover、Kafka 短时不可用、DLQ 重放和 Redis 故障切换，记录恢复时间及最终状态。
3. **全 worker 组崩溃**：需要在生产同构 staging 中执行 kill/restart，验证 lease 回收、重投、Outbox 去重、单一 SUCCESS 和无终态复活。
4. **容量/SLO**：load-tests 工具和指标门已存在，但目标负载、峰值余量、锁等待、连接池、Kafka lag、Outbox backlog 尚需实测并留档。

## 保留的设计风险

- Redis 配额运行时状态故障时当前策略是 fail-open；这是已有设计，必须保证告警和数据库实现切换 runbook 可执行。
- Helm 基础 `values.yaml` 保持开发安全边界，生产必须使用 `values-prod.yaml` 或等价 overlay；生产隔离不是基础 values 自动推断出来的。
- `env-sync` 对生产专用凭据和限流变量只告警，不将其复制进示例文件；这避免把真实密钥形态带入示例，但发布流程必须检查这些变量已注入。
- 历史 Flyway 版本存在间隔，但当前没有重复、乱序或 checksum 漂移；不得通过重命名旧迁移修复历史编号。

## 后续验收顺序

1. staging 同构部署，执行完整 sim、E2E 和容量基线并归档报告。
2. 执行全 worker 崩溃、PG failover、Kafka 故障、DLQ 重放和 PITR 恢复演练。
3. 复核 SLO、RTO/RPO、告警触发和恢复动作幂等性后，再进行上线签字。

## 审查边界

本轮是严格六轮代码与仓库证据审查，不把未连接外部 staging 的灾备脚本当作真实演练，也没有修改生产密钥、基础 Helm 默认值或历史迁移。
